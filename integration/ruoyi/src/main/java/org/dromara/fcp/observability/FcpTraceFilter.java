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

/** First servlet boundary. Completion belongs to the entire servlet request lifecycle,
 * not to an individual nested FORWARD, ERROR or ASYNC filter invocation.
 * Only explicitly configured socket peers may propagate traceparent; forwarding headers are ignored.
 */
public final class FcpTraceFilter implements Filter {
    public static final String ATTRIBUTE = FcpTraceFilter.class.getName()+".state";
    private static final Logger LOG = LoggerFactory.getLogger("fcp.request");
    private static final Set<String> METHODS=Set.of("GET","POST","PUT","PATCH","DELETE","HEAD","OPTIONS","TRACE");
    private static final java.util.regex.Pattern ROUTE=java.util.regex.Pattern.compile("[A-Za-z0-9_/{\\}.*:-]+");
    private final Set<String> peers;
    public FcpTraceFilter(Set<String> peers) { this.peers=Set.copyOf(peers); }
    private static final class State {
        final TraceContext.Trace trace;
        final long started=System.nanoTime();
        final AtomicBoolean completed=new AtomicBoolean();
        volatile boolean failed;
        volatile boolean asynchronous;
        volatile HttpServletResponse response;
        State(TraceContext.Trace trace) { this.trace=trace; }
    }
    /** Only synchronous completion is authoritative here. Undertow can destroy an async
     * dispatch before the later error response; AsyncListener.onComplete owns that lifecycle.
     */
    public static final class CompletionListener implements ServletRequestListener {
        @Override public void requestDestroyed(ServletRequestEvent event) {
            if(event.getServletRequest() instanceof HttpServletRequest request &&
                    request.getAttribute(ATTRIBUTE) instanceof State state && !state.asynchronous && state.response!=null)
                complete(request,state.response,state);
        }
    }
    @Override public void doFilter(ServletRequest raw, ServletResponse output, FilterChain chain) throws IOException, ServletException {
        if (!(raw instanceof HttpServletRequest request) || !(output instanceof HttpServletResponse response)) { chain.doFilter(raw,output);return; }
        State state=(State)request.getAttribute(ATTRIBUTE);
        if(state==null) {
            var headers=request.getHeaders("traceparent");
            String parent=headers.hasMoreElements()?headers.nextElement():null;
            if(headers.hasMoreElements())parent=null;
            state=new State(TraceContext.start(parent,peers.contains(request.getRemoteAddr())));
            request.setAttribute(ATTRIBUTE,state);
        }
        State captured=state;
        HttpServletResponse traced=new HttpServletResponseWrapper(response) {
            @Override public void reset() { super.reset();headers(this,captured); }
        };
        state.response=traced;
        headers(traced,state);
        // Attach async failure callbacks at startAsync, not after chain return (which can race complete).
        HttpServletRequest wrapped=new HttpServletRequestWrapper(request) {
            private AsyncContext observe(AsyncContext context) {
                // Called only after startAsync succeeds. A rejected start remains synchronous.
                captured.asynchronous=true;
                context.addListener(new AsyncListener() {
                    public void onComplete(AsyncEvent event) { complete(request,captured.response,captured); }
                    public void onTimeout(AsyncEvent event) { captured.failed=true; }
                    public void onError(AsyncEvent event) { captured.failed=true; }
                    public void onStartAsync(AsyncEvent event) { event.getAsyncContext().addListener(this); }
                });
                return context;
            }
            @Override public AsyncContext startAsync() { return observe(super.startAsync(this,traced)); }
            @Override public AsyncContext startAsync(ServletRequest req,ServletResponse res) { return observe(super.startAsync(req,res)); }
        };
        try(var ignored=NativeTrace.open(state.trace)) {
            try { chain.doFilter(wrapped,traced); }
            catch(IOException|ServletException|RuntimeException|Error failure) { state.failed=true;throw failure; }
        }
    }
    private static void headers(HttpServletResponse response,State state) {
        response.setHeader("X-Trace-Id",state.trace.traceId());
        response.setHeader("X-Request-Id",state.trace.requestId());
    }
    private static void complete(HttpServletRequest request,HttpServletResponse response,State state) {
        if(!state.completed.compareAndSet(false,true))return;
        Object pattern=request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route=pattern instanceof String value && value.length()<=256 && ROUTE.matcher(value).matches()?value:"unmatched";
        String method=METHODS.contains(request.getMethod())?request.getMethod():"OTHER";
        Object code=request.getAttribute(NativeBusinessCodeAdvice.ATTRIBUTE);
        Object businessCode=code instanceof Integer?code:"none";
        Object businessFailed=code instanceof Integer value?value!=org.dromara.common.core.domain.R.SUCCESS:"none";
        try(var ignored=NativeTrace.open(state.trace)) {
            LOG.info("request_complete method={} route={} status={} durationMs={} failed={} businessCode={} businessFailed={} droppedTotal={}",method,route,
                response.getStatus(),(System.nanoTime()-state.started)/1_000_000,state.failed||response.getStatus()>=500,businessCode,businessFailed,TraceAsyncAppender.droppedCount());
        }
    }
}
