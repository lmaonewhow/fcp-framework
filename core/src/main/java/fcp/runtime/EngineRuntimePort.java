package fcp.runtime;

import fcp.*;
import fcp.control.*;
import fcp.host.*;
import fcp.plugin.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import static fcp.Schema.*;

/** Real Engine catalog adapter and local stdio runner for trusted Python/Node packages.
 * No OS sandbox is claimed. All process launch configuration is host-owned.
 */
public final class EngineRuntimePort implements RuntimePort,AutoCloseable {
    private final Engine engine;
    private final HostCapabilities host;
    private final VerifiedPackageRepository repository;
    private final Map<String,String> executables;
    private final InvocationJournal journal;
    private final ThreadLocal<TrustedContext> context=new ThreadLocal<>();
    private final ThreadLocal<Boolean> journaled=new ThreadLocal<>();
    private final ThreadLocal<Long> callDeadline=new ThreadLocal<>();
    private final ThreadLocal<Deque<String>> invocationSources=ThreadLocal.withInitial(ArrayDeque::new);
    @FunctionalInterface public interface StorageCompatibilityFingerprint { String fingerprint(String plugin,String version,String digest,Map<String,Object> declaration); }
    private volatile StorageCompatibilityFingerprint storageFingerprint=(plugin,version,digest,declaration)->{throw new FcpException("SCHEMA_FINGERPRINT_REQUIRED","Version plans require a read-only schema fingerprint adapter");};
    @FunctionalInterface public interface StorageCompatibilityVerifier { void verify(String plugin,String version,String digest,Map<String,Object> storageDeclaration); }
    private volatile StorageCompatibilityVerifier storageVerifier=(plugin,version,digest,declaration)->{throw new FcpException("STORAGE_ADAPTER_REQUIRED","Storage declarations require the production migration adapter");};
    @FunctionalInterface public interface HostCapabilityHandler { Object invoke(String serverPluginId,TrustedContext context,String capabilityId,Map<String,Object> input); }
    private record HostBinding(PluginManifest.Capability contract,HostCapabilityHandler handler) {}
    private final Map<String,HostBinding> hostBindings=new ConcurrentHashMap<>();
    private final Map<String,Runner> runners=new ConcurrentHashMap<>();
    private final Map<String,PackageInstaller.InstalledPackage> packages=new ConcurrentHashMap<>();
    private final Map<String,Set<String>> enabled=new ConcurrentHashMap<>();
    private final Map<String,Long> generations=new ConcurrentHashMap<>();
    private final Map<String,String> publications=new HashMap<>();
    private final Map<String,AtomicInteger> calls=new ConcurrentHashMap<>();
    private final Set<String> cancelRequested=ConcurrentHashMap.newKeySet();
    private final int restartBudget;
    private boolean closed;
    private static final class Runner {
        final PackageInstaller.InstalledPackage pkg;final ProcessProvider transport;final String nonce;volatile ProcessProvider.Identity identity;Instant restartAttempt;
        Runner(PackageInstaller.InstalledPackage p,ProcessProvider transport){pkg=p;this.transport=transport;nonce=UUID.randomUUID().toString();}
    }
    public EngineRuntimePort(HostCapabilities host,VerifiedPackageRepository repository,Map<String,String> executables,Path callJournal,int restartBudget)throws IOException {
        this(host,repository,executables,new FileStateJournal(callJournal),restartBudget);
    }
    public EngineRuntimePort(HostCapabilities host,VerifiedPackageRepository repository,Map<String,String> executables,StateJournal callJournal,int restartBudget)throws IOException {
        if(restartBudget<1||restartBudget>100)throw new IllegalArgumentException("restart budget");
        this.host=Objects.requireNonNull(host);this.repository=Objects.requireNonNull(repository);this.executables=Map.copyOf(executables);this.restartBudget=restartBudget;
        for(String path:executables.values())if(!Path.of(path).isAbsolute()||!Files.isExecutable(Path.of(path)))throw new IllegalArgumentException("Host runtime must be an absolute executable");
        journal=new InvocationJournal(callJournal);Registry registry=new Registry();
        engine=new Engine(registry,(ctx,target,operation)->{TrustedContext trusted=trusted(ctx);String module=target.split("/")[0];Map<String,Object> config=obj(registry.modules.get(module),"config");String p=str(config.get("plugin"));String capability=target.split("/")[1];if(Boolean.TRUE.equals(config.get("hostCapability"))){if(!hostBindings.containsKey(capability))throw new FcpException("CAPABILITY_MISSING",capability);}else {authorizeDependency(trusted,p,capability);authorize(trusted,p,capability);}});
        registry.factories.put("host-capability",(document,scope)->{String source=str(obj(document,"config").get("plugin"));return (ctx,cap,op,input)->{
            TrustedContext trusted=trusted(ctx);if(!source.equals(invocationSources.get().peek()))throw new FcpException("DEPENDENCY_NOT_DECLARED","Host capability source binding mismatch");if(callDeadline.get()==null||System.nanoTime()>=callDeadline.get())throw new FcpException("DEADLINE","Parent invocation deadline expired");HostBinding binding=authorizeHost(trusted,source,cap,input);
            if(binding.contract().sideEffects()&&!Boolean.TRUE.equals(journaled.get()))throw new FcpException("SIDE_EFFECT_CONTEXT_REQUIRED","Host write requires a journaled top-level operation");
            if(host instanceof HostCommitFence fence)return fence.commit(trusted,()->{
                HostBinding current=authorizeHost(trusted,source,cap,input);
                return current.handler().invoke(source,trusted,cap,input);
            });
            return binding.handler().invoke(source,trusted,cap,input);
        };});
        registry.factories.put("managed-stdio",(document,scope)->{String p=str(obj(document,"config").get("plugin"));return (ctx,cap,op,input)->invokeProvider(ctx,p,cap,input);});
    }
    /** Startup-only, read-only compatibility verifier. Migration/DDL needs a separate approved deployment port. */
    public synchronized void setStorageCompatibilityVerifier(StorageCompatibilityVerifier verifier){live();if(!enabled.isEmpty())throw new FcpException("RUNTIME_IN_USE","Set storage verifier before publishing tenants");storageVerifier=Objects.requireNonNull(verifier);}
    public synchronized void setStorageCompatibilityFingerprint(StorageCompatibilityFingerprint fingerprint){live();if(!enabled.isEmpty())throw new FcpException("RUNTIME_IN_USE","Configure schema fingerprint before publication");storageFingerprint=Objects.requireNonNull(fingerprint);}
    @Override public String storageCompatibilityFingerprint(String plugin,String version,String digest){var pkg=resolve(plugin,version,digest);if(pkg.manifest().storage().isEmpty())return "no-declared-schema";storageVerifier.verify(plugin,version,digest,pkg.manifest().storage());String token=storageFingerprint.fingerprint(plugin,version,digest,pkg.manifest().storage());if(token==null||token.isBlank()||token.length()>4096)throw new FcpException("SCHEMA_FINGERPRINT_REQUIRED","Invalid schema fingerprint");return token;}
    /** Server-owned approved port registration; a manifest declaration alone does not grant access. */
    public synchronized void registerHostCapability(PluginManifest.Capability contract,HostCapabilityHandler handler){
        live();Schema.check(contract.inputSchema());Schema.check(contract.outputSchema());
        if(hostBindings.putIfAbsent(contract.id(),new HostBinding(contract,Objects.requireNonNull(handler)))!=null)throw new FcpException("DUPLICATE","Host capability already registered");
    }
    private HostBinding authorizeHost(TrustedContext context,String sourcePlugin,String capability,Map<String,Object> input){
        HostBinding binding=hostBindings.get(capability);if(binding==null)throw new FcpException("CAPABILITY_MISSING",capability);
        String scope="tenant:"+context.tenantId();
        if(capability.startsWith("host.storage.")){Object value=input.get("dataScope");if(!(value instanceof String requested)||!(requested.equals(scope)||requested.matches("class:[A-Za-z0-9_.-]{1,100}")))throw new FcpException("SCOPE_DENIED","Invalid storage scope");scope=requested;}
        host.require(context,new HostCapabilities.Grant(sourcePlugin,capability,"invoke",scope));return binding;
    }
    /** Server-authenticated caller only. expectedGeneration is the Engine catalog revision from describe. */
    public Object invoke(TrustedContext trusted,String plugin,String capability,Map<String,Object> input,String idempotencyKey,Long expectedGeneration){
        if(context.get()!=null)throw new FcpException("CONTEXT_REENTRY","Use declared dependency callbacks");
        authorize(trusted,plugin,capability);if(expectedGeneration!=null&&!expectedGeneration.equals(generations.getOrDefault(trusted.tenantId(),0L)))throw new FcpException("STALE_COMPOSITION","Client catalog changed");PluginManifest.Capability spec=capability(plugin,capability);String request=null;
        if(spec.sideEffects()) {
            if(idempotencyKey==null||idempotencyKey.isBlank()||idempotencyKey.length()>256)throw new FcpException("IDEMPOTENCY_REQUIRED","Side effects require an idempotency key");
            request=hash(List.of(trusted.principal().kind().name(),trusted.principal().id(),trusted.tenantId(),plugin,capability,idempotencyKey));
            Map<String,Object> prior=journal.begin(request,hash(canonical(input)));
            if(prior!=null){if("COMPLETE".equals(prior.get("outcome")))return prior.get("result");throw new FcpException("OUTCOME_UNKNOWN","Prior request may have committed; reconcile rather than replay");}
        }
        context.set(trusted);journaled.set(request!=null);callDeadline.set(System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.min(spec.timeoutMillis(),Math.max(1,java.time.Duration.between(host.clock().instant(),trusted.deadline()).toMillis()))));
        try{Object result=engine.invoke(scope(trusted.tenantId()),export(plugin,capability),"invoke",input,Map.of(),expectedGeneration);if(request!=null)journal.complete(request,result);return result;}
        catch(FcpException e){if(request!=null)throw new FcpException("OUTCOME_UNKNOWN","Side-effect outcome requires reconciliation; original code="+e.code,e);throw e;}
        finally{context.remove();journaled.remove();callDeadline.remove();invocationSources.remove();}
    }
    public Map<String,Object> describe(TrustedContext trusted){host.require(trusted,new HostCapabilities.Grant("control","plugin.lifecycle","status","tenant:"+trusted.tenantId()));return engine.describe(scope(trusted.tenantId()));}
    public record EnabledVersion(String version,String digest) {}
    /** Internal execution-service metadata only. This grants no business access and is not an HTTP endpoint.
     * Tasks need not possess lifecycle/status permission; invocation still enforces live host authorization.
     */
    public synchronized EnabledVersion enabledVersion(TrustedContext trusted,String plugin){
        live();Objects.requireNonNull(trusted);checkDeadline(trusted);
        if(!enabled.getOrDefault(trusted.tenantId(),Set.of()).contains(plugin))throw new FcpException("ADMISSION_CLOSED","Plugin is not enabled for this tenant");
        Runner runner=runners.get(plugin);
        if(runner==null||!sameIdentity(runner)||!ready(plugin,runner.pkg.manifest().version(),runner.pkg.sha256()))throw new FcpException("RUNNER_UNAVAILABLE","Plugin runner is not ready");
        checkDeadline(trusted);return new EnabledVersion(runner.pkg.manifest().version(),runner.pkg.sha256());
    }
    private void checkDeadline(TrustedContext trusted){if(!host.clock().instant().isBefore(trusted.deadline()))throw new FcpException("DEADLINE_EXCEEDED","Request deadline expired");}
    public record CatalogSnapshot(long generation,Map<String,PluginManifest> enabledManifests) { public CatalogSnapshot { enabledManifests=Map.copyOf(enabledManifests); } }
    /** Atomic server projection of the effective tenant catalog, never the installation inventory. */
    public synchronized CatalogSnapshot snapshot(TrustedContext trusted){
        host.require(trusted,new HostCapabilities.Grant("control","plugin.lifecycle","status","tenant:"+trusted.tenantId()));
        Map<String,PluginManifest> manifests=new TreeMap<>();for(String p:enabled.getOrDefault(trusted.tenantId(),Set.of())){Runner r=runners.get(p);if(r!=null&&sameIdentity(r))manifests.put(p,r.pkg.manifest());}
        return new CatalogSnapshot(generations.getOrDefault(trusted.tenantId(),0L),manifests);
    }
    @Override public synchronized void resetAdmissions(){for(String tenant:new ArrayList<>(enabled.keySet()))publish(tenant,Set.of(),0);enabled.clear();}
    @Override public void verifyInstalledPackage(PackageInstaller.InstalledPackage pkg){try{repository.verify(pkg);packages.put(pkg.manifest().pluginId(),pkg);}catch(IOException e){throw new FcpException("PACKAGE_INTEGRITY","Installed package revalidation failed",e);}}
    @Override public void verifyStorageCompatibility(String plugin,String version,String digest){
        var pkg=resolve(plugin,version,digest);if(!pkg.manifest().storage().isEmpty())storageVerifier.verify(plugin,version,digest,pkg.manifest().storage());
    }
    @Override public synchronized void prepare(String plugin,String version,String digest){
        live();Runner prior=runners.get(plugin);if(prior!=null&&prior.pkg.sha256().equals(digest)&&sameIdentity(prior))return;
        if(prior!=null){if(totalCalls(plugin)>0)throw new FcpException("RUNNER_BUSY","Cannot replace runner with admitted calls");prior.transport.close();runners.remove(plugin);}
        Instant now=host.clock().instant();
        var pkg=resolve(plugin,version,digest);String entry=pkg.manifest().runtime().entry();String kind=entry.endsWith(".py")?"python":entry.endsWith(".js")||entry.endsWith(".mjs")?"node":"";String exe=executables.get(kind);if(exe==null)throw new FcpException("RUNTIME_MISSING","No approved interpreter for entry");
        List<Object> argv=new ArrayList<>();argv.add(exe);argv.add(pkg.directory().resolve(entry).toString());argv.addAll(pkg.manifest().runtime().argv());
        Map<String,Object> launcher=Json.obj("argv",argv,"cwd",".","env",Map.of(),"requires",List.of());
        Map<String,Object> document=Json.obj("runtime",Json.obj("launcher","plugin","timeout",60,"keepAlive",true,"autoRestart",false,"methods",Map.of()));
        Runner runner=new Runner(pkg,new ProcessProvider(document,pkg.directory(),Map.of("plugin",launcher)));journal.reserveRestart(plugin,now,restartBudget);runner.restartAttempt=now;
        try{Object handshake=control(runner,"handshake",Json.obj("protocol","fcp.runtime/1","pluginId",plugin,"version",version,"nonce",runner.nonce));Map<String,Object> response=map(handshake);
            if(!response.equals(Json.obj("protocol","fcp.runtime/1","pluginId",plugin,"version",version,"nonce",runner.nonce)))throw new FcpException("HANDSHAKE","Runner identity handshake mismatch");
            runner.identity=runner.transport.identity();if(runner.identity==null||runner.identity.startedAt().equals(Instant.EPOCH))throw new FcpException("RUNNER_IDENTITY","OS start identity unavailable");
            requireOk(control(runner,"ready",Map.of()));runners.put(plugin,runner);packages.put(plugin,pkg);
        }catch(RuntimeException e){runner.transport.close();throw e;}
    }
    @Override public boolean ready(String plugin,String version,String digest){Runner r=runners.get(plugin);if(r==null||!r.pkg.sha256().equals(digest)||!sameIdentity(r))return false;try{requireOk(control(r,"health",Map.of()));return sameIdentity(r);}catch(RuntimeException e){return false;}}
    public Map<String,Object> health(String plugin){Runner r=runners.get(plugin);return Json.obj("ready",r!=null&&sameIdentity(r),"pid",r==null||r.identity==null?null:r.identity.pid(),"startedAt",r==null||r.identity==null?null:r.identity.startedAt().toString(),"activeCalls",totalCalls(plugin));}
    @Override public synchronized void publish(String tenant,Set<String> plugins,long generation){
        live();List<Object> identities=new ArrayList<>();
        for(String p:new TreeSet<>(plugins)){Runner r=runners.get(p);if(r==null||!sameIdentity(r))throw new FcpException("NOT_READY",p);identities.add(List.of(p,r.pkg.sha256(),r.identity.pid(),r.identity.startedAt().toString()));}
        String publication=hash(List.of(generation,identities));if(publication.equals(publications.get(tenant)))return;
        List<Map<String,Object>> documents=new ArrayList<>();List<Object> modules=new ArrayList<>();Map<String,Object> exports=new LinkedHashMap<>(),bindings=new LinkedHashMap<>();
        for(String p:new TreeSet<>(plugins)){var pkg=packages.get(p);Runner r=runners.get(p);if(pkg==null||r==null||!sameIdentity(r))throw new FcpException("NOT_READY",p);String module=module(pkg);modules.add(module);Map<String,Object> provides=new LinkedHashMap<>(),requires=new LinkedHashMap<>();
            for(var cap:pkg.manifest().provides()){String contract=contract(cap);addDocument(documents,"contract",Json.obj("id",contract,"operations",Map.of("invoke",Json.obj("input",cap.inputSchema(),"output",cap.outputSchema(),"effect",cap.sideEffects()?"external":"read"))));provides.put(cap.id(),contract);exports.put(export(p,cap.id()),module+"/"+cap.id());}
            for(var cap:pkg.manifest().requires()){String contract=contract(cap);String target=null;
                HostBinding hostBinding=hostBindings.get(cap.id());
                if(hostBinding!=null){
                    if(!contract(hostBinding.contract()).equals(contract))throw new FcpException("CONTRACT_MISMATCH","Host contract mismatch: "+cap.id());
                    String hostModule="h"+hash(List.of(p,contract)).substring(0,40);modules.add(hostModule);
                    addDocument(documents,"contract",Json.obj("id",contract,"operations",Map.of("invoke",Json.obj("input",cap.inputSchema(),"output",cap.outputSchema(),"effect",cap.sideEffects()?"external":"read"))));
                    addDocument(documents,"module",Json.obj("protocol","fcp/0.3","kind","module","id",hostModule,"version",cap.version(),"provides",Map.of(cap.id(),contract),"runtime",Map.of("provider","host-capability"),"config",Map.of("plugin",p,"hostCapability",true)));
                    target=hostModule+"/"+cap.id();
                }
                for(String dependency:plugins){var dep=packages.get(dependency);if(pkg.manifest().dependencies().stream().noneMatch(d->d.pluginId().equals(dependency)))continue;for(var actual:dep.manifest().provides())if(contract(actual).equals(contract)){if(target!=null)throw new FcpException("DEPENDENCY_AMBIGUOUS",cap.id());target=module(dep)+"/"+actual.id();}}
                if(target==null)throw new FcpException("DEPENDENCY_MISSING",cap.id());requires.put(cap.id(),Map.of("contract",contract));bindings.put(module+"/"+cap.id(),target);
            }
            addDocument(documents,"module",Json.obj("protocol","fcp/0.3","kind","module","id",module,"version",pkg.manifest().version(),"provides",provides,"requires",requires,"runtime",Map.of("provider","managed-stdio"),"config",Map.of("plugin",p,"digest",pkg.sha256()),"ui",List.of()));
        }
        Map<String,Object> app=Json.obj("protocol","fcp/0.3","kind","composition","id","runtime","modules",modules,"exports",exports,"bindings",bindings,"ui",List.of(),"profile",Map.of("tenant",tenant,"intentGeneration",generation));
        var description=engine.publishCatalog(scope(tenant),documents,app,generations.getOrDefault(tenant,0L));generations.put(tenant,number(description.get("generation")));publications.put(tenant,publication);Set<String> old=enabled.put(tenant,Set.copyOf(plugins));
        if(old!=null)for(String p:old)if(!plugins.contains(p)){cancelRequested.add(key(tenant,p));Runner r=runners.get(p);if(r!=null&&sameIdentity(r)&&activeCalls(tenant,p)==0&&totalCalls(p)==0)requireOk(control(r,"drain",Map.of("tenantId",tenant)));}
        for(String p:plugins)cancelRequested.remove(key(tenant,p));
    }
    @Override public long activeCalls(String tenant,String plugin){AtomicInteger count=calls.get(key(tenant,plugin));return count==null?0:count.get();}
    @Override public void cancel(String tenant,String plugin){cancelRequested.add(key(tenant,plugin));Runner r=runners.get(plugin);if(r!=null&&sameIdentity(r)&&totalCalls(plugin)==0)requireOk(control(r,"cancel",Map.of("tenantId",tenant)));}
    @Override public synchronized void stop(String plugin){
        if(enabled.values().stream().anyMatch(s->s.contains(plugin))||totalCalls(plugin)>0)throw new FcpException("RUNNER_IN_USE","Shared runner still in use");
        Runner r=runners.remove(plugin);if(r==null)return;
        boolean acknowledged=false;ProcessHandle owned=null;
        try{if(sameIdentity(r)){
            try{owned=ProcessHandle.of(r.identity.pid()).filter(h->h.info().startInstant().map(r.identity.startedAt()::equals).orElse(false)).orElse(null);}catch(RuntimeException unavailable){/* No termination proof: never refund. */}
            requireOk(control(r,"shutdown",Map.of()));acknowledged=true;
        }}
        finally{r.transport.close();}
        // Dead/unknown runners and host shutdown retain their charge across restarts.
        if(acknowledged){
            boolean terminated=false;try{terminated=owned!=null&&!owned.isAlive();}catch(RuntimeException unavailable){/* Fail closed. */}
            if(!terminated)throw new FcpException("RUNNER_TERMINATION_UNKNOWN","Owned process termination could not be confirmed");
            journal.completeCleanSession(plugin,r.restartAttempt);
        }
    }
    private Object invokeProvider(Engine.Context engineContext,String plugin,String capability,Map<String,Object> input){
        TrustedContext trusted=trusted(engineContext);authorizeDependency(trusted,plugin,capability);authorize(trusted,plugin,capability);if(capability(plugin,capability).sideEffects()&&!Boolean.TRUE.equals(journaled.get()))throw new FcpException("SIDE_EFFECT_CONTEXT_REQUIRED","A write dependency requires a journaled top-level operation");String key=key(trusted.tenantId(),plugin);if(cancelRequested.contains(key)||!enabled.getOrDefault(trusted.tenantId(),Set.of()).contains(plugin))throw new FcpException("ADMISSION_CLOSED","Plugin admission is closed");
        Runner r=runners.get(plugin);if(r==null||!sameIdentity(r))throw new FcpException("RUNNER_UNAVAILABLE","Runner identity is not ready");AtomicInteger count=calls.computeIfAbsent(key,k->new AtomicInteger());synchronized(this){if(cancelRequested.contains(key)||!enabled.getOrDefault(trusted.tenantId(),Set.of()).contains(plugin))throw new FcpException("ADMISSION_CLOSED","Plugin admission changed");count.incrementAndGet();}
        invocationSources.get().push(plugin);
        try{authorize(trusted,plugin,capability);if(cancelRequested.contains(key))throw new FcpException("ADMISSION_CLOSED","Plugin admission changed");Map<String,Object> identity=Json.obj("requestId",trusted.requestId(),"traceId",trusted.traceId(),"tenantId",trusted.tenantId(),"principal",Json.obj("kind",trusted.principal().kind().name(),"id",trusted.principal().id()),"authorizationRevision",trusted.authorizationRevision(),"deadline",trusted.deadline().toString());long remaining=Math.min(capability(plugin,capability).timeoutMillis(),Math.min(java.time.Duration.between(host.clock().instant(),trusted.deadline()).toMillis(),java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(callDeadline.get()-System.nanoTime())));return r.transport.invokeBounded(engineContext,capability,"invoke",Json.obj("capability",capability,"input",input,"context",identity),remaining);}
        finally{invocationSources.get().pop();count.decrementAndGet();if(cancelRequested.contains(key)&&totalCalls(plugin)==0&&sameIdentity(r)){try{requireOk(control(r,"cancel",Map.of("tenantId",trusted.tenantId())));}catch(RuntimeException ignored){/* Cancellation outcome remains unknown; coordinator observes runner state. */}}}
    }
    private void authorizeDependency(TrustedContext context,String target,String capability){
        String source=invocationSources.get().peek();if(source==null)return;
        var sourcePackage=packages.get(source);var targetPackage=packages.get(target);
        if(sourcePackage==null||targetPackage==null||sourcePackage.manifest().dependencies().stream().noneMatch(d->d.pluginId().equals(target)&&PluginManifest.matchesVersion(targetPackage.manifest().version(),d.versionRange())))throw new FcpException("DEPENDENCY_NOT_DECLARED","Caller did not declare this plugin dependency");
        var actual=capability(target,capability);var required=sourcePackage.manifest().requires().stream().filter(r->r.id().equals(capability)&&contract(r).equals(contract(actual))).findFirst().orElseThrow(()->new FcpException("DEPENDENCY_NOT_DECLARED","Caller did not declare this capability contract"));
        host.require(context,new HostCapabilities.Grant(source,required.id(),"invoke","tenant:"+context.tenantId()));
    }
    private TrustedContext trusted(Engine.Context ctx){TrustedContext c=context.get();if(c==null||!scope(c.tenantId()).equals(ctx.scope()))throw new FcpException("TRUSTED_CONTEXT_REQUIRED","Server-authenticated context required");return c;}
    private void authorize(TrustedContext c,String p,String capability){var cap=capability(p,capability);host.require(c,new HostCapabilities.Grant(p,cap.id(),"invoke","tenant:"+c.tenantId()));}
    private PluginManifest.Capability capability(String p,String id){var pkg=packages.get(p);if(pkg==null)throw new FcpException("NOT_INSTALLED",p);return pkg.manifest().provides().stream().filter(c->c.id().equals(id)).findFirst().orElseThrow(()->new FcpException("CAPABILITY_MISSING",id));}
    private PackageInstaller.InstalledPackage resolve(String p,String v,String d){try{return repository.resolve(p,v,d);}catch(IOException e){throw new FcpException("PACKAGE_INTEGRITY","Package verification failed",e);}}
    private Object control(Runner r,String method,Map<String,Object> args){return r.transport.invokeBounded(null,"lifecycle",method,args,5000);}
    private static void requireOk(Object response){if(!Boolean.TRUE.equals(map(response).get("ok")))throw new FcpException("LIFECYCLE_REJECTED","Runner lifecycle rejected");}
    private static boolean sameIdentity(Runner r){return r.identity!=null&&r.identity.equals(r.transport.identity());}
    private long totalCalls(String p){return calls.entrySet().stream().filter(e->e.getKey().endsWith("\n"+p)).mapToLong(e->e.getValue().get()).sum();}
    private void addDocument(List<Map<String,Object>> documents,String kind,Map<String,Object> doc){Map<String,Map<String,Object>> existing=kind.equals("contract")?engine.registry.contracts:engine.registry.modules;String id=str(doc.get("id"));Map<String,Object> old=existing.get(id);if(old!=null){if(!old.equals(doc))throw new FcpException("IMMUTABLE_CATALOG",id);return;}if(documents.stream().noneMatch(d->kind.equals(d.get("kind"))&&id.equals(obj(d,"document").get("id"))))documents.add(Map.of("kind",kind,"document",doc));}
    private static String contract(PluginManifest.Capability c){return "c"+hash(List.of(c.id(),c.version(),canonical(c.inputSchema()),canonical(c.outputSchema()),c.sideEffects())).substring(0,40);}
    private static String module(PackageInstaller.InstalledPackage p){return "m"+hash(List.of(p.manifest().pluginId(),p.sha256())).substring(0,40);}
    public static String export(String plugin,String capability){return "e"+hash(List.of(plugin,capability)).substring(0,40);}
    private static String scope(String tenant){return "t"+hash(tenant).substring(0,40);}
    private static String key(String tenant,String plugin){return tenant+"\n"+plugin;}
    private static String hash(Object o){return VerifiedPackageRepository.hash(Json.write(o).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private static Object canonical(Object o){if(o instanceof Map<?,?> m){Map<String,Object> out=new TreeMap<>();m.forEach((k,v)->out.put((String)k,canonical(v)));return out;}if(o instanceof List<?> l)return l.stream().map(EngineRuntimePort::canonical).toList();return o;}
    private void live(){if(closed)throw new FcpException("CLOSED","Runtime port closed");}
    @Override public synchronized void close()throws IOException{if(closed)return;try{resetAdmissions();}finally{closed=true;try{engine.close();for(Runner r:runners.values())r.transport.close();runners.clear();}finally{journal.close();}}}
}
