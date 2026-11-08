package fcp;
import java.nio.file.*;
import fcp.observability.TraceContext;
import fcp.observability.TraceEvents;
import java.nio.charset.*;
import java.nio.ByteBuffer;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import static fcp.Schema.*;

/** Bounded synchronous stdio protocol, asynchronous pipe I/O, host-mediated callbacks. */
public final class ProcessProvider implements Engine.Provider {
    private static final int MAX_FRAME=1048576;
    private static final int MAX_PROCESSES = integerProperty("fcp.maxProcesses", 8, 1, 64);
    private static final Semaphore SLOTS = new Semaphore(MAX_PROCESSES);
    private static final java.util.concurrent.atomic.AtomicInteger THREAD_IDS = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger TREE_SCANS = new java.util.concurrent.atomic.AtomicInteger();
    // No unbounded task queue; stderr drain plus one synchronous pipe operation per child.
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(0, MAX_PROCESSES * 3,
            2, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> daemon(r,"fcp-pipe-"+THREAD_IDS.incrementAndGet()),
            new ThreadPoolExecutor.AbortPolicy());
    private static final ScheduledThreadPoolExecutor REAPER = new ScheduledThreadPoolExecutor(1, r -> daemon(r,"fcp-idle-reaper"));
    static { REAPER.setRemoveOnCancelPolicy(true); REAPER.setExecuteExistingDelayedTasksAfterShutdownPolicy(false); }
    private static int integerProperty(String key, int fallback, int min, int max) {
        int value = Integer.parseInt(System.getProperty(key, String.valueOf(fallback)));
        if (value < min || value > max) throw new IllegalArgumentException(key+" outside supported range");
        return value;
    }
    /** Aggregate metrics contain no plugin credentials or payloads. */
    public static Map<String,Object> metrics() {
        return Json.obj("processLimit",MAX_PROCESSES,"activeProcesses",MAX_PROCESSES-SLOTS.availablePermits(),
                "ioThreads",IO.getPoolSize(),"activeIoTasks",IO.getActiveCount(),"idleTimers",REAPER.getQueue().size(),"pendingDescendantScans",TREE_SCANS.get(),"traceTelemetry",TraceEvents.metrics());
    }
    private final ReentrantLock invocationLock=new ReentrantLock(true);
    private final Map<String,Object> runtime,launcher;
    private final Path root;
    private final TraceEvents.PluginIdentity tracePlugin;
    private ScheduledFuture<?> reaper;
    private long idleEpoch;
    private final long idleTimeoutMs;
    private volatile Process process;
    private InputStream output;
    private OutputStream input;

