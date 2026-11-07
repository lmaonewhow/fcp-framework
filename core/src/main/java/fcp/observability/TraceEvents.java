package fcp.observability;

import fcp.Json;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/** Bounded best-effort diagnostic summaries, not a durable audit log.
 * Queue saturation drops telemetry, never blocks a business request; metrics expose loss.
 */
public final class TraceEvents {
    private TraceEvents() {}
    public record Event(String kind,String operation,String outcome,long durationNanos,
                        String traceId,String spanId,String parentSpanId,String requestId) {}
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
        public Map<String,Long> metrics(){return Map.of("accepted",accepted.sum(),"dropped",dropped.sum(),"sinkFailures",failed.sum(),"queued",(long)queue.size());}
        /** Does not await a stalled sink; diagnostic loss at shutdown is observable, not a request failure. */
        public void close(){closed=true;worker.interrupt();}
    }
    private static final class Holder {
        static final Recorder INSTANCE=new Recorder(2048,event->System.err.println(Json.write(Map.of(
            "event",event.kind(),"operation",event.operation(),"outcome",event.outcome(),"durationNanos",event.durationNanos(),
            "traceId",event.traceId(),"spanId",event.spanId(),"parentSpanId",event.parentSpanId(),"requestId",event.requestId()))));
    }
    public static void record(String kind,String operation,String outcome,long startedNanos){
        var trace=TraceContext.current();if(trace==null)return;
        Holder.INSTANCE.record(new Event(label(kind),label(operation),label(outcome),Math.max(0,System.nanoTime()-startedNanos),trace.traceId(),trace.spanId(),trace.parentSpanId(),trace.requestId()));
    }
    /** Labels are implementation-controlled, never request input/error messages. */
    private static String label(String value){return value!=null&&value.length()<=96&&value.matches("[A-Za-z0-9_.:-]{1,96}")?value:"other";}
    public static Map<String,Long> metrics(){return Holder.INSTANCE.metrics();}
}
