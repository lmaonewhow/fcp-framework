import fcp.*;
import fcp.runtime.*;
import java.nio.file.*;
import java.util.*;

/** Simulates stale/reused PID metadata; never forces OS PID reuse or targets a non-fixture process. */
public final class ProcessIdentityReuseTest {
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("runtime-pid-reuse-");
        var host=new RealRuntimeTest.Host();var repository=new VerifiedPackageRepository(root.resolve("inventory"),Set.of("test"));
        var pkg=RealRuntimeTest.install(repository,root,"identity","python",false);
        Process unrelated=new ProcessBuilder(args[0],"-c","import time; time.sleep(60)").start();
        try(var runtime=new EngineRuntimePort(host,repository,Map.of("python",args[0]),root.resolve("calls"),2)) {
            runtime.prepare("identity","1.0.0",pkg.sha256());
            var field=EngineRuntimePort.class.getDeclaredField("runners");field.setAccessible(true);
            var runner=((Map<?,?>)field.get(runtime)).get("identity");
            var identityField=runner.getClass().getDeclaredField("identity");identityField.setAccessible(true);
            var owned=(ProcessProvider.Identity)identityField.get(runner);
            identityField.set(runner,new ProcessProvider.Identity(owned.pid(),owned.startedAt().minusSeconds(1)));
            RealRuntimeTest.check(!runtime.ready("identity","1.0.0",pkg.sha256()),"Same PID with another start identity is not READY");
            RealRuntimeTest.rejected(()->runtime.publish("t1",Set.of("identity"),1),"NOT_READY");
            identityField.set(runner,new ProcessProvider.Identity(unrelated.pid(),unrelated.info().startInstant().orElseThrow()));
            RealRuntimeTest.check(!runtime.ready("identity","1.0.0",pkg.sha256()),"Stale metadata pointing to another live PID is not READY");
            runtime.stop("identity");
            RealRuntimeTest.check(unrelated.isAlive(),"Cleanup did not signal the PID named by stale metadata");
            RealRuntimeTest.check(ProcessHandle.of(owned.pid()).map(p->!p.isAlive()).orElse(true),"Actual owned child was terminated");
        } finally {unrelated.destroy();if(!unrelated.waitFor(2,java.util.concurrent.TimeUnit.SECONDS)){unrelated.destroyForcibly();unrelated.waitFor();}}
        RealRuntimeTest.check(Schema.number(ProcessProvider.metrics().get("activeProcesses"))==0,"Owned process quota reclaimed");
        System.out.println("PASS simulated stale/PID-reuse metadata: start-time mismatch rejected, unrelated real fixture process survives cleanup; OS PID reuse not forced");
    }
}
