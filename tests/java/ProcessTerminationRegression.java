import fcp.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static fcp.Schema.*;

/** Real child-process regression: a non-reading child must not trap the host in cleanup. */
public final class ProcessTerminationRegression {
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("fcp-stalled-stdin-");
        Path script=root.resolve("stalled.py");
        Files.writeString(script,"import signal,time\nsignal.signal(signal.SIGTERM, signal.SIG_IGN)\ntime.sleep(120)\n");
        Registry registry=new Registry();
        registry.register("contract",Json.obj("id","probe.v1","operations",Json.obj("run",Json.obj("input",Json.obj("type","object"),"output",Json.obj("type","object"),"effect","read"))));
        registry.register("module",Json.obj("protocol","fcp/0.3","kind","module","id","child","version","1","provides",Json.obj("api","probe.v1"),"requires",Map.of(),"runtime",Json.obj("provider","stdio","launcher","test","timeout",2,"idleTimeoutMs",5000)));
        Map<String,Object> launchers=Json.obj("test",Json.obj("argv",List.of("{python}","-I","-u","{root}/stalled.py")));
        registry.factories.put("stdio",(manifest,scope)->new ProcessProvider(manifest,root,launchers));
        ExecutorService executor=Executors.newSingleThreadExecutor(task->{Thread thread=new Thread(task,"stalled-child-invocation");thread.setDaemon(true);return thread;});
        Engine engine=new Engine(registry,null);
        engine.publish("a",Json.obj("protocol","fcp/0.3","kind","composition","id","test.app","modules",List.of("child"),"exports",Json.obj("child","child/api")),0);
        long started=System.nanoTime();
        Future<String> call=executor.submit(()->{
            try{engine.invoke("a","child","run",Json.obj("payload","x".repeat(512*1024)),Map.of(),1L);return "UNEXPECTED_SUCCESS";}
            catch(FcpException error){return error.code;}
        });
        boolean bounded=false;String result="NOT_RETURNED";
        try {
            result=call.get(9,TimeUnit.SECONDS);
            bounded="DEADLINE".equals(result);
        } catch(TimeoutException timeout) {
            System.out.println("FAIL stalled stdin cleanup exceeded outer 9-second watchdog");
            Thread.getAllStackTraces().forEach((thread,stack)->{
                if(thread.getName().equals("stalled-child-invocation") || thread.getName().startsWith("fcp-pipe")){
                    System.out.println(thread.getName()+" "+thread.getState());
                    for(StackTraceElement frame:stack)System.out.println("  at "+frame);
                }
            });
        } finally {
            // Watchdog is outside the code under test. Never use Process.destroy(), whose stream cleanup is being tested.
            ProcessHandle.current().descendants().forEach(ProcessHandle::destroyForcibly);
            try{call.get(5,TimeUnit.SECONDS);}catch(Exception ignored){}
            executor.shutdownNow();
        }
        if(!bounded){System.out.println("RESULT 0 process-termination checks PASS; failure="+result);System.exit(1);}
        engine.close();
        if(number(ProcessProvider.metrics().get("activeProcesses"))!=0)throw new AssertionError("Process slot leaked");
        Files.deleteIfExists(script);Files.deleteIfExists(root);
        System.out.println("PASS stalled stdin returns DEADLINE without blocking cleanup");
        System.out.println("PASS process slot released after child termination");
        System.out.println("Elapsed seconds="+((System.nanoTime()-started)/1e9));
        System.out.println("RESULT 2 process-termination checks PASS");
    }
}