    private boolean closed,slot,started;
    /** Identity of the actual owned Process object, never a PID-only lookup. */
    public record Identity(long pid, java.time.Instant startedAt) {}
    public Identity identity(){Process p=process;if(p==null||!p.isAlive())return null;return new Identity(p.pid(),p.info().startInstant().orElse(java.time.Instant.EPOCH));}
    public boolean isRunning(){return identity()!=null;}
    private static Thread daemon(Runnable work,String name){Thread thread=new Thread(work,name);thread.setDaemon(true);return thread;}
    public ProcessProvider(Map<String,Object> document,Path root,Map<String,Object> launchers){
        tracePlugin=new TraceEvents.PluginIdentity(document.get("id") instanceof String id?id:null,document.get("version") instanceof String version?version:null);
        this.root=root.toAbsolutePath().normalize();runtime=obj(document,"runtime");Object record=launchers.get(runtime.get("launcher"));if(record==null)throw new FcpException("LAUNCHER_MISSING",String.valueOf(runtime.get("launcher")));launcher=map(record);
        long timeout=number(runtime.getOrDefault("timeout",8));
        if(timeout<1||timeout>60)throw new FcpException("MANIFEST","Plugin timeout must be 1..60 seconds");
        if(arr(launcher,"argv").isEmpty())throw new FcpException("MANIFEST","Launcher argv is empty");
        String executable=expand(str(arr(launcher,"argv").get(0)));boolean found=new File(executable).isFile();
        for(String folder:System.getenv().getOrDefault("PATH","").split(java.util.regex.Pattern.quote(File.pathSeparator))){for(String suffix:System.getProperty("os.name").startsWith("Windows")?List.of("",".exe",".cmd"):List.of(""))if(new File(folder,executable+suffix).isFile())found=true;}
        if(!found)throw new FcpException("RUNTIME_MISSING",executable);
        for(Object required:arr(launcher,"requires"))if(!Files.exists(Path.of(expand(str(required)))))throw new FcpException("BUILD_REQUIRED",str(required));
        idleTimeoutMs=number(runtime.getOrDefault("idleTimeoutMs",30000));
        if(idleTimeoutMs<100||idleTimeoutMs>300000)throw new FcpException("MANIFEST","idleTimeoutMs must be 100..300000");
    }
    private String expand(String value){return value.replace("{root}",root.toString()).replace("{python}",System.getProperty("fcp.python",System.getenv().getOrDefault("FCP_PYTHON",System.getProperty("os.name").startsWith("Windows")?"python":"python3"))).replace("{pathsep}",File.pathSeparator).replace("{exe}",System.getProperty("os.name").startsWith("Windows")?".exe":"");}
    private void start()throws IOException{
        if(!SLOTS.tryAcquire())throw new FcpException("BUSY","Plugin process quota exhausted");slot=true;
        try{
            List<String> argv=new ArrayList<>();for(Object argument:arr(launcher,"argv"))argv.add(expand(str(argument)));
            ProcessBuilder builder=new ProcessBuilder(argv);builder.directory(root.resolve((String)launcher.getOrDefault("cwd",".")).toFile());Map<String,String> environment=builder.environment(),keep=new HashMap<>();
            var allowed=List.of("PATH","SystemRoot","WINDIR","TEMP","TMP","JAVA_HOME","DOTNET_ROOT");
            boolean windows=System.getProperty("os.name").startsWith("Windows");
            for(var entry:environment.entrySet())
                if(allowed.stream().anyMatch(key->windows?key.equalsIgnoreCase(entry.getKey()):key.equals(entry.getKey())))
                    keep.put(entry.getKey(),entry.getValue());
            environment.clear();environment.putAll(keep);environment.put("LANG","C.UTF-8");environment.put("PYTHONIOENCODING","utf-8");for(var e:obj(launcher,"env").entrySet())environment.put(e.getKey(),expand(str(e.getValue())));
            process=builder.start();started=true;input=new BufferedOutputStream(process.getOutputStream());output=new BufferedInputStream(process.getInputStream());InputStream errors=process.getErrorStream();
            IO.submit(()->{try{byte[] buffer=new byte[2048];while(errors.read(buffer)!=-1){/* Drain bounded chunks; never retain credentials. */}}catch(IOException ignored){/* Expected at process cleanup. */}});
        }catch(IOException|RuntimeException error){stop();throw error;}
    }
    private byte[] line(InputStream source)throws IOException{
        ByteArrayOutputStream buffer=new ByteArrayOutputStream();int b;
        while((b=source.read())!=-1){if(b==10)return buffer.toByteArray();if(buffer.size()>=MAX_FRAME-1)throw new FcpException("FRAME_LIMIT","Frame exceeds 1 MiB including LF");buffer.write(b);}
        throw new EOFException("Plugin exited");
    }
    private <T>T await(Callable<T> action,long deadline){
        final Future<T> result;
        try { result=IO.submit(TraceContext.wrap(action)); }
        catch(RejectedExecutionException error){stop();throw new FcpException("BUSY","Bounded pipe executor is saturated",error);}
        try{return result.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);}
        catch(TimeoutException error){stop();result.cancel(true);throw new FcpException("DEADLINE","Plugin timed out",error);}
        catch(ExecutionException error){stop();if(error.getCause() instanceof FcpException fcp)throw fcp;throw new FcpException("TRANSPORT",error.getCause().toString(),error);}
        catch(InterruptedException error){Thread.currentThread().interrupt();stop();result.cancel(true);throw new FcpException("CANCELLED","Interrupted",error);}
    }
    private void send(Map<String,Object> message,long deadline){
        if(System.nanoTime()>=deadline)throw new FcpException("DEADLINE","Request budget expired before send");
        byte[] wire=(Json.write(message)+"\n").getBytes(StandardCharsets.UTF_8);if(wire.length>MAX_FRAME)throw new FcpException("FRAME_LIMIT","Input too large");
        OutputStream target=input;
        await(()->{target.write(wire);target.flush();return null;},deadline);
    }
    @Override public Object invoke(Engine.Context context,String export,String operation,Map<String,Object> data){
        return invokeBounded(context,export,operation,data,TimeUnit.SECONDS.toMillis(number(runtime.getOrDefault("timeout",8))));
    }
    /** One monotonic budget covers queueing, callbacks and pipe IO. */
    public Object invokeBounded(Engine.Context context,String export,String operation,Map<String,Object> data,long timeoutMillis){
        if(timeoutMillis<1||timeoutMillis>300000)throw new FcpException("DEADLINE","Invalid remaining request budget");
        try(var traceScope=TraceContext.open(TraceContext.child())) {
        long traceStart=System.nanoTime();boolean traceReturned=false;String traceError="INTERNAL_ERROR";
        try {
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            if(!invocationLock.tryLock(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS))throw new FcpException("BUSY","Plugin queue wait exhausted the request budget");
        } catch(InterruptedException error){Thread.currentThread().interrupt();throw new FcpException("CANCELLED","Queue wait interrupted",error);}
        boolean remoteRejected=false;
        String method=(String)obj(runtime,"methods").getOrDefault(export+"."+operation,operation);String id=UUID.randomUUID().toString();
        try{
            if(closed)throw new FcpException("CLOSED","Provider closed");
            cancelIdle();
            if(process==null||!process.isAlive()){stop();if(started&&Boolean.FALSE.equals(runtime.get("autoRestart")))throw new FcpException("RUNNER_EXITED","Explicit runner reconciliation required");start();}
            Map<String,Object> parameters=data;
            if(Boolean.TRUE.equals(runtime.get("runtimeContext"))&&data.containsKey("context")) {
                parameters=new LinkedHashMap<>(data);Map<String,Object> identity=new LinkedHashMap<>(obj(data,"context"));
                identity.put("traceparent",TraceContext.current().traceparent());parameters.put("context",identity);
            }
            Map<String,Object> envelope=Json.obj("jsonrpc","2.0","id",id,"method",method,"params",parameters);
            // Optional envelope extension for explicitly opted-in legacy stdio workers.
            if("w3c".equals(runtime.get("traceContext")))envelope.put("traceparent",TraceContext.current().traceparent());
            send(envelope,deadline);
            InputStream source=output; int callbacks=0;
            for(;;){
                String raw=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(await(()->line(source),deadline))).toString();Map<String,Object> response=map(Json.parse(raw));
                if("2.0".equals(response.get("jsonrpc"))&&"fcp.call".equals(response.get("method"))){
                    if(context==null)throw new FcpException("PROTOCOL","Lifecycle controls cannot call capabilities");
                    if(!response.keySet().equals(Set.of("jsonrpc","id","method","params"))&&!response.keySet().equals(Set.of("jsonrpc","id","method","params","traceparent")))throw new FcpException("PROTOCOL","Invalid callback envelope");
                    if(++callbacks>32)throw new FcpException("PROTOCOL","Callback limit exceeded");
                    Map<String,Object> p=obj(response,"params");Object callbackId=response.get("id");if(!(callbackId instanceof String)||!p.keySet().equals(Set.of("dependency","operation","input")))throw new FcpException("PROTOCOL","Invalid dependency callback");
                    Map<String,Object> reply;
                    try(var callbackTrace=TraceContext.open(TraceContext.remoteChild(response.get("traceparent") instanceof String header?header:null))){Object result=context.call(str(p.get("dependency")),str(p.get("operation")),map(p.get("input")));reply=Json.obj("jsonrpc","2.0","id",callbackId,"result",result);}
                    catch(FcpException error){reply=Json.obj("jsonrpc","2.0","id",callbackId,"error",Json.obj("code",-32000,"message",error.getMessage()));}
                    if(System.nanoTime()>=deadline)throw new FcpException("DEADLINE","Dependency exceeded parent budget");send(reply,deadline);continue;
                }
                if(!"2.0".equals(response.get("jsonrpc"))||!id.equals(response.get("id"))||response.containsKey("result")==response.containsKey("error"))throw new FcpException("PROTOCOL","Invalid response envelope");
                if(!response.keySet().equals(Set.of("jsonrpc","id",response.containsKey("error")?"error":"result")))throw new FcpException("PROTOCOL","Unknown response fields");
                if(response.containsKey("error")) {
                    Map<String,Object> error=map(response.get("error"));
                    if(!(error.get("code") instanceof Number)||!(error.get("message") instanceof String)||!Set.of("code","message","data").containsAll(error.keySet()))throw new FcpException("PROTOCOL","Invalid JSON-RPC error");
                    try {number(error.get("code"));}catch(FcpException bad){throw new FcpException("PROTOCOL","Error code must be an integer",bad);}
                    Object rawCode=obj(error,"data").getOrDefault("code","PLUGIN_REJECTED");
                    if(!(rawCode instanceof String))throw new FcpException("PROTOCOL","Invalid plugin error code");
                    String code=(String)rawCode;
                    if(!Set.of("BUSINESS_RULE","INVALID_INPUT").contains(code))code="PLUGIN_REJECTED";
                    remoteRejected=true;
                    throw new FcpException(code,"Plugin rejected operation: "+error.getOrDefault("message","unknown"));
                }
                traceReturned=true;return response.get("result");
            }
        }catch(IOException|IllegalArgumentException error){stop();throw new FcpException("TRANSPORT","Malformed or interrupted plugin transport",error);}
        catch(FcpException error){if(!remoteRejected)stop();throw error;}
        finally{if(process!=null&&!closed&&!Boolean.TRUE.equals(runtime.get("keepAlive")))scheduleIdle();invocationLock.unlock();}
        } catch(FcpException error){traceReturned=false;traceError=error.code;throw error;}
        catch(RuntimeException error){traceReturned=false;throw error;}
        finally {TraceEvents.recordRpc(tracePlugin,export,operation,traceReturned,traceError,traceStart);}
        }
    }
    private void cancelIdle(){idleEpoch++;if(reaper!=null){reaper.cancel(false);reaper=null;}}
    private void scheduleIdle(){
        cancelIdle();
        long epoch=idleEpoch;
        reaper=REAPER.schedule(()->{
            invocationLock.lock();
            try{if(!closed&&epoch==idleEpoch)stop();}finally{invocationLock.unlock();}
        },idleTimeoutMs,TimeUnit.MILLISECONDS);
    }
    private List<ProcessHandle> descendants(ProcessHandle handle) {
        // Windows process-tree enumeration can block for seconds. Share the already
        // bounded executor, not an unbounded cleanup queue or the idle-reaper thread.
        CompletableFuture<List<ProcessHandle>> scan=new CompletableFuture<>();
        TREE_SCANS.incrementAndGet();
        try {
            IO.execute(()->{
                try {scan.complete(handle.descendants().toList());}
                catch(RuntimeException failure) {scan.completeExceptionally(failure);}
                finally {TREE_SCANS.decrementAndGet();}
            });
        } catch(RejectedExecutionException saturated) {TREE_SCANS.decrementAndGet();return List.of();}
        try {return scan.get(100,TimeUnit.MILLISECONDS);}
        catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
        catch(TimeoutException | ExecutionException delayed) { /* Root termination must not await a tree snapshot. */ }
        scan.thenAccept(children->{for(ProcessHandle child:children)try{if(child.isAlive())child.destroyForcibly();}catch(RuntimeException ignored){/* best effort */}});
        return List.of();
    }
    private void stop(){
        cancelIdle();
        Process old=process;process=null;input=null;output=null;
        boolean reserved=slot;slot=false;
        boolean interrupted=false;
        try {
            if(old!=null){
                // Process.destroy() also closes Java pipe streams and may block behind a
                // writer when a child ignores SIGTERM. Signal via ProcessHandle instead.
                ProcessHandle handle=old.toHandle();
                List<ProcessHandle> children=descendants(handle);
                for(ProcessHandle child:children)child.destroy();
                handle.destroy();
                try {old.waitFor(1,TimeUnit.SECONDS);}
                catch(InterruptedException error){interrupted=true;}
                for(ProcessHandle child:children)if(child.isAlive())child.destroyForcibly();
                if(handle.isAlive())handle.destroyForcibly();
                try {old.waitFor(2,TimeUnit.SECONDS);}
                catch(InterruptedException error){interrupted=true;}
                // The JDK closes its process streams on exit. Do not synchronously
                // close/flush a pipe that another task may still be writing or reading.
                // Descendant cleanup is best effort, not an untrusted-code sandbox.
            }
        } finally {
            if(reserved){
                if(old==null||!old.isAlive())SLOTS.release();
                else {
                    // Never advertise a free slot while the OS process is still alive.
                    ProcessHandle handle=old.toHandle();
                    handle.onExit().whenComplete((ignored,error)->{
                        if(!handle.isAlive())SLOTS.release();
                    });
                }
            }
            if(interrupted)Thread.currentThread().interrupt();
        }
    }
    @Override public void close(){invocationLock.lock();try{if(closed)return;closed=true;cancelIdle();stop();}finally{invocationLock.unlock();}}
}
