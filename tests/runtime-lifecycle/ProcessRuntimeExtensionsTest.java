import fcp.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class ProcessRuntimeExtensionsTest {
    static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("process-extension-");Files.writeString(root.resolve("probe.py"),"import sys,json,time\nfor line in sys.stdin:\n r=json.loads(line);time.sleep(r['params'].get('sleep',0));print(json.dumps({'jsonrpc':'2.0','id':r['id'],'result':{'ok':True}}),flush=True)\n");
        var doc=Json.obj("runtime",Json.obj("launcher","python","timeout",2,"idleTimeoutMs",100,"keepAlive",true,"autoRestart",false));
        var launchers=Json.obj("python",Json.obj("argv",List.of(args[0],root.resolve("probe.py").toString())));
        try(var provider=new ProcessProvider(doc,root,launchers)) {
            provider.invoke(null,"control","ready",Map.of());var identity=provider.identity();check(identity!=null&&!identity.startedAt().equals(java.time.Instant.EPOCH),"owned process start identity");Thread.sleep(250);check(identity.equals(provider.identity()),"managed keepAlive suppresses idle stop");
            ExecutorService pool=Executors.newSingleThreadExecutor();Future<?> work=pool.submit(()->provider.invokeBounded(null,"api","invoke",Map.of("sleep",0.4),1000));Thread.sleep(100);
            long start=System.nanoTime();try{provider.invokeBounded(null,"api","invoke",Map.of(),50);throw new AssertionError("queue budget");}catch(FcpException e){check(e.code.equals("BUSY"),"queue budget code");}check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<300,"queue bounded budget");work.get();pool.shutdown();
            try{provider.invokeBounded(null,"api","invoke",Map.of("sleep",1),100);throw new AssertionError("deadline");}catch(FcpException e){check(e.code.equals("DEADLINE"),"transport budget");}
            check(provider.identity()==null,"dead owned process not ready");try{provider.invoke(null,"api","invoke",Map.of());throw new AssertionError("silent restart");}catch(FcpException e){check(e.code.equals("RUNNER_EXITED"),"requires explicit handshake reconciliation");}
        }
        check(Schema.number(ProcessProvider.metrics().get("activeProcesses"))==0,"slots reclaimed");System.out.println("PASS process runtime extensions: owned identity, keepAlive, queue+IO deadline budget, no implicit restart, cleanup");
    }
}
