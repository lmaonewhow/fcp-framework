import fcp.*;
import fcp.host.*;
import fcp.control.*;
import fcp.plugin.*;
import fcp.runtime.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/** Actual Python and Node subprocesses through existing ProcessProvider and Engine. */
public class RealRuntimeTest {
    static class Host implements HostCapabilities {
        volatile boolean allow=true;volatile String denied="";volatile boolean sourceApproval=true;
        public Descriptor descriptor(){return new Descriptor("dev","1","fcp.host/1","embedded-single-instance",Map.of());}
        public Clock clock(){return Clock.systemUTC();}
        public Decision authorize(TrustedContext c,Grant g){return new Decision((sourceApproval||!g.pluginId().equals("dependent")||!g.capability().equals("echo"))&&!(c.principal().kind()==TrustedContext.Kind.TASK&&g.capability().equals("plugin.lifecycle"))&&allow&&!g.pluginId().equals(denied)&&(g.capability().equals("plugin.lifecycle")||g.operation().equals("invoke")),1);}
        public void audit(Audit event){}
    }
    static TrustedContext ctx(String t){return new TrustedContext(UUID.randomUUID().toString(),"trace",t,new TrustedContext.Principal(TrustedContext.Kind.USER,"server-admin"),1,Set.of("tenant:"+t),Instant.now().plusSeconds(30));}
    static Map<String,Object> cap(String id,boolean write){return Json.obj("id",id,"version","1.0.0","permission",id,"sideEffects",write,"idempotent",write,"timeoutMillis",2000,"inputSchema",Map.of("type","object"),"outputSchema",Map.of("type","object"));}
    static PackageInstaller.InstalledPackage install(VerifiedPackageRepository repo,Path root,String id,String lang,boolean dependency)throws Exception {
        String entry=lang.equals("python")?"runtime.py":"runtime.mjs";Map<String,Object> manifest=Json.obj("manifestVersion",1,"pluginId",id,"version","1.0.0","publisher","test","contractVersion","1.0","runtime",Json.obj("type","local","entry",entry,"argv",List.of()),"provides",List.of(cap("echo",false),cap("write",true)),"dependencies",dependency?List.of(Map.of("pluginId","python","versionRange","1.0.0")):List.of(),"requires",id.equals("hostuser")?List.of(cap("host.storage.get",false),cap("host.storage.put",true)):dependency?List.of(cap("echo",false)):List.of());
        Path archive=root.resolve(id+".zip");try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(archive))){out.putNextEntry(new ZipEntry("plugin.json"));out.write(Json.write(manifest).getBytes(java.nio.charset.StandardCharsets.UTF_8));out.closeEntry();out.putNextEntry(new ZipEntry(entry));out.write(Files.readAllBytes(Path.of("tests/runtime-lifecycle/plugins/"+entry)));out.closeEntry();}
        return repo.install(archive,VerifiedPackageRepository.hash(Files.readAllBytes(archive)),"install-"+id);
    }
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static void rejected(Runnable work,String code){try{work.run();throw new AssertionError("Expected "+code);}catch(FcpException e){check(e.code.equals(code),"Expected "+code+", got "+e.code);}}
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("fcp-real-runtime-");Host host=new Host();var repository=new VerifiedPackageRepository(root.resolve("inventory"),Set.of("test"));
        var python=install(repository,root,"python","python",false);var node=install(repository,root,"node","node",false);var dependent=install(repository,root,"dependent","python",true);
        Map<String,String> runtimes=Map.of("python",args[0],"node",args[1]);String writeKey="durable-write";Object cached;
        try(var runtime=new EngineRuntimePort(host,repository,runtimes,root.resolve("calls"),3);var coordinator=new LifecycleCoordinator(root.resolve("control"),host,runtime)) {
            coordinator.setEnablementPolicy((caller,manifest,digest,closure)->"explicit-fixture-platform-approval");
            for(var pkg:List.of(python,node,dependent))coordinator.register(ctx("t1"),pkg,coordinator.revision());
            for(String p:List.of("python","node")) {
                var plan=coordinator.plan(ctx("t1"),"t1",p,LifecycleCoordinator.Action.ENABLE,coordinator.revision());coordinator.confirm(ctx("t1"),plan,Set.of(p),"enable-"+p);
                Map<String,Object> result=Schema.map(runtime.invoke(ctx("t1"),p,"echo",Map.of("message","hello","tenantId","forged"),null,null));
                check(result.get("tenant").equals("t1"),"server tenant context");check(!Boolean.TRUE.equals(result.get("secretPresent")),"environment whitelist");check(Schema.map(result.get("principal")).get("id").equals("server-admin"),"server principal");System.out.println("PASS real "+p+" handshake/ready/health/invoke pid="+result.get("pid"));
            }
            var plan=coordinator.plan(ctx("t2"),"t2","python",LifecycleCoordinator.Action.ENABLE,coordinator.revision());coordinator.confirm(ctx("t2"),plan,Set.of("python"),"enable");
            long steady=runtime.snapshot(ctx("t2")).generation();coordinator.reconcile(ctx("t2"),"t2");check(runtime.snapshot(ctx("t2")).generation()==steady,"no-op reconcile preserves catalog generation");
            var task=new TrustedContext("task-request","trace","t2",new TrustedContext.Principal(TrustedContext.Kind.TASK,"exchange"),1,Set.of("tenant:t2"),Instant.now().plusSeconds(30));
            rejected(()->runtime.snapshot(task),"PERMISSION_DENIED");var metadata=runtime.enabledVersion(task,"python");check(metadata.version().equals("1.0.0")&&metadata.digest().equals(python.sha256()),"task metadata without lifecycle grant");
            rejected(()->runtime.enabledVersion(ctx("unknown-tenant"),"python"),"ADMISSION_CLOSED");
            var expired=new TrustedContext("expired","trace","t2",task.principal(),1,Set.of("tenant:t2"),Instant.now().minusSeconds(1));rejected(()->runtime.enabledVersion(expired,"python"),"DEADLINE_EXCEEDED");
            long pid=Schema.number(runtime.health("python").get("pid"));check(Schema.number(Schema.map(runtime.invoke(ctx("t2"),"python","echo",Map.of(),null,null)).get("pid"))==pid,"one shared process");
            cached=runtime.invoke(ctx("t1"),"python","write",Map.of("value",1),writeKey,null);check(cached.equals(runtime.invoke(ctx("t1"),"python","write",Map.of("value",1),writeKey,null)),"no duplicate side effect");
            rejected(()->runtime.invoke(ctx("t1"),"python","write",Map.of("value",2),writeKey,null),"IDEMPOTENCY_CONFLICT");
            long old=Schema.number(runtime.describe(ctx("t1")).get("generation"));var depPlan=coordinator.plan(ctx("t1"),"t1","dependent",LifecycleCoordinator.Action.ENABLE,coordinator.revision());coordinator.confirm(ctx("t1"),depPlan,Set.of("python","dependent"),"dep");
            rejected(()->runtime.invoke(ctx("t1"),"python","echo",Map.of(),null,old),"STALE_COMPOSITION");
            check(Schema.map(runtime.invoke(ctx("t1"),"dependent","echo",Map.of("dependency",true),null,null)).get("tenant").equals("t1"),"declared dependency real callback");
            host.sourceApproval=false;
            var sourceRevoked=Schema.map(runtime.invoke(ctx("t1"),"dependent","write",Map.of("dependency",true),"source-revoked",null));check(sourceRevoked.containsKey("callbackError"),"source requires grant revoked without restarting");
            check(Schema.map(runtime.invoke(ctx("t1"),"python","echo",Map.of(),null,null)).get("tenant").equals("t1"),"target permission remains independently allowed");host.sourceApproval=true;
            host.denied="python";rejected(()->runtime.invoke(ctx("t1"),"python","echo",Map.of(),null,null),"PERMISSION_DENIED");var deniedCallback=Schema.map(runtime.invoke(ctx("t1"),"dependent","echo",Map.of("dependency",true),null,null));check(deniedCallback.containsKey("callbackError"),"callback live authorization");host.denied="";
            ExecutorService pool=Executors.newSingleThreadExecutor();Future<?> longCall=pool.submit(()->{try{runtime.invoke(ctx("t1"),"node","echo",Map.of("sleep",0.6),null,null);}catch(FcpException e){check(e.code.equals("STALE_COMPOSITION"),"draining old result fenced");}});
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(runtime.activeCalls("t1","node")==0&&System.nanoTime()<until)Thread.sleep(5);
            var disable=coordinator.plan(ctx("t1"),"t1","node",LifecycleCoordinator.Action.DISABLE,coordinator.revision());var op=coordinator.confirm(ctx("t1"),disable,Set.of("node"),"disable");check(op.phase().equals("DRAINING"),"real in-flight drain");rejected(()->runtime.invoke(ctx("t1"),"node","echo",Map.of(),null,null),"CAPABILITY_MISSING");coordinator.decideDrain(ctx("t1"),op.id(),LifecycleCoordinator.DrainDecision.CANCEL);longCall.get();coordinator.decideDrain(ctx("t1"),op.id(),LifecycleCoordinator.DrainDecision.WAIT);pool.shutdown();
            var disablePython=coordinator.plan(ctx("t1"),"t1","python",LifecycleCoordinator.Action.DISABLE,coordinator.revision());coordinator.confirm(ctx("t1"),disablePython,Set.of("python","dependent"),"disable-python");check(Schema.number(Schema.map(runtime.invoke(ctx("t2"),"python","echo",Map.of(),null,null)).get("pid"))==pid,"T1 disable preserves T2 process");
            rejected(()->runtime.invoke(ctx("t2"),"python","write",Map.of("crash",true),"crash-write",null),"OUTCOME_UNKNOWN");rejected(()->runtime.invoke(ctx("t2"),"python","write",Map.of("crash",true),"crash-write",null),"OUTCOME_UNKNOWN");check(!Boolean.TRUE.equals(runtime.health("python").get("ready")),"crash identity not READY");rejected(()->runtime.enabledVersion(task,"python"),"RUNNER_UNAVAILABLE");check(coordinator.status(ctx("t2"),"t2","python").effective().equals("UNKNOWN"),"coordinator does not report stale READY");
        }
        try(var runtime=new EngineRuntimePort(host,repository,runtimes,root.resolve("calls"),3);var coordinator=new LifecycleCoordinator(root.resolve("control"),host,runtime)) {
            coordinator.setEnablementPolicy((caller,manifest,digest,closure)->"explicit-fixture-platform-approval");
            coordinator.reconcile(ctx("t2"),"t2");rejected(()->runtime.invoke(ctx("t2"),"python","write",Map.of("crash",true),"crash-write",null),"OUTCOME_UNKNOWN");
            runtime.verifyInstalledPackage(python);check(cached.equals(runtime.invoke(ctx("t1"),"python","write",Map.of("value",1),writeKey,null)),"durable result replay without execution");
        }
        check(Schema.number(ProcessProvider.metrics().get("activeProcesses"))==0,"process cleanup");System.out.println("PASS real runtime dynamic catalogs, Python+Node, context, permissions, dependency callback, revision, shared tenant drain, durable unknown outcomes, restart and process cleanup");
    }
}
