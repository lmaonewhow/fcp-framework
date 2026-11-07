import fcp.*;
import fcp.host.*;
import fcp.plugin.*;
import fcp.runtime.*;
import fcp.control.*;
import java.nio.file.*;
import java.util.*;

/** Real Python callback transport; storage handler here is explicitly a fixture, not MySQL evidence. */
public class HostPortsRuntimeTest {
    static class ScopedHost extends RealRuntimeTest.Host {
        String lastScope;
        @Override public Decision authorize(TrustedContext c,Grant g){lastScope=g.dataScope();return new Decision(!g.dataScope().equals("class:denied")&&(g.capability().equals("plugin.lifecycle")||g.operation().equals("invoke")),1);}
    }
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("host-ports-runtime-");ScopedHost host=new ScopedHost();var repository=new VerifiedPackageRepository(root.resolve("inventory"),Set.of("test"));var pkg=RealRuntimeTest.install(repository,root,"hostuser","python",false);
        try(var runtime=new EngineRuntimePort(host,repository,Map.of("python",args[0]),root.resolve("calls"),1);var coordinator=new LifecycleCoordinator(root.resolve("control"),host,runtime)) {
            coordinator.setEnablementPolicy((caller,manifest,digest,closure)->"explicit-fixture-platform-approval");
            for(var contract:pkg.manifest().requires())runtime.registerHostCapability(contract,(source,c,cap,input)->Json.obj("source",source,"tenant",c.tenantId(),"scope",input.get("dataScope")));
            var c=RealRuntimeTest.ctx("tenant-a");coordinator.register(c,pkg,0);coordinator.confirm(c,coordinator.plan(c,c.tenantId(),"hostuser",LifecycleCoordinator.Action.ENABLE,coordinator.revision()),Set.of("hostuser"),"enable");
            var payload=Map.of("dataScope","class:room-a","pluginId","forged","tenantId","forged");var read=Schema.map(runtime.invoke(c,"hostuser","echo",Map.of("dependency","host.storage.get","payload",payload),null,null));
            RealRuntimeTest.check(read.get("source").equals("hostuser")&&read.get("tenant").equals("tenant-a")&&read.get("scope").equals("class:room-a"),"server-bound callback identity and class scope");
            var denied=Schema.map(runtime.invoke(c,"hostuser","echo",Map.of("dependency","host.storage.get","payload",Map.of("dataScope","class:denied")),null,null));RealRuntimeTest.check(denied.containsKey("callbackError"),"host scope revocation");
            var mismatch=Schema.map(runtime.invoke(c,"hostuser","echo",Map.of("dependency","host.storage.get","payload",Map.of("dataScope","tenant:other")),null,null));RealRuntimeTest.check(mismatch.containsKey("callbackError"),"cross-tenant scope rejected");
            var unsafe=Schema.map(runtime.invoke(c,"hostuser","echo",Map.of("dependency","host.storage.put","payload",payload),null,null));RealRuntimeTest.check(unsafe.containsKey("callbackError"),"read cannot hide host write");
            var write=Schema.map(runtime.invoke(c,"hostuser","write",Map.of("dependency","host.storage.put","payload",payload),"host-write",null));RealRuntimeTest.check(write.get("source").equals("hostuser"),"journaled host write allowed");
            RealRuntimeTest.rejected(()->runtime.invoke(c,"hostuser","write",Map.of("crash",true),"crash",null),"OUTCOME_UNKNOWN");
        }
        try(var runtime=new EngineRuntimePort(host,repository,Map.of("python",args[0]),root.resolve("calls"),1)) {
            RealRuntimeTest.rejected(()->runtime.prepare("hostuser","1.0.0",pkg.sha256()),"RESTART_BUDGET");
        }
        RealRuntimeTest.check(Schema.number(ProcessProvider.metrics().get("activeProcesses"))==0,"callback process cleanup");System.out.println("PASS real Python host callbacks: server-bound identity, class scope, live denial, tenant fence, write journal requirement, durable restart budget");
    }
}
