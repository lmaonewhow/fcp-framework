package fcp.observability;

import fcp.Json;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/** Bounded best-effort diagnostic summaries, not a durable audit log.
 * Queue saturation drops telemetry, never blocks a business request; metrics expose loss.
 */
public final class TraceEvents {
    private TraceEvents() {}
    /** Immutable host manifest identity, validated once per provider rather than per request. */
    public record PluginIdentity(String pluginId,String pluginVersion) {
        public PluginIdentity {pluginId=label(pluginId,128);pluginVersion=label(pluginVersion,100,true);}
    }
    /** Fixed RPC-only fields. Never accept arbitrary metadata maps or serialize a result/error object. */
    public record RpcSummary(PluginIdentity plugin,String capability,String errorCode) {
        public RpcSummary {capability=label(capability,128);errorCode="NONE".equals(errorCode)?"NONE":rpcError(errorCode);}
    }
    /** Host-selected routing identity/classification only; no affinity or invocation data. */
    public record ReleaseMetadata(String selectedReleaseId,long routeRevision,boolean technicalFailure,
                                  String businessOutcome,boolean unknownResult) {
        public ReleaseMetadata {
            if(!releaseId(selectedReleaseId)||routeRevision<0||routeRevision>9007199254740991L||
                !("SUCCESS".equals(businessOutcome)||"FAILURE".equals(businessOutcome)||"UNKNOWN".equals(businessOutcome)))
                throw new IllegalArgumentException("Invalid release trace metadata");
        }
    }
    public record Event(String kind,String operation,String outcome,long durationNanos,
                        String traceId,String spanId,String parentSpanId,String requestId,RpcSummary rpc,ReleaseMetadata release) {
        public Event(String kind,String operation,String outcome,long durationNanos,
                     String traceId,String spanId,String parentSpanId,String requestId) {
            this(kind,operation,outcome,durationNanos,traceId,spanId,parentSpanId,requestId,null,null);
        }
        public Event(String kind,String operation,String outcome,long durationNanos,
                     String traceId,String spanId,String parentSpanId,String requestId,RpcSummary rpc) {
            this(kind,operation,outcome,durationNanos,traceId,spanId,parentSpanId,requestId,rpc,null);
        }
    }
    private static boolean releaseId(String value){
        // ReleasePolicy domain, including complete 100-character ReleasePlan IDs. Never truncate.
        if(value==null||value.length()>256||value.isBlank())return false;
        for(int i=0;i<value.length();i++)if(Character.isISOControl(value.charAt(i)))return false;
        return true;
    }
    private static final Set<String> RPC_ERRORS=Set.of("BUSY","DEADLINE","CANCELLED","TRANSPORT","FRAME_LIMIT",
        "PROTOCOL","CLOSED","RUNNER_EXITED","BUSINESS_RULE","INVALID_INPUT","PLUGIN_REJECTED","INTERNAL_ERROR");
    private static String rpcError(String code){return code!=null&&code.length()<=32&&RPC_ERRORS.contains(code)?code:"OTHER";}
    public static final class Recorder implements AutoCloseable {
        private final ArrayBlockingQueue<Event> queue;
        private final LongAdder accepted=new LongAdder(),dropped=new LongAdder(),failed=new LongAdder();
        private final Thread worker;private volatile boolean closed;
        public Recorder(int capacity,Consumer<Event> sink) {
            if(capacity<1||capacity>65536)throw new IllegalArgumentException("Trace queue capacity");
            queue=new ArrayBlockingQueue<>(capacity);
            worker=new Thread(()->{while(!closed||!queue.isEmpty())try{Event e=queue.poll(100,java.util.concurrent.TimeUnit.MILLISECONDS);if(e!=null)try{sink.accept(e);}catch(RuntimeException failure){failed.increment();}}catch(InterruptedException ignored){if(closed)break;}},"fcp-trace-events");
            worker.setDaemon(true);worker.start();
        }
        public void record(Event event){if(closed||!queue.offer(event))dropped.increment();else accepted.increment();}
        private void drop(){dropped.increment();}
        public Map<String,Long> metrics(){return Map.of("accepted",accepted.sum(),"dropped",dropped.sum(),"sinkFailures",failed.sum(),"queued",(long)queue.size());}
        /** Does not await a stalled sink; diagnostic loss at shutdown is observable, not a request failure. */
        public void close(){closed=true;worker.interrupt();}
    }
    private static final class Holder {
        static final Recorder INSTANCE=new Recorder(2048,event->System.err.println(Json.write(fields(event))));
    }
    private static Map<String,Object> fields(Event event){
        Map<String,Object> fields=Map.of("event",event.kind(),"operation",event.operation(),"outcome",event.outcome(),"durationNanos",event.durationNanos(),
            "traceId",event.traceId(),"spanId",event.spanId(),"parentSpanId",event.parentSpanId(),"requestId",event.requestId());
        if(event.rpc()==null&&event.release()==null)return fields; // HTTP/task keeps its original map and serialization.
        Map<String,Object> detailed=new LinkedHashMap<>(fields);
        if(event.rpc()!=null){RpcSummary rpc=event.rpc();
            detailed.put("pluginId",rpc.plugin().pluginId());detailed.put("pluginVersion",rpc.plugin().pluginVersion());
            detailed.put("capability",rpc.capability());detailed.put("errorCode",rpc.errorCode());
        }
        if(event.release()!=null){ReleaseMetadata release=event.release();
            detailed.put("release",Map.of("selectedReleaseId",release.selectedReleaseId(),"routeRevision",release.routeRevision(),
                "technicalFailure",release.technicalFailure(),"businessOutcome",release.businessOutcome(),"unknownResult",release.unknownResult()));
        }
        return detailed;
    }
    /** RETURNED only means a JSON-RPC result arrived, not business success or a committed side effect. */
    public static void recordRpc(PluginIdentity plugin,String capability,String operation,boolean returned,String errorCode,long startedNanos){
        var trace=TraceContext.current();if(trace==null)return;
        var rpc=new RpcSummary(plugin,capability,returned?"NONE":rpcError(errorCode));
        Holder.INSTANCE.record(new Event("plugin.rpc",label(operation),returned?"RETURNED":"ERROR",Math.max(0,System.nanoTime()-startedNanos),
            trace.traceId(),trace.spanId(),trace.parentSpanId(),trace.requestId(),rpc));
    }
    /** Best-effort host completion, separate from RPC result arrival and never a business decision. */
    public static void recordReleaseInvocation(String releaseId,long revision,boolean technicalFailure,String businessOutcome,boolean unknownResult,long startedNanos){
        var trace=TraceContext.current();if(trace==null)return;
        try{
            var release=new ReleaseMetadata(releaseId,revision,technicalFailure,businessOutcome,unknownResult);
            String outcome=unknownResult||"UNKNOWN".equals(businessOutcome)?"UNKNOWN":
                technicalFailure||"FAILURE".equals(businessOutcome)?"FAILURE":"SUCCESS";
            Holder.INSTANCE.record(new Event("release.invocation","invoke",outcome,Math.max(0,System.nanoTime()-startedNanos),
                trace.traceId(),trace.spanId(),trace.parentSpanId(),trace.requestId(),null,release));
        }catch(RuntimeException invalid){Holder.INSTANCE.drop();} // Diagnostics cannot replace a business result/exception.
    }
    public static void record(String kind,String operation,String outcome,long startedNanos){
        var trace=TraceContext.current();if(trace==null)return;
        Holder.INSTANCE.record(new Event(label(kind),label(operation),label(outcome),Math.max(0,System.nanoTime()-startedNanos),trace.traceId(),trace.spanId(),trace.parentSpanId(),trace.requestId()));
    }
    /** Labels are implementation-controlled, never request input/error messages. */
    private static String label(String value){return label(value,96);}
    private static String label(String value,int max){return label(value,max,false);}
    private static String label(String value,int max,boolean version){
        if(value==null||value.isEmpty()||value.length()>max)return "other";
        for(int i=0;i<value.length();i++){char c=value.charAt(i);if(!(c>='A'&&c<='Z'||c>='a'&&c<='z'||c>='0'&&c<='9'||c=='_'||c=='.'||c==':'||c=='-'||version&&c=='+'))return "other";}
        return value;
    }
    public static Map<String,Long> metrics(){return Holder.INSTANCE.metrics();}
}
