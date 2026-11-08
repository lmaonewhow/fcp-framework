package fcp.release.deploy;

import fcp.*;
import fcp.control.StateJournal;
import fcp.host.TrustedContext;
import fcp.release.control.*;
import fcp.release.routing.*;
import fcp.release.observation.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Trusted Linux single-controller process executor. Service commands are deployment configuration,
 * never received in release API requests. It does not execute migrations or manage arbitrary host JARs.
 */
public final class LocalProcessDeploymentProvider implements DeploymentProvider {
    public record ServiceSpec(String releaseId,String manifestDigest,Path artifact,String artifactSha256,
                              List<String> command,URI origin,Set<String> paths) {
        public ServiceSpec {
            if(!releaseId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")||!manifestDigest.matches("[a-f0-9]{64}")||!artifactSha256.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid service identity");
            artifact=artifact.toAbsolutePath().normalize();command=List.copyOf(command);paths=Set.copyOf(paths);
            if(command.isEmpty()||!Path.of(command.get(0)).isAbsolute()||command.stream().noneMatch("{artifact}"::equals))throw new IllegalArgumentException("Explicit executable and artifact placeholder required");
            if(!"http".equals(origin.getScheme())||!Set.of("127.0.0.1","[::1]").contains(origin.getHost())||origin.getPort()<1||!(origin.getPath().isEmpty()||origin.getPath().equals("/"))||origin.getQuery()!=null||origin.getFragment()!=null||origin.getUserInfo()!=null)throw new IllegalArgumentException("Dedicated numeric loopback service origin required");
            new ReleaseHttpTransport.Endpoint(releaseId,origin,paths);
        }
    }
    private final StateJournal journal;private final DurableReleaseRouter router;private final String environment;
    private final Map<String,ServiceSpec> specs;private final Clock clock;private final HttpClient http;
    private final Map<String,AtomicLong> inFlight=new ConcurrentHashMap<>();
    private final Map<String,ProcessHandle> owned=new HashMap<>();
    private final Map<String,Path> stagedArtifacts=new HashMap<>();
    private final ReleaseHttpTransport transport;
    private final WindowedObservation observations;
    public LocalProcessDeploymentProvider(StateJournal journal,DurableReleaseRouter router,Collection<ServiceSpec> specs,String environment,Clock clock,byte[] backendIdentityKey){
        this(journal,router,specs,environment,clock,backendIdentityKey,null);
    }
    public LocalProcessDeploymentProvider(StateJournal journal,DurableReleaseRouter router,Collection<ServiceSpec> specs,String environment,Clock clock,byte[] backendIdentityKey,WindowedObservation observations){
        this.observations=observations;
        this.journal=Objects.requireNonNull(journal);this.router=Objects.requireNonNull(router);this.clock=Objects.requireNonNull(clock);
        if(!environment.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}"))throw new IllegalArgumentException("Invalid environment");this.environment=environment;
        Map<String,ServiceSpec> map=new HashMap<>();Set<URI> origins=new HashSet<>();for(var spec:specs)if(map.putIfAbsent(spec.releaseId(),spec)!=null||!origins.add(spec.origin()))throw new IllegalArgumentException("Duplicate local service registration");this.specs=Map.copyOf(map);
        http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(1)).build();
        transport=new ReleaseHttpTransport(router,map.values().stream().map(s->new ReleaseHttpTransport.Endpoint(s.releaseId(),s.origin(),s.paths())).toList(),backendIdentityKey,clock,observations==null?RequestObserver.NONE:observations);
    }
    /** Process retirement is deliberately unsupported: arbitrary services need an application drain protocol. */
    public Set<Action> capabilities(){return Set.of(Action.DEPLOY,Action.CANARY,Action.EXPAND,Action.PAUSE,Action.ABORT,Action.PROMOTE,Action.ROLLBACK);}
    @Override public boolean supportsRecovery(){return true;}
    /** This journal never expires/deletes operation receipts. Absence is authoritative only at a
     * valid next generation with no unresolved claim; unreadable or conflicting state is not absence. */
    @Override public synchronized RecoveryDisposition recovery(Command command)throws IOException {
        if(!environment.equals(command.plan().environment())||!capabilities().contains(command.action()))throw new IOException("LOCAL_CAPABILITY_UNSUPPORTED");
        if(command.action()!=Action.ABORT){spec(command.plan().stableRelease(),command.plan().stableDigest());spec(command.plan().candidateRelease(),command.plan().candidateDigest());}
        State state=load();Map<String,Object> operations=Schema.map(state.value().get("operations"));Object found=operations.get(command.operationId());
        if(found!=null){verifyReceipt(Schema.map(found),command);return RecoveryDisposition.ACCEPTED;}
        long last=state.value().containsKey("lastGeneration")?Schema.number(state.value().get("lastGeneration")):0;
        if(command.generation()!=last+1||operations.values().stream().anyMatch(v->!Set.of("COMPLETE","FAILED").contains(Schema.map(v).get("state"))))return RecoveryDisposition.UNKNOWN;
        return RecoveryDisposition.NOT_ACCEPTED;
    }
    @Override public synchronized void resumeNotAccepted(Command command)throws IOException {
        RecoveryDisposition disposition=recovery(command);
        if(disposition==RecoveryDisposition.UNKNOWN)throw new IOException("LOCAL_RECOVERY_NOT_AUTHORITATIVE");
        // submit performs the durable CAS claim before any launch or route write. Another executor
        // winning that claim causes either exact replay or CAS failure, never a duplicated launch.
        if(disposition==RecoveryDisposition.NOT_ACCEPTED)submit(command);
    }
    private static void verifyReceipt(Map<String,Object> receipt,Command command)throws IOException {
        if(!command.digest().equals(receipt.get("commandDigest"))||!Objects.equals(receipt.get("command"),Json.copy(command.json())))throw new IOException("LOCAL_OPERATION_CONFLICT");
    }
    private record State(long revision,Map<String,Object> value){}
    private State load()throws IOException{var s=journal.load();Map<String,Object> v=s.json()==null?new LinkedHashMap<>(Map.of("format",1,"processes",new LinkedHashMap<>(),"operations",new LinkedHashMap<>())):Schema.map(Json.parse(s.json()));if(Schema.number(v.get("format"))!=1)throw new IOException("LOCAL_EXECUTOR_STATE_FORMAT");return new State(s.revision(),v);}
    private State save(State s)throws IOException{var n=journal.compareAndSet(s.revision(),Json.write(s.value()));return new State(n.revision(),s.value());}
    private ServiceSpec spec(String release,String digest)throws IOException{var s=specs.get(release);if(s==null||!s.manifestDigest().equals(digest))throw new IOException("LOCAL_RELEASE_NOT_REGISTERED");return s;}
    @Override public synchronized void submit(Command command)throws IOException{
        if(!environment.equals(command.plan().environment())||!capabilities().contains(command.action()))throw new IOException("LOCAL_CAPABILITY_UNSUPPORTED");
        if((Set.of(Action.DEPLOY,Action.ABORT,Action.ROLLBACK).contains(command.action())&&command.basisPoints()!=0)||(command.action()==Action.PROMOTE&&command.basisPoints()!=10000)||(Set.of(Action.CANARY,Action.EXPAND).contains(command.action())&&(command.basisPoints()==0||command.basisPoints()==10000)))throw new IOException("LOCAL_ACTION_PERCENT_INVALID");
        ServiceSpec stable=command.action()==Action.ABORT?null:spec(command.plan().stableRelease(),command.plan().stableDigest()),candidate=command.action()==Action.ABORT?null:spec(command.plan().candidateRelease(),command.plan().candidateDigest());
        State state=load();Map<String,Object> ops=Schema.map(state.value().get("operations"));Object existing=ops.get(command.operationId());
        if(existing!=null){verifyReceipt(Schema.map(existing),command);return;}
        if(ops.values().stream().anyMatch(v->!Set.of("COMPLETE","FAILED").contains(Schema.map(v).get("state"))))throw new IOException("LOCAL_OPERATION_PENDING");
        long last=state.value().containsKey("lastGeneration")?Schema.number(state.value().get("lastGeneration")):0;
        if(command.generation()!=last+1)throw new IOException("LOCAL_GENERATION_CONFLICT");
        ops.put(command.operationId(),new LinkedHashMap<>(Map.of("command",command.json(),"commandDigest",command.digest(),"state","INTENT")));state=save(state);
        if(command.action()==Action.DEPLOY){state=start(state,stable);state=start(state,candidate);}
        boolean stableOnly=command.action()==Action.ROLLBACK;
        if(command.action()!=Action.ABORT){
            boolean ready;
            try{ready=healthy(state,stable)&&(stableOnly||healthy(state,candidate));}
            catch(IOException|RuntimeException failure){
                if(command.action()==Action.DEPLOY)throw failure; // Launch may already have occurred.
                failBeforeRoute(state,command);return;
            }
            if(!ready){
                if(command.action()==Action.DEPLOY)throw new IOException("LOCAL_SERVICE_NOT_READY");
                failBeforeRoute(state,command);return;
            }
        }
        var previous=router.policy().orElse(null);long epoch=previous==null?0:previous.epoch();
        if(command.action()==Action.ABORT||command.action()==Action.ROLLBACK)epoch=Math.addExact(epoch,1);
        var next=new ReleasePolicy("local-"+environment,command.plan().stableRelease(),command.plan().candidateRelease(),command.basisPoints(),Set.of(),Set.of(),command.action()!=Action.ABORT,epoch);
        var intent=Schema.map(Schema.map(state.value().get("operations")).get(command.operationId()));
        intent.put("expectedRouterRevision",router.revision());intent.put("epoch",epoch);state=save(state);
        if(observations!=null){long routeRevision=Schema.number(intent.get("expectedRouterRevision"))+1;
            observations.bind(command.plan().stableRelease(),command.generation(),routeRevision);
            observations.bind(command.plan().candidateRelease(),command.generation(),routeRevision);}
        router.put(next,Schema.number(intent.get("expectedRouterRevision")));
        var entry=Schema.map(Schema.map(state.value().get("operations")).get(command.operationId()));entry.put("state","COMPLETE");entry.put("routerRevision",router.revision());state.value().put("lastGeneration",command.generation());save(state);
    }
    /** Only called for non-launch actions before any router mutation. A failed CAS is still unknown. */
    private void failBeforeRoute(State state,Command command)throws IOException {
        var policy=router.policy().orElse(null);if(policy==null)throw new IOException("LOCAL_ROUTE_STATE_UNAVAILABLE");
        var receipt=Schema.map(Schema.map(state.value().get("operations")).get(command.operationId()));
        receipt.put("state","FAILED");receipt.put("detail","LOCAL_PRE_ROUTE_FAILED");receipt.put("routerRevision",router.revision());
        state.value().put("lastGeneration",command.generation());save(state);
    }
    private State start(State state,ServiceSpec spec)throws IOException{
        Map<String,Object> processes=Schema.map(state.value().get("processes"));
        if(processes.containsKey(spec.releaseId())){if(!healthy(state,spec))throw new IOException("LOCAL_PROCESS_REQUIRES_OPERATOR_RECONCILIATION");return state;}
        Path part=spec.artifact().getRoot();for(Path p:spec.artifact()){part=part.resolve(p);if(Files.isSymbolicLink(part))throw new IOException("LOCAL_ARTIFACT_SYMLINK");}
        if(!Files.isRegularFile(spec.artifact(),LinkOption.NOFOLLOW_LINKS)||Files.size(spec.artifact())>256*1024*1024)throw new IOException("LOCAL_ARTIFACT_INVALID");
        ByteArrayOutputStream snapshot=new ByteArrayOutputStream();String digest;try(InputStream in=Files.newInputStream(spec.artifact())){MessageDigest hash=MessageDigest.getInstance("SHA-256");byte[] b=new byte[65536];for(int n;(n=in.read(b))!=-1;){if(snapshot.size()+n>256*1024*1024)throw new IOException("LOCAL_ARTIFACT_LIMIT");hash.update(b,0,n);snapshot.write(b,0,n);}digest=HexFormat.of().formatHex(hash.digest());}catch(NoSuchAlgorithmException e){throw new IOException(e);}
        if(!digest.equals(spec.artifactSha256()))throw new IOException("LOCAL_ARTIFACT_DIGEST_MISMATCH");
        // Refuse occupied endpoint: never adopt somebody else's service by matching a release label.
        try{http.send(HttpRequest.newBuilder(spec.origin().resolve("/fcp/health")).timeout(Duration.ofSeconds(1)).GET().build(),HttpResponse.BodyHandlers.discarding());throw new IOException("LOCAL_ENDPOINT_ALREADY_OCCUPIED");}catch(java.net.ConnectException expected){}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
        String nonce=UUID.randomUUID().toString();Map<String,Object> record=new LinkedHashMap<>(Map.of("instance",nonce,"phase","START_INTENT"));processes.put(spec.releaseId(),record);state=save(state);
        // Execute the exact bytes checked above, not a mutable source path after digest verification.
        Path staging=Files.createTempDirectory("fcp-release-artifact-");Path staged=staging.resolve(spec.artifact().getFileName());Files.write(staged,snapshot.toByteArray(),StandardOpenOption.CREATE_NEW);Files.setPosixFilePermissions(staged,java.nio.file.attribute.PosixFilePermissions.fromString("r--------"));stagedArtifacts.put(spec.releaseId(),staged);
        List<String> command=spec.command().stream().map(x->x.equals("{artifact}")?staged.toString():x).toList();ProcessBuilder builder=new ProcessBuilder(command).directory(spec.artifact().getParent().toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().clear();builder.environment().put("LANG","C.UTF-8");builder.environment().put("FCP_RELEASE_ID",spec.releaseId());builder.environment().put("FCP_RELEASE_DIGEST",spec.manifestDigest());builder.environment().put("FCP_RELEASE_INSTANCE",nonce);builder.environment().put("FCP_RELEASE_PORT",String.valueOf(spec.origin().getPort()));
        Process process=builder.start();ProcessHandle handle=process.toHandle();owned.put(spec.releaseId(),handle);Instant start=handle.info().startInstant().orElseThrow(()->new IOException("LOCAL_PROCESS_START_IDENTITY_UNAVAILABLE"));record.put("pid",handle.pid());record.put("start",start.toString());record.put("phase","STARTED");state=save(state);
        long until=System.nanoTime()+Duration.ofSeconds(10).toNanos();while(System.nanoTime()<until){if(healthy(state,spec))return state;if(!handle.isAlive())throw new IOException("LOCAL_PROCESS_EXITED");try{Thread.sleep(50);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}}
        throw new IOException("LOCAL_HEALTH_TIMEOUT");
    }
    private boolean healthy(State state,ServiceSpec spec)throws IOException{
        Object raw=Schema.map(state.value().get("processes")).get(spec.releaseId());if(raw==null)return false;Map<String,Object> record=Schema.map(raw);
        if(!record.containsKey("pid")||!record.containsKey("start"))return false;
        var handle=ProcessHandle.of(Schema.number(record.get("pid")));if(handle.isEmpty()||!handle.get().isAlive()||!handle.get().info().startInstant().map(Instant::toString).orElse("").equals(record.get("start")))return false;
        CompletableFuture<HttpResponse<byte[]>> pending=http.sendAsync(HttpRequest.newBuilder(spec.origin().resolve("/fcp/health")).timeout(Duration.ofSeconds(1)).GET().build(),info->new HealthBody());
        try{var r=pending.get(1,TimeUnit.SECONDS);byte[] b=r.body();if(r.statusCode()!=200)return false;var h=Schema.map(Json.parse(new String(b,java.nio.charset.StandardCharsets.UTF_8)));return h.keySet().equals(Set.of("releaseId","manifestDigest","instanceId","pid"))&&spec.releaseId().equals(h.get("releaseId"))&&spec.manifestDigest().equals(h.get("manifestDigest"))&&record.get("instance").equals(h.get("instanceId"))&&Schema.number(h.get("pid"))==handle.get().pid();}
        catch(ExecutionException|TimeoutException|RuntimeException e){return false;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}finally{pending.cancel(true);}
    }
    private static final class HealthBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();private final ByteArrayOutputStream out=new ByteArrayOutputStream();private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);}
        public void onNext(List<java.nio.ByteBuffer> chunks){for(var chunk:chunks){if(chunk.remaining()>8192-out.size()){subscription.cancel();result.completeExceptionally(new IOException("HEALTH_RESPONSE_LIMIT"));return;}byte[] bytes=new byte[chunk.remaining()];chunk.get(bytes);out.writeBytes(bytes);}subscription.request(1);}
        public void onError(Throwable e){result.completeExceptionally(e);}
        public void onComplete(){result.complete(out.toByteArray());}
    }
    @Override public synchronized Optional<Observation> observe(String environment,String operationId)throws IOException{
        if(!this.environment.equals(environment))throw new IOException("LOCAL_ENVIRONMENT_MISMATCH");State state=load();Object raw=Schema.map(state.value().get("operations")).get(operationId);if(raw==null)return Optional.empty();Map<String,Object> entry=Schema.map(raw),cmd=Schema.map(entry.get("command"));ReleasePlan plan=ReleasePlan.parse(Schema.map(cmd.get("plan")));
        Action action=Action.valueOf(Schema.str(cmd.get("action")));var actual=router.policy().orElse(null);
        if("FAILED".equals(entry.get("state"))){
            if(actual==null)throw new IOException("LOCAL_ROUTE_STATE_UNAVAILABLE");
            long consumed=Schema.number(state.value().get("lastGeneration"));
            return Optional.of(new Observation(operationId,consumed==Schema.number(cmd.get("generation")),false,plan.candidateRelease(),plan.candidateDigest(),actual.basisPoints(),consumed,inFlight.values().stream().mapToLong(AtomicLong::get).sum(),"LOCAL_PRE_ROUTE_FAILED",actual.enabled()?RoutingMode.ACTIVE:RoutingMode.STOPPED,consumed==Schema.number(cmd.get("generation"))?ExecutionOutcome.FAILED:ExecutionOutcome.PENDING));
        }
        ServiceSpec stable=action==Action.ABORT?null:spec(plan.stableRelease(),plan.stableDigest()),candidate=action==Action.ABORT?null:spec(plan.candidateRelease(),plan.candidateDigest());var policy=actual;int desired=Math.toIntExact(Schema.number(cmd.get("basisPoints")));boolean routed=policy!=null&&(action==Action.ABORT?!policy.enabled():policy.enabled())&&policy.stableRelease().equals(plan.stableRelease())&&policy.candidateRelease().equals(plan.candidateRelease())&&policy.basisPoints()==desired;
        boolean stableOnly=action==Action.ROLLBACK;
        boolean healthy=action!=Action.ABORT&&healthy(state,stable)&&(stableOnly||healthy(state,candidate));boolean ready=action==Action.ABORT||healthy;
        if("INTENT".equals(entry.get("state"))&&ready&&routed&&entry.containsKey("expectedRouterRevision")&&router.revision()==Schema.number(entry.get("expectedRouterRevision"))+1&&policy.epoch()==Schema.number(entry.get("epoch"))){
            entry.put("state","COMPLETE");entry.put("routerRevision",router.revision());state.value().put("lastGeneration",Schema.number(cmd.get("generation")));save(state);
        }
        long observedGeneration=state.value().containsKey("lastGeneration")?Schema.number(state.value().get("lastGeneration")):0;
        boolean complete="COMPLETE".equals(entry.get("state"))&&routed&&ready&&entry.containsKey("routerRevision")&&Schema.number(entry.get("routerRevision"))==router.revision()&&observedGeneration==Schema.number(cmd.get("generation"));
        // A crash between route write and receipt is unknown; operator reconciliation must not relaunch.
        return Optional.of(new Observation(operationId,complete,healthy,plan.candidateRelease(),plan.candidateDigest(),policy==null?0:policy.basisPoints(),observedGeneration,inFlight.values().stream().mapToLong(AtomicLong::get).sum(),complete?(action==Action.ABORT?"LOCAL_INGRESS_STOPPED":"LOCAL_PROCESSES_AND_ROUTE_VERIFIED"):"LOCAL_OUTCOME_REQUIRES_RECONCILIATION",policy!=null&&!policy.enabled()?RoutingMode.STOPPED:RoutingMode.ACTIVE));
    }
    public ReleaseHttpTransport.Response invoke(TrustedContext context,String affinity,String method,String path,byte[] body)throws IOException,InterruptedException{
        if(observations!=null)synchronized(this){State state=load();var policy=router.policy().orElseThrow(()->new IOException("LOCAL_ROUTE_NOT_READY"));long generation=Schema.number(state.value().getOrDefault("lastGeneration",0));
            if(generation<1)throw new IOException("LOCAL_GENERATION_UNCONFIRMED");
            observations.bind(policy.stableRelease(),generation,router.revision());observations.bind(policy.candidateRelease(),generation,router.revision());}
        AtomicLong count=inFlight.computeIfAbsent(context.tenantId(),x->new AtomicLong());count.incrementAndGet();try{return transport.invoke(context,affinity,method,path,body);}finally{count.decrementAndGet();}
    }
    /** Explicit test/development cleanup only; does not claim application drain or production retirement. */
    public synchronized void stopOwnedProcesses()throws IOException {
        if(inFlight.values().stream().anyMatch(n->n.get()!=0))throw new IOException("LOCAL_REQUESTS_IN_FLIGHT");
        for(ProcessHandle handle:owned.values())if(handle.isAlive())handle.destroy();
        for(ProcessHandle handle:owned.values())try{handle.onExit().get(5,TimeUnit.SECONDS);}catch(Exception e){throw new IOException("LOCAL_PROCESS_STOP_NOT_CONFIRMED",e);}owned.clear();for(Path staged:stagedArtifacts.values()){Files.deleteIfExists(staged);Files.deleteIfExists(staged.getParent());}stagedArtifacts.clear();
    }
}
