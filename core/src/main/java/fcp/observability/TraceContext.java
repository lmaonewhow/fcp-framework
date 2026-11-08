package fcp.observability;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;

/** Correlation only, not an OTel exporter or authority. No baggage or user data is retained.
 * Entry points must choose whether the immediate peer is trusted; public headers default off.
 * Context is explicit/thread-scoped, never inherited by a newly created pool thread.
 */
public final class TraceContext {
    private TraceContext() {}
    private static final ThreadLocal<Trace> CURRENT = new ThreadLocal<>();
    public record Trace(String traceId, String spanId, String parentSpanId, String requestId) {
        public Trace {
            if (!validTraceId(traceId) || !hex(spanId,16) || zero(spanId)
                    || !(parentSpanId.isEmpty() || hex(parentSpanId,16) && !zero(parentSpanId))
                    || !hex(requestId,32) || zero(requestId)) throw new IllegalArgumentException("Invalid trace context");
        }
        // No telemetry sampling claim: an optional OTel adapter owns its separate sampling decision.
        public String traceparent() { return "00-"+traceId+"-"+spanId+"-00"; }
    }
    public static boolean validTraceId(String value) { return hex(value,32) && !zero(value); }
    private static boolean hex(String value,int size) {
        if(value==null||value.length()!=size)return false;
        for(int i=0;i<size;i++){char c=value.charAt(i);if(!(c>='0'&&c<='9'||c>='a'&&c<='f'))return false;}
        return true;
    }
    private static boolean zero(String value){for(int i=0;i<value.length();i++)if(value.charAt(i)!='0')return false;return true;}
    private static String id(int bytes) {
        // Random identifiers are not credentials. Independent 128-bit roots avoid cross-request linkage.
        var random=ThreadLocalRandom.current();StringBuilder result=new StringBuilder(bytes*2);
        do {result.setLength(0);for(int i=0;i<bytes/8;i++){String part=Long.toHexString(random.nextLong());result.append("0".repeat(16-part.length())).append(part);}} while(zero(result.toString()));
        return result.toString();
    }
    public static Trace start(String header,boolean trustedPeer) {
        // Strict W3C v00 support, reject duplicates, future versions, zero IDs and overlong values.
        if(trustedPeer&&header!=null&&header.length()==55&&header.startsWith("00-")&&header.charAt(35)=='-'
                &&header.charAt(52)=='-'&&validTraceId(header.substring(3,35))
                &&hex(header.substring(36,52),16)&&!zero(header.substring(36,52))&&hex(header.substring(53),2))
            return new Trace(header.substring(3,35),id(8),header.substring(36,52),id(16));
        return new Trace(id(16),id(8),"",id(16));
    }
    public static Trace current(){return CURRENT.get();}
    /** Returns a root when absent, without attaching it or leaking it into a reused thread. */
    public static Trace ensure(){Trace trace=current();return trace==null?start(null,false):trace;}
    public static Trace child(){Trace parent=ensure();return new Trace(parent.traceId(),id(8),parent.spanId(),parent.requestId());}
    /** Only a server-created TrustedContext may call this. Legacy opaque IDs are not accepted on the wire. */
    public static Trace fromTrusted(String traceId,String requestId){
        Trace current=current();if(current!=null&&current.traceId().equals(traceId))return current;
        return new Trace(validTraceId(traceId)?traceId:id(16),id(8),"",validTraceId(requestId)?requestId:id(16));
    }
    /** Callback metadata may extend correlation only within the already authenticated invocation. */
    public static Trace remoteChild(String header) {
        Trace parent=current();Trace remote=start(header,true);
        if(parent!=null&&remote.traceId().equals(parent.traceId()))return new Trace(parent.traceId(),remote.spanId(),remote.parentSpanId(),parent.requestId());
        return child();
    }
    public static Scope open(Trace trace){Trace before=current();if(trace==null)CURRENT.remove();else CURRENT.set(trace);return new Scope(before);}
    public static final class Scope implements AutoCloseable {
        private final Thread owner=Thread.currentThread();private final Trace before;private boolean closed;
        private Scope(Trace before){this.before=before;}
        public void close(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Trace scope belongs to another thread");if(closed)return;closed=true;if(before==null)CURRENT.remove();else CURRENT.set(before);}
    }
    public static Runnable wrap(Runnable work){Objects.requireNonNull(work);Trace captured=current();return ()->{try(var scope=open(captured)){work.run();}};}
    public static <T> Callable<T> wrap(Callable<T> work){Objects.requireNonNull(work);Trace captured=current();return ()->{try(var scope=open(captured)){return work.call();}};}
}
