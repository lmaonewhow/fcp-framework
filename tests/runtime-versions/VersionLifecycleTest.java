import fcp.*;
import fcp.control.*;
import fcp.host.*;
import fcp.plugin.*;
import fcp.runtime.*;
import dev.fcp.host.PluginVersionApplication;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual retained ZIPs, file CAS journal, Engine and Python processes; host authorization is a fixture. */
public final class VersionLifecycleTest {
    static class Host implements HostCapabilities {
        boolean deny;
        public Descriptor descriptor(){return new Descriptor("version-test","1","fcp.host/1","embedded-single-instance",Map.of());}
        public Clock clock(){return Clock.systemUTC();}
        public Decision authorize(TrustedContext c,Grant grant){return new Decision(!deny,1);}
        public void audit(Audit event){}
    }
    static TrustedContext context(String tenant){return new TrustedContext(UUID.randomUUID().toString(),"trace",tenant,new TrustedContext.Principal(TrustedContext.Kind.USER,"operator"),1,Set.of("tenant:"+tenant),Instant.now().plusSeconds(60));}
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static void rejected(String code,Runnable action){try{action.run();throw new AssertionError("Expected "+code);}catch(FcpException e){check(code.equals(e.code),"Expected "+code+" got "+e.code);}}
    static Map<String,Object> plan(PluginVersionApplication app,TrustedContext c,String action,String version,String digest,long revision){try{return app.plan(c,"ci.python",action,version,digest,revision);}catch(java.io.IOException e){throw new RuntimeException(e);}}
    static Map<String,Object> confirm(PluginVersionApplication app,TrustedContext c,Map<String,Object> plan,String key){try{return app.confirm(c,(String)plan.get("planId"),(String)plan.get("digest"),key,true);}catch(java.io.IOException e){throw new RuntimeException(e);}}
    static void set(LifecycleCoordinator coordinator,TrustedContext c,String plugin,LifecycleCoordinator.Action action){var plan=coordinator.plan(c,c.tenantId(),plugin,action,coordinator.revision());coordinator.confirm(c,plan,new HashSet<>(plan.closure()),UUID.randomUUID().toString());}
    public static void main(String[] args)throws Exception {
        Path inputs=Path.of(args[0]),root=Files.createTempDirectory("version-lifecycle-");var repo=new VerifiedPackageRepository(root.resolve("packages"),Set.of("test"));Host host=new Host();var c=context("t1");var c2=context("t2");
        Map<String,PackageInstaller.InstalledPackage> packages=new HashMap<>();for(String name:List.of("ci.python","ci.node","ci.python-1.1.0","ci.python-1.2.0")){Path archive=inputs.resolve(name+".zip");packages.put(name,repo.stageOnly(archive,VerifiedPackageRepository.hash(Files.readAllBytes(archive)),"stage-"+name));}
        var one=packages.get("ci.python");var two=packages.get("ci.python-1.1.0");var bad=packages.get("ci.python-1.2.0");String receiptId;String winningDigest;String winningKey;
        try(var runtime=new EngineRuntimePort(host,repo,Map.of("python",args[1],"node",args[2]),root.resolve("calls"),10);var coordinator=new LifecycleCoordinator(root.resolve("control"),host,runtime)){
            coordinator.setEnablementPolicy((identity,manifest,digest,closure)->"explicit-version-test-grants");coordinator.register(c,one,0);coordinator.register(c,packages.get("ci.node"),1);
            var app=new PluginVersionApplication(host,repo,coordinator,root.resolve("uploads"));check(((List<?>)app.versions(c,"ci.python").get("versions")).size()==3,"retained versions inventory");check(coordinator.installedMetadata(c,"ci.python").get("version").equals("1.0.0"),"staging never switches active version");
            set(coordinator,c,"ci.node",LifecycleCoordinator.Action.ENABLE);set(coordinator,c2,"ci.python",LifecycleCoordinator.Action.ENABLE);
            rejected("PLUGIN_IN_USE",()->plan(app,c,"UPGRADE","1.1.0",two.sha256(),coordinator.revision()));set(coordinator,c,"ci.python",LifecycleCoordinator.Action.DISABLE);
            rejected("PLUGIN_IN_USE",()->plan(app,c,"UPGRADE","1.1.0",two.sha256(),coordinator.revision()));set(coordinator,c2,"ci.python",LifecycleCoordinator.Action.DISABLE);
            var first=plan(app,c,"UPGRADE","1.1.0",two.sha256(),coordinator.revision());var second=plan(app,c,"UPGRADE","1.1.0",two.sha256(),coordinator.revision());check(new HashSet<>((List<?>)first.get("affectedTenants")).equals(Set.of("t1","t2")),"plan lists all prior tenant users");check(first.get("dependentPlugins").equals(List.of("ci.node")),"dependent impact visible");
            host.deny=true;rejected("PERMISSION_DENIED",()->confirm(app,c,first,"denied"));host.deny=false;
            ExecutorService pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);List<Future<Map<String,Object>>> results=new ArrayList<>();List<Map<String,Object>> plans=List.of(first,second);
            for(int i=0;i<2;i++){int index=i;results.add(pool.submit(()->{start.await();try{return confirm(app,c,plans.get(index),"race-"+index);}catch(FcpException e){return Map.of("error",e.code);}}));}start.countDown();int successes=0;Map<String,Object> winner=null;int winnerIndex=-1;for(int i=0;i<results.size();i++){var result=results.get(i).get();if(result.containsKey("error"))check(result.get("error").equals("REVISION_CONFLICT"),"losing concurrent confirmation CAS");else{successes++;winner=result;winnerIndex=i;}}pool.shutdown();check(successes==1,"one version CAS winner");receiptId=(String)winner.get("operationId");winningDigest=(String)plans.get(winnerIndex).get("digest");winningKey="race-"+winnerIndex;
            check(coordinator.installedMetadata(c,"ci.python").get("version").equals("1.1.0"),"upgrade current version");check(coordinator.status(c,"t1","ci.python").desired().equals("DISABLED")&&coordinator.status(c2,"t2","ci.python").desired().equals("DISABLED"),"all tenant desired states remain disabled");
            rejected("STORAGE_ADAPTER_REQUIRED",()->plan(app,c,"UPGRADE","1.2.0",bad.sha256(),coordinator.revision()));check(coordinator.installedMetadata(c,"ci.python").get("digest").equals(two.sha256()),"incompatible target leaves current unchanged");
            try{coordinator.register(c,one,coordinator.revision());throw new AssertionError("legacy register silently rolled back");}catch(FcpException e){check(e.code.equals("VERSION_PLAN_REQUIRED"),"explicit version plan required");}
            Thread.sleep(260);set(coordinator,c,"ci.python",LifecycleCoordinator.Action.ENABLE);var result=Schema.map(runtime.invoke(c,"ci.python","echo",Map.of("message","version-two"),null,null));check(result.get("packageVersion").equals("1.1.0"),"actual upgraded Python process executes new package");set(coordinator,c,"ci.python",LifecycleCoordinator.Action.DISABLE);
            var rollback=plan(app,c,"ROLLBACK","1.0.0",one.sha256(),coordinator.revision());check(confirm(app,c,rollback,"rollback").get("outcome").equals("APPLIED"),"explicit rollback applies");Thread.sleep(260);set(coordinator,c,"ci.python",LifecycleCoordinator.Action.ENABLE);check(Schema.map(runtime.invoke(c,"ci.python","echo",Map.of(),null,null)).get("packageVersion").equals("1.0.0"),"actual rollback Python process executes retained package");set(coordinator,c,"ci.python",LifecycleCoordinator.Action.DISABLE);
        }
        try(var runtime=new EngineRuntimePort(host,repo,Map.of("python",args[1],"node",args[2]),root.resolve("calls"),10);var coordinator=new LifecycleCoordinator(root.resolve("control"),host,runtime)){
            var app=new PluginVersionApplication(host,repo,coordinator,root.resolve("uploads"));var replay=app.confirm(c,"lost-http-plan-after-restart",winningDigest,winningKey,true);check(replay.get("operationId").equals(receiptId)&&replay.get("outcome").equals("APPLIED"),"durable version receipt survives loss of in-memory HTTP plan");check(coordinator.installedMetadata(c,"ci.python").get("version").equals("1.0.0"),"old upgrade receipt replay never reapplies over later rollback");
        }
        check(Schema.number(ProcessProvider.metrics().get("activeProcesses"))==0,"no process leak");System.out.println("PASS actual version staging/inventory, two-tenant drain fence, explicit UPGRADE/ROLLBACK, CAS race, incompatible target refusal, real Python versions, durable replay");
    }
}
