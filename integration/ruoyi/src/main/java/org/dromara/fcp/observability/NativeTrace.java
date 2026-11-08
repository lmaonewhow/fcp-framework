package org.dromara.fcp.observability;

import fcp.observability.TraceContext;
import org.slf4j.MDC;
import java.util.Map;

/** Scoped binding: never leave a previous request on a pooled worker. */
public final class NativeTrace {
    private NativeTrace() { }
    public static Scope open(TraceContext.Trace trace) { return new Scope(trace); }
    public static Runnable wrap(Runnable work) {
        var trace = TraceContext.current();
        return () -> { try (var ignored = open(trace)) { work.run(); } };
    }
    public static <T> java.util.concurrent.Callable<T> wrap(java.util.concurrent.Callable<T> work) {
        var trace = TraceContext.current();
        return () -> { try (var ignored = open(trace)) { return work.call(); } };
    }
    /** Each recurring tick is a new operation, not an eternal child of its registration request. */
    public static Runnable recurring(Runnable work) {
        return () -> { try (var ignored = open(TraceContext.start(null,false))) { work.run(); } };
    }
    public static final class Scope implements AutoCloseable {
        private final Map<String,String> previous = MDC.getCopyOfContextMap();
        private final TraceContext.Scope scope;
        private Scope(TraceContext.Trace trace) {
            scope = TraceContext.open(trace);
            if (trace == null) { MDC.remove("traceId"); MDC.remove("spanId"); MDC.remove("requestId"); }
            else { MDC.put("traceId",trace.traceId()); MDC.put("spanId",trace.spanId()); MDC.put("requestId",trace.requestId()); }
        }
        public void close() { try { scope.close(); } finally { if(previous==null)MDC.clear(); else MDC.setContextMap(previous); } }
    }
}
