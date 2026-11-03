import fcp.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

/** Real child termination with an explicit slow process-enumeration test double. */
public final class ProcessTreeRegression {
    static int checks;
    static void check(boolean value,String name){if(!value)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
    static final class WrappedProcess extends Process {
        final Process process;final ProcessHandle handle;
        WrappedProcess(Process process,ProcessHandle handle){this.process=process;this.handle=handle;}
        public OutputStream getOutputStream(){return process.getOutputStream();}
        public InputStream getInputStream(){return process.getInputStream();}
        public InputStream getErrorStream(){return process.getErrorStream();}
        public int waitFor()throws InterruptedException{return process.waitFor();}
        public boolean waitFor(long timeout,TimeUnit unit)throws InterruptedException{return process.waitFor(timeout,unit);}
        public int exitValue(){return process.exitValue();}
        public void destroy(){process.destroy();}
        public Process destroyForcibly(){process.destroyForcibly();return this;}
        public boolean isAlive(){return process.isAlive();}
        public ProcessHandle toHandle(){return handle;}
    }
    public static void main(String[] args)throws Exception {
        String python=System.getenv().getOrDefault("FCP_PYTHON",System.getProperty("os.name").startsWith("Windows")?"python":"python3");
        String code="import json,sys,os\nfor line in sys.stdin:\n r=json.loads(line); print(json.dumps({'jsonrpc':'2.0','id':r['id'],'result':{'hasPath':bool(os.environ.get('PATH')),'hasWindir':bool(os.environ.get('WINDIR'))}}),flush=True)\n";
        var document=Json.obj("runtime",Json.obj("launcher","python","timeout",2,"idleTimeoutMs",300000));
        var launchers=Json.obj("python",Json.obj("argv",List.of(python,"-I","-S","-u","-c",code),"requires",List.of()));
        Process delayedChild=new ProcessBuilder(python,"-I","-S","-u","-c","import time; time.sleep(30)").start();
        var entered=new CountDownLatch(1);
        try(var provider=new ProcessProvider(document,java.nio.file.Path.of("."),launchers)) {
            var environment=Schema.map(provider.invoke(null,"api","test",Map.of()));
            String expectedPath=System.getenv("PATH");
            check(Boolean.TRUE.equals(environment.get("hasPath"))==(expectedPath!=null&&!expectedPath.isEmpty()),"actual child preserves whitelisted PATH despite Windows Path casing");
            if(System.getProperty("os.name").startsWith("Windows"))check(Boolean.TRUE.equals(environment.get("hasWindir")),"actual child preserves Windows windir casing");
            Field field=ProcessProvider.class.getDeclaredField("process");field.setAccessible(true);
            Process actual=(Process)field.get(provider);ProcessHandle actualHandle=actual.toHandle();
            ProcessHandle slow=(ProcessHandle)Proxy.newProxyInstance(ProcessHandle.class.getClassLoader(),new Class<?>[]{ProcessHandle.class},(proxy,method,arguments)->{
                if(method.getName().equals("descendants")){entered.countDown();Thread.sleep(1500);return Stream.of(delayedChild.toHandle());}
                try{return method.invoke(actualHandle,arguments);}catch(InvocationTargetException failure){throw failure.getCause();}
            });
            field.set(provider,new WrappedProcess(actual,slow));
            long start=System.nanoTime();provider.close();long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
            check(entered.await(1,TimeUnit.SECONDS),"bounded executor attempted process-tree enumeration");
            check(elapsed<1200,"root close does not await 1500ms enumeration (actual "+elapsed+"ms)");
            check(!actual.isAlive(),"actual root child terminated before close returns");
            check(((Number)ProcessProvider.metrics().get("activeProcesses")).intValue()==0,"quota released only after actual root exit");
            check(delayedChild.waitFor(5,TimeUnit.SECONDS),"late enumeration still terminates its captured child");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(((Number)ProcessProvider.metrics().get("pendingDescendantScans")).intValue()!=0&&System.nanoTime()<deadline)Thread.sleep(10);
            check(((Number)ProcessProvider.metrics().get("pendingDescendantScans")).intValue()==0,"completed enumeration leaves no pending scan");
            System.out.println("RESULT "+checks+" process-tree checks PASS; slow enumeration is an explicit test double");
        } finally {delayedChild.destroyForcibly();delayedChild.waitFor(5,TimeUnit.SECONDS);}
    }
}
