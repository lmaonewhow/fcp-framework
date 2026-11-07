import fcp.*;
import fcp.control.*;
import fcp.runtime.*;
import java.nio.file.*;
import java.util.*;

/** Explicit approval-policy and runtime fixtures; journal and installer are actual implementations. */
public class EnablementPolicyTest {
    static class Runtime extends LifecycleTest.FixtureRuntime {
        Runnable beforePrepare=()->{};
        @Override public void prepare(String p,String version,String digest){beforePrepare.run();}
    }
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("enablement-policy-");var repository=new VerifiedPackageRepository(root.resolve("inventory"),Set.of("test"));var dep=RealRuntimeTest.install(repository,root,"python","python",false);var pkg=RealRuntimeTest.install(repository,root,"dependent","python",true);
        var host=new LifecycleTest.Host();var runtime=new Runtime();var context=LifecycleTest.context("t1");boolean[] allowed={true};long[] grantsRevision={1};
        try(var coordinator=new LifecycleCoordinator(root.resolve("state"),host,runtime)) {
            coordinator.register(context,dep,0);coordinator.register(context,pkg,1);
            RealRuntimeTest.rejected(()->coordinator.plan(context,"t1","dependent",LifecycleCoordinator.Action.ENABLE,coordinator.revision()),"CAPABILITY_NOT_GRANTED");
            coordinator.setEnablementPolicy((caller,manifest,digest,closure)->{if(!allowed[0])throw new FcpException("CAPABILITY_NOT_GRANTED","Explicit fixture grant revoked");return "fixture-approval-revision-"+grantsRevision[0];});
            var first=coordinator.plan(context,"t1","dependent",LifecycleCoordinator.Action.ENABLE,coordinator.revision());allowed[0]=false;
            RealRuntimeTest.rejected(()->coordinator.confirm(context,first,Set.of("python","dependent"),"denied"),"CAPABILITY_NOT_GRANTED");RealRuntimeTest.check(runtime.catalogs.isEmpty(),"revocation before confirm has no publication");allowed[0]=true;grantsRevision[0]++;
            RealRuntimeTest.rejected(()->coordinator.confirm(context,first,Set.of("python","dependent"),"changed"),"CONFIRMATION_REQUIRED");
            var current=coordinator.plan(context,"t1","dependent",LifecycleCoordinator.Action.ENABLE,coordinator.revision());runtime.beforePrepare=()->grantsRevision[0]++;
            RealRuntimeTest.rejected(()->coordinator.confirm(context,current,Set.of("python","dependent"),"prepare-race"),"GRANT_REVISION_CONFLICT");RealRuntimeTest.check(runtime.catalogs.get("t1").isEmpty(),"grant change during prepare never publishes closure");runtime.beforePrepare=()->{};allowed[0]=false;
            RealRuntimeTest.rejected(()->coordinator.reconcile(context,"t1"),"CAPABILITY_NOT_GRANTED");RealRuntimeTest.check(runtime.catalogs.get("t1").isEmpty(),"recovery cannot bypass revoked approval");allowed[0]=true;coordinator.reconcile(context,"t1");RealRuntimeTest.check(runtime.catalogs.get("t1").equals(Set.of("python","dependent")),"explicit restored platform approval can reconcile");allowed[0]=false;RealRuntimeTest.rejected(()->coordinator.reconcile(context,"t1"),"CAPABILITY_NOT_GRANTED");RealRuntimeTest.check(runtime.catalogs.get("t1").isEmpty(),"revoked live approval removes existing admissions");
        }
        System.out.println("PASS enablement policy: fail-closed default, explicit platform fixture, plan fingerprint, confirm revocation, prepublication race, recovery recheck");
    }
}
