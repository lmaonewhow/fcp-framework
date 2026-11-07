package fcp.release.control;

import com.sun.net.httpserver.*;
import fcp.*;
import fcp.control.*;
import fcp.host.TrustedContext;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.io.*;
import java.util.concurrent.atomic.*;
import static fcp.release.control.DeploymentProvider.Action;

public final class ReleaseControllerTest {
    static int assertions;
    static void check(boolean b,String message){assertions++;if(!b)throw new AssertionError(message);}
    static void fails(Throwing task,String contains)throws Exception{assertions++;try{task.run();throw new AssertionError("Expected "+contains);}catch(Exception e){if(!e.toString().contains(contains))throw e;}}
    interface Throwing{void run()throws Exception;}
    static long rev(Map<String,Object> s){return Schema.number(s.get("revision"));}
    static final String D1="a".repeat(64),D2="b".repeat(64);
    static final Instant NOW=Instant.parse("2026-11-07T12:00:00Z");
    static final TrustedContext CTX=new TrustedContext("test-request","test-trace","000000",new TrustedContext.Principal(TrustedContext.Kind.USER,"admin"),1,Set.of(),NOW.plusSeconds(3600));
    static final ReleasePlan PLAN=new ReleasePlan("test","r1","r2",D1,D2,2,1,3,2,4);
    static HttpClient client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    static void reply(HttpExchange x,int status,Object body)throws IOException{byte[] b=Json.write(body).getBytes(java.nio.charset.StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(status,b.length);x.getResponseBody().write(b);x.close();}
    /** Real loopback executor fixture; not a production deployment adapter implementation. */
    static class Executor implements AutoCloseable {
        HttpServer control,stable,candidate,ingress;AtomicInteger stableWrites=new AtomicInteger(),candidateWrites=new AtomicInteger();boolean stopped,lieAboutStopped,failPause;Map<String,Map<String,Object>> receipts=new HashMap<>();AtomicInteger puts=new AtomicInteger();boolean dropReply,wrongDigest,inFlight,unhealthy,recoveryUnknown;int bp;long maxGeneration;Map<String,String> digests=new HashMap<>();
        Executor()throws IOException{stable=version("r1");control=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);control.createContext("/operations/",this::handle);control.start();ingress=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);ingress.createContext("/",x->{
            if(stopped){reply(x,503,Map.of("error","INGRESS_STOPPED"));return;}
            HttpServer target="true".equals(x.getRequestHeaders().getFirst("X-Candidate"))&&bp>0?candidate:stable;
            if(target==null){reply(x,503,Map.of("error","NO_TARGET"));return;}
            try{HttpRequest.Builder req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+target.getAddress().getPort()+"/"));if(x.getRequestMethod().equals("POST"))req.POST(HttpRequest.BodyPublishers.ofString("write"));HttpResponse<String> r=client.send(req.build(),HttpResponse.BodyHandlers.ofString());reply(x,r.statusCode(),Json.parse(r.body()));}catch(Exception e){reply(x,502,Map.of("error","UPSTREAM"));}
        });ingress.start();}
        HttpServer version(String id)throws IOException{HttpServer s=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);s.createContext("/",x->{if(x.getRequestMethod().equals("POST"))(id.equals("r1")?stableWrites:candidateWrites).incrementAndGet();reply(x,200,Map.of("release",id));});s.start();return s;}
        void handle(HttpExchange x)throws IOException{String id=x.getRequestURI().getPath().substring("/operations/".length());
            if(id.endsWith("/recovery")){id=id.substring(0,id.length()-9);String digest=Arrays.stream(x.getRequestURI().getQuery().split("&")).filter(q->q.startsWith("commandDigest=")).findFirst().orElseThrow().substring(14);reply(x,200,Map.of("operationId",id,"commandDigest",digest,"disposition",recoveryUnknown?"UNKNOWN":receipts.containsKey(id)?"ACCEPTED":"NOT_ACCEPTED"));return;}
            if(x.getRequestMethod().equals("PUT")){
            puts.incrementAndGet();Map<String,Object> c=Schema.map(Json.parse(new String(x.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)));String action=Schema.str(c.get("action"));
            String digest=x.getRequestHeaders().getFirst("FCP-Command-Digest");
            if(digests.containsKey(id)){if(!digests.get(id).equals(digest)){reply(x,409,Map.of());return;}reply(x,200,Map.of("operationId",id,"accepted",true));return;}
            long generation=Schema.number(c.get("generation"));if(generation<=maxGeneration){reply(x,409,Map.of());return;}maxGeneration=generation;digests.put(id,digest);
            if(action.equals("DEPLOY")&&candidate==null)candidate=version("r2");bp=Math.toIntExact(Schema.number(c.get("basisPoints")));if(action.equals("ABORT"))stopped=true;else if(action.equals("ROLLBACK")||action.equals("DEPLOY"))stopped=false;
            if(action.equals("RETIRE")&&!inFlight&&bp==0&&candidate!=null){candidate.stop(0);candidate=null;}
            Map<String,Object> observation=new LinkedHashMap<>(Map.of("operationId",id,"complete",true,"healthy",!unhealthy,"candidateRelease","r2","candidateDigest",D2,"basisPoints",bp,"generation",c.get("generation"),"inFlight",inFlight?1:0,"detail","observed loopback fixture"));observation.put("outcome",action.equals("PAUSE")&&failPause?"FAILED":"SUCCEEDED");observation.put("routingMode",stopped&&!lieAboutStopped?"STOPPED":"ACTIVE");receipts.put(id,observation);
            if(dropReply){dropReply=false;x.close();return;}reply(x,202,Map.of("operationId",id,"accepted",true));
        }else{Map<String,Object> receipt=receipts.get(id);if(receipt==null){reply(x,404,Map.of());return;}Map<String,Object> out=new LinkedHashMap<>(receipt);if(wrongDigest)out.put("candidateDigest",D1);out.put("inFlight",inFlight?1:0);reply(x,200,out);}}
        String actual(boolean candidateTenant)throws Exception{return actual(candidateTenant,false);}
        String actual(boolean candidateTenant,boolean write)throws Exception{HttpRequest.Builder req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+ingress.getAddress().getPort()+"/")).header("X-Candidate",Boolean.toString(candidateTenant));if(write)req.POST(HttpRequest.BodyPublishers.ofString("write"));HttpResponse<String> r=client.send(req.build(),HttpResponse.BodyHandlers.ofString());return r.statusCode()+" "+r.body();}
        HttpDeploymentProvider provider(){return new HttpDeploymentProvider(URI.create("http://127.0.0.1:"+control.getAddress().getPort()+"/"),client,Duration.ofSeconds(2),Set.of(Action.values()),true,true,true);}
        public void close(){ingress.stop(0);control.stop(0);stable.stop(0);if(candidate!=null)candidate.stop(0);}
    }
    static ReleaseController controller(StateJournal journal,DeploymentProvider p){return new ReleaseController(journal,p,(c,permission)->{if(!c.principal().id().equals("admin"))throw new SecurityException("DENIED");},plan->plan.requireCompatibleSchema(2,false),Clock.fixed(NOW,ZoneOffset.UTC));}
    public static void main(String[]args)throws Exception{
        Path dir=Files.createTempDirectory("fcp-release-control-test-");
        try(Executor executor=new Executor()){
            try(FileStateJournal journal=new FileStateJournal(dir)){
                ReleaseController c=controller(journal,executor.provider());Map<String,Object>s=c.snapshot(CTX);
                s=c.createPlan(CTX,rev(s),"plan1",PLAN);check(s.get("phase").equals("PLANNED"),"planned");
                check(rev(c.createPlan(CTX,0,"plan1",PLAN))==rev(s),"durable replay");
                fails(()->c.execute(CTX,0,"stale",Action.DEPLOY,0),"REVISION_CONFLICT");
                fails(()->c.execute(CTX,rev(c.snapshot(CTX)),"bad",Action.PROMOTE,10000),"INVALID_RELEASE_TRANSITION");
                s=c.execute(CTX,rev(s),"deploy",Action.DEPLOY,0);check(s.get("phase").equals("READY"),"actual candidate ready");
                check(executor.candidate!=null,"real candidate HTTP listener running");check(executor.actual(true).contains("r1"),"zero candidate traffic");
                s=c.execute(CTX,rev(s),"canary",Action.CANARY,1000);check(executor.actual(true).contains("r2"),"candidate actual response");check(executor.actual(false).contains("r1"),"stable actual response");
                s=c.advance(CTX,rev(s),"missing-metrics",2000,null,new MetricsGate.Limits(10,Duration.ofMinutes(1),.1,100));check(s.get("phase").equals("PAUSED"),"missing metrics pauses");check(Schema.number(s.get("basisPoints"))==1000,"pause does not rollback traffic");
                executor.dropReply=true;long before=rev(s);fails(()->c.execute(CTX,before,"expand-unknown",Action.EXPAND,2000),"OUTCOME_UNKNOWN");
                check(c.snapshot(CTX).containsKey("pending"),"unknown retained durable");
            }
            try(FileStateJournal journal=new FileStateJournal(dir)){
                ReleaseController c=controller(journal,executor.provider());int puts=executor.puts.get();Map<String,Object>s=c.reconcile(CTX);check(s.get("phase").equals("CANARY"),"restart reconcile actual");check(executor.puts.get()==puts,"no retry uncertain effect");check(Schema.number(s.get("basisPoints"))==2000,"observed new route");
                executor.wrongDigest=true;long mismatchRevision=rev(s);fails(()->c.execute(CTX,mismatchRevision,"full",Action.PROMOTE,10000),"IDENTITY_MISMATCH");
                check(c.snapshot(CTX).containsKey("pending"),"bad observation cannot complete");executor.wrongDigest=false;s=c.reconcile(CTX);check(s.get("phase").equals("FULL"),"promoted observed");
                s=c.execute(CTX,rev(s),"rollback",Action.ROLLBACK,0);check(executor.actual(true).contains("r1"),"rollback actual route");
                executor.inFlight=true;s=c.execute(CTX,rev(s),"retire",Action.RETIRE,0);check(s.containsKey("pending"),"retire waits drain");executor.inFlight=false;s=c.reconcile(CTX);check(s.get("phase").equals("RETIRED"),"drained retire confirmed");
                check(Schema.list(s.get("audit")).size()>10,"audit persisted");
                TrustedContext other=new TrustedContext("r","t","000001",new TrustedContext.Principal(TrustedContext.Kind.USER,"reader"),1,Set.of(),NOW.plusSeconds(30));fails(()->c.snapshot(other),"DENIED");
            }
        }
        PLAN.requireCompatibleSchema(2,false);fails(()->PLAN.requireCompatibleSchema(2,true),"DIRTY");fails(()->PLAN.requireCompatibleSchema(4,false),"INCOMPATIBLE");
        MetricsGate.Limits limits=new MetricsGate.Limits(10,Duration.ofMinutes(1),.1,100);
        check(MetricsGate.evaluate(new MetricsGate.Sample(NOW,"r2",1,100,0,100,0,20,false),limits,NOW,"r2",1)==MetricsGate.Decision.ABORT,"business rejection counts");
        check(MetricsGate.evaluate(new MetricsGate.Sample(NOW,"r2",1,100,0,0,0,Double.NaN,false),limits,NOW,"r2",1)==MetricsGate.Decision.PAUSE,"NaN pause");
        check(MetricsGate.evaluate(new MetricsGate.Sample(NOW.minusSeconds(61),"r2",1,100,0,0,0,20,false),limits,NOW,"r2",1)==MetricsGate.Decision.PAUSE,"stale pause");
        check(MetricsGate.evaluate(new MetricsGate.Sample(NOW,"r2",1,100,0,0,0,20,false),limits,NOW,"r2",1)==MetricsGate.Decision.PROCEED,"valid metrics");
        Path recoveryDir=Files.createTempDirectory("fcp-release-recovery-test-");
        try(Executor executor=new Executor()){
            DeploymentProvider real=executor.provider();
            DeploymentProvider crashBeforeSubmit=new DeploymentProvider(){
                public Set<Action> capabilities(){return real.capabilities();}
                public void submit(Command c){throw new AssertionError("INJECTED_PROCESS_CRASH_AFTER_INTENT");}
                public Optional<Observation> observe(String env,String op)throws IOException{return real.observe(env,op);}
            };
            try(FileStateJournal journal=new FileStateJournal(recoveryDir)){
                ReleaseController c=controller(journal,crashBeforeSubmit);Map<String,Object> s=c.createPlan(CTX,0,"crash-plan",PLAN);
                try{c.execute(CTX,rev(s),"crash-deploy",Action.DEPLOY,0);throw new AssertionError("No crash");}catch(AssertionError e){check(e.getMessage().contains("INJECTED"),"crash injection after durable intent");}
                check(executor.puts.get()==0,"crash occurred before any submit");
            }
            try(FileStateJournal journal=new FileStateJournal(recoveryDir)){
                ReleaseController c=controller(journal,real);Map<String,Object> s=c.reconcile(CTX);check(s.containsKey("pending"),"ordinary HTTP 404 remains unknown");
                executor.recoveryUnknown=true;s=c.recover(CTX,rev(s));check(s.containsKey("pending")&&executor.puts.get()==0,"UNKNOWN recovery never resubmits");
                executor.recoveryUnknown=false;s=c.recover(CTX,rev(s));check(s.get("phase").equals("READY"),"authoritative absence recovers to ready");check(executor.puts.get()==1,"same operation submitted once");
                check(Schema.map(s.get("operations")).containsKey("crash-deploy"),"original operation id retained");
                executor.unhealthy=true;s=c.execute(CTX,rev(s),"failed-canary",Action.CANARY,1000);check(s.get("phase").equals("FAILED")&&!s.containsKey("pending"),"definite unhealthy completion not stuck pending");
                ReleaseController dirty=new ReleaseController(journal,real,(x,p)->{},p->{throw new IOException("DATABASE_DIRTY");},Clock.fixed(NOW,ZoneOffset.UTC));
                s=dirty.execute(CTX,rev(s),"contain-failure",Action.ABORT,0);check(s.get("phase").equals("ABORTED")&&executor.bp==0,"abort works despite unhealthy candidate and dirty DB");
            }
        }
        Path outcomeDir=Files.createTempDirectory("fcp-release-outcome-test-");
        try(Executor executor=new Executor();FileStateJournal journal=new FileStateJournal(outcomeDir)){
            ReleaseController c=controller(journal,executor.provider());Map<String,Object>s=c.createPlan(CTX,0,"outcome-plan",PLAN);s=c.execute(CTX,rev(s),"outcome-deploy",Action.DEPLOY,0);s=c.execute(CTX,rev(s),"outcome-canary",Action.CANARY,1000);
            executor.failPause=true;s=c.execute(CTX,rev(s),"failed-pause",Action.PAUSE,1000);
            check(s.get("phase").equals("FAILED")&&!s.containsKey("pending"),"typed PAUSE failure is terminal FAILED even unchanged route and healthy service");
            check(Schema.map(Schema.map(s.get("operations")).get("failed-pause")).get("status").equals("FAILED"),"typed failed receipt retained");
            s=c.execute(CTX,rev(s),"abort-after-pause-failed",Action.ABORT,0);check(s.get("phase").equals("ABORTED")&&executor.actual(true,true).startsWith("503"),"failed PAUSE can safely ABORT actual ingress");
        }
        Path proofDir=Files.createTempDirectory("fcp-release-proof-test-");
        try(Executor executor=new Executor();FileStateJournal journal=new FileStateJournal(proofDir)){
            ReleaseController c=controller(journal,executor.provider());Map<String,Object>s=c.createPlan(CTX,0,"proof-plan",PLAN);s=c.execute(CTX,rev(s),"proof-deploy",Action.DEPLOY,0);
            executor.lieAboutStopped=true;s=c.execute(CTX,rev(s),"missing-stop-proof",Action.ABORT,0);
            check(s.get("phase").equals("FAILED"),"zero percentage without STOPPED proof cannot complete ABORT");
        }
        Path fullDir=Files.createTempDirectory("fcp-release-full-stop-test-");
        try(Executor executor=new Executor();FileStateJournal journal=new FileStateJournal(fullDir)){
            DeploymentProvider provider=executor.provider();ReleaseController c=controller(journal,provider);Map<String,Object>s=c.createPlan(CTX,0,"full-plan",PLAN);
            s=c.execute(CTX,rev(s),"full-deploy",Action.DEPLOY,0);s=c.execute(CTX,rev(s),"full-canary",Action.CANARY,1000);s=c.execute(CTX,rev(s),"full-promote",Action.PROMOTE,10000);
            check(executor.actual(true,true).contains("200"),"full candidate write served before containment");int writes=executor.stableWrites.get();
            ReleaseController dirty=new ReleaseController(journal,provider,(x,p)->{},p->{throw new IOException("DATABASE_DIRTY_AND_REGISTRY_UNAVAILABLE");},Clock.fixed(NOW,ZoneOffset.UTC));
            long fullRevision=rev(s);fails(()->dirty.execute(CTX,fullRevision,"unsafe-rollback",Action.ROLLBACK,0),"DATABASE_DIRTY");
            s=dirty.advance(CTX,rev(s),"security-stop",10000,new MetricsGate.Sample(NOW.minusSeconds(600),"r2",Schema.number(s.get("generation")),100,0,0,0,10,true),limits);
            check(s.get("phase").equals("ABORTED"),"FULL security stop bypasses unavailable compatibility dependencies");
            check(executor.actual(true,true).startsWith("503")&&executor.actual(false,true).startsWith("503"),"all governed ingress rejected after ABORT");
            check(executor.stableWrites.get()==writes,"ABORT never sends candidate write to stable");
            check(Schema.map(s.get("observed")).get("routingMode").equals("STOPPED"),"observed stopped proof");
            long abortedRevision=rev(s);fails(()->dirty.execute(CTX,abortedRevision,"resume-old",Action.ROLLBACK,0),"DATABASE_DIRTY");
            s=c.execute(CTX,rev(s),"compatible-resume",Action.ROLLBACK,0);check(executor.actual(true,true).contains("r1"),"explicit compatible rollback resumes stable");
        }
        Path fenceDir=Files.createTempDirectory("fcp-release-fence-test-");
        try(Executor executor=new Executor();FileStateJournal journal=new FileStateJournal(fenceDir)){
            DeploymentProvider real=executor.provider();
            DeploymentProvider crash=new DeploymentProvider(){public Set<Action> capabilities(){return real.capabilities();}public void submit(Command c){throw new AssertionError("CRASH");}public Optional<Observation> observe(String e,String o)throws IOException{return real.observe(e,o);}};
            ReleaseController c=controller(journal,crash);Map<String,Object>s=c.createPlan(CTX,0,"fence-plan",PLAN);
            try{c.execute(CTX,rev(s),"late-deploy",Action.DEPLOY,0);}catch(AssertionError expected){}
            long pendingRevision=rev(c.snapshot(CTX));fails(()->c.execute(CTX,pendingRevision,"unsafe-abort",Action.ABORT,0),"OPERATION_PENDING");
            fails(()->c.recover(CTX,pendingRevision),"RECOVERY_UNSUPPORTED");
            ReleaseController safe=new ReleaseController(journal,real,(x,p)->{},p->{throw new IOException("REGISTRY_UNAVAILABLE");},Clock.fixed(NOW,ZoneOffset.UTC));
            s=safe.execute(CTX,pendingRevision,"fenced-abort",Action.ABORT,0);check(s.get("phase").equals("ABORTED"),"fenced abort supersedes unknown pending despite registry outage");
            check(Schema.number(s.get("generation"))==2,"abort generation above pending generation");
            check(Schema.map(Schema.map(s.get("operations")).get("late-deploy")).get("status").equals("SUPERSEDED_BY_FENCED_ABORT"),"superseded operation retained");
            fails(()->real.submit(new DeploymentProvider.Command("late-deploy",PLAN,Action.DEPLOY,0,1)),"HTTP 409");
            check(executor.candidate==null,"late old deploy fenced before starting candidate");
        }
        check(MetricsGate.evaluate(new MetricsGate.Sample(NOW.minusSeconds(1000),"r2",1,100,0,0,0,20,true),limits,NOW,"r2",1)==MetricsGate.Decision.ABORT,"verified security signal wins stale sample");
        Path concurrent=Files.createTempDirectory("fcp-release-cas-test-");
        try(FileStateJournal journal=new FileStateJournal(concurrent);Executor executor=new Executor()){
            java.util.concurrent.CyclicBarrier barrier=new java.util.concurrent.CyclicBarrier(2);
            ReleaseController.Preflight verify=p->{try{barrier.await(5,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception e){throw new IOException(e);}};
            ReleaseController a=new ReleaseController(journal,executor.provider(),(c,p)->{},verify,Clock.fixed(NOW,ZoneOffset.UTC));
            ReleaseController b=new ReleaseController(journal,executor.provider(),(c,p)->{},verify,Clock.fixed(NOW,ZoneOffset.UTC));
            java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);
            try{java.util.concurrent.Future<Boolean> fa=pool.submit(()->{try{a.createPlan(CTX,0,"concurrent-a",PLAN);return true;}catch(IOException e){return false;}});
                java.util.concurrent.Future<Boolean> fb=pool.submit(()->{try{b.createPlan(CTX,0,"concurrent-b",PLAN);return true;}catch(IOException e){return false;}});
                check(fa.get()!=fb.get(),"exactly one concurrent CAS wins");check(journal.load().revision()==1,"one durable state");
            }finally{pool.shutdownNow();}
        }
        check(MetricsGate.evaluate(new MetricsGate.Sample(NOW,"r2",1,1,0,0,0,20,false),limits,NOW,"r2",1)==MetricsGate.Decision.PAUSE,"low sample pause");
        check(MetricsGate.evaluate(new MetricsGate.Sample(NOW,"r2",1,100,0,0,1,20,false),limits,NOW,"r2",1)==MetricsGate.Decision.PAUSE,"unknown writes pause");
        check(MetricsGate.evaluate(new MetricsGate.Sample(NOW,"r2",2,100,0,0,0,20,false),limits,NOW,"r2",1)==MetricsGate.Decision.PAUSE,"wrong generation pause");
        check(MetricsGate.evaluate(new MetricsGate.Sample(NOW,"r2",1,100,0,0,0,20,true),limits,NOW,"r2",1)==MetricsGate.Decision.ABORT,"security abort");
        fails(()->new HttpDeploymentProvider(URI.create("http://example.com/"),client,Duration.ofSeconds(1),Set.of(Action.DEPLOY),true),"Unsafe");
        fails(()->new HttpDeploymentProvider(URI.create("https://example.com/"),HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build(),Duration.ofSeconds(1),Set.of(Action.DEPLOY),false),"Unsafe");
        HttpServer slow=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        slow.createContext("/operations/",x->{try{x.sendResponseHeaders(200,1000);x.getResponseBody().write('{');x.getResponseBody().flush();Thread.sleep(1500);}catch(Exception ignored){}finally{x.close();}});slow.start();
        try{HttpDeploymentProvider slowProvider=new HttpDeploymentProvider(URI.create("http://127.0.0.1:"+slow.getAddress().getPort()+"/"),client,Duration.ofMillis(150),Set.of(Action.DEPLOY),true);
            long started=System.nanoTime();fails(()->slowProvider.observe("test","slow"),"timeout");check((System.nanoTime()-started)/1_000_000<1200,"whole response deadline covers stalled stream after headers");
        }finally{slow.stop(0);}
        HttpServer large=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);large.createContext("/operations/",x->{try{byte[] b=new byte[65537];x.sendResponseHeaders(200,b.length);x.getResponseBody().write(b);}catch(IOException ignored){}finally{x.close();}});large.start();
        try{HttpDeploymentProvider bounded=new HttpDeploymentProvider(URI.create("http://127.0.0.1:"+large.getAddress().getPort()+"/"),client,Duration.ofSeconds(2),Set.of(Action.DEPLOY),true);fails(()->bounded.observe("test","large"),"Executor I/O");}finally{large.stop(0);}
        System.out.println("PASS "+assertions+" controller assertions; actual loopback executor/two HTTP services and file recovery. Native RuoYi/MySQL/browser NOT_RUN.");
    }
}
