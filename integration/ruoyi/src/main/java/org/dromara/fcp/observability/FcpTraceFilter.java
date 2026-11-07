package org.dromara.fcp.observability;

import fcp.observability.TraceContext;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerMapping;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** First servlet boundary, including native login, captcha and rejected requests.
 * Only explicitly configured socket peers may propagate traceparent. Forwarded headers are ignored.
 */
public final class FcpTraceFilter implements Filter {
    public static final String ATTRIBUTE = FcpTraceFilter.class.getName()+".state";
    private static final Logger LOG = LoggerFactory.getLogger("fcp.request");
    private final Set<String> peers;
    public FcpTraceFilter(Set<String> peers) { this.peers=Set.copyOf(peers); }
    private static final class State {
        final TraceContext.Trace trace;
        final long started=System.nanoTime();
        final AtomicBoolean completed=new AtomicBoolean();
        volatile boolean failed;
        State(TraceContext.Trace trace) { this.trace=trace; }
    }
    @Override public void doFilter(ServletRequest raw, ServletResponse output, FilterChain chain) throws IOException, ServletException {
        if (!(raw instanceof HttpServletRequest request) || !(output instanceof HttpServletResponse response)) { chain.doFilter(raw,output);return; }
        State state=(State)request.getAttribute(ATTRIBUTE);
        if(state==null) {
            // Exact numeric peers only. Proxies must strip public traceparent before forwarding.
            var headers=request.getHeaders("traceparent");
            String parent=headers.hasMoreElements()?headers.nextElement():null;
            if(headers.hasMoreElements())parent=null;
            state=new State(TraceContext.start(parent,peers.contains(request.getRemoteAddr())));
            request.setAttribute(ATTRIBUTE,state);
        }
        response.setHeader("X-Trace-Id",state.trace.traceId());
        response.setHeader("X-Request-Id",state.trace.requestId());
        State captured=state;
        try(var ignored=NativeTrace.open(state.trace)) {
            try { chain.doFilter(request,response); }
            catch(IOException|ServletException|RuntimeException|Error failure) { state.failed=true;throw failure; }
            finally {
                if(request.isAsyncStarted()) {
                    try { request.getAsyncContext().addListener(new AsyncListener() {
                        public void onComplete(AsyncEvent event) { complete(request,response,captured); }
                        public void onTimeout(AsyncEvent event) { captured.failed=true; }
                        public void onError(AsyncEvent event) { captured.failed=true; }
                        public void onStartAsync(AsyncEvent event) { event.getAsyncContext().addListener(this); }
                    }); } catch(IllegalStateException alreadyCompleted) { complete(request,response,state); }
                } else complete(request,response,state);
            }
        }
    }
    private static void complete(HttpServletRequest request,HttpServletResponse response,State state) {
        if(!state.completed.compareAndSet(false,true))return;
        // Matched templates only, never raw URI/query (which may contain user identifiers/secrets).
        Object pattern=request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route=pattern instanceof String value && value.length()<=256 && value.matches("[A-Za-z0-9_/{\\}.*:-]+")?value:"unmatched";
        String method=Set.of("GET","POST","PUT","PATCH","DELETE","HEAD","OPTIONS","TRACE").contains(request.getMethod())?request.getMethod():"OTHER";
        try(var ignored=NativeTrace.open(state.trace)) {
            LOG.info("request_complete method={} route={} status={} durationMs={} failed={} droppedTotal={}",method,route,
                state.failed?Math.max(500,response.getStatus()):response.getStatus(),(System.nanoTime()-state.started)/1_000_000,state.failed,TraceAsyncAppender.droppedCount());
        }
    }
}
