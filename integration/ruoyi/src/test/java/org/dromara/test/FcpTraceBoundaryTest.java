package org.dromara.test;

import fcp.observability.TraceContext;
import org.dromara.fcp.observability.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.*;
import java.util.Set;
import java.util.ArrayList;
import java.util.Map;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletResponse;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("prod")
class FcpTraceBoundaryTest {
    private static void finish(MockHttpServletRequest request) {
        new FcpTraceFilter.CompletionListener().requestDestroyed(new ServletRequestEvent(request.getServletContext(),request));
    }
    private static final String PARENT="00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";
    @Test void publicHeadersAreNotTrustedAndEveryNativePathHasTrace() throws Exception {
        for(String path:new String[]{"/auth/login","/auth/code","/system/user/list","/fcp/plugins","/missing"}) {
            var request=new MockHttpServletRequest("POST",path);request.addHeader("traceparent",PARENT);
            request.addHeader("Forwarded","for=127.0.0.1");request.setRemoteAddr("192.0.2.1");
            var response=new MockHttpServletResponse();
            new FcpTraceFilter(Set.of()).doFilter(request,response,(req,res)-> {
                assertNotNull(TraceContext.current());assertEquals(TraceContext.current().traceId(),MDC.get("traceId"));
                assertEquals(TraceContext.current().traceId(),response.getHeader("X-Trace-Id"));
                assertNotEquals(PARENT.substring(3,35),TraceContext.current().traceId());
                response.setStatus(403);
            });
            finish(request);
            assertTrue(response.getHeader("X-Trace-Id").matches("[a-f0-9]{32}"));
            assertNull(TraceContext.current());assertNull(MDC.get("traceId"));
        }
    }
    @Test void trustedPeerContinuationAndFailureCleanup() {
        var request=new MockHttpServletRequest("GET","/auth/code");request.addHeader("traceparent",PARENT);request.setRemoteAddr("127.0.0.1");
        var response=new MockHttpServletResponse();
        assertThrows(IllegalStateException.class,()->new FcpTraceFilter(Set.of("127.0.0.1")).doFilter(request,response,(req,res)-> { throw new IllegalStateException("private-canary"); }));
        response.setStatus(500);finish(request);
        assertEquals(PARENT.substring(3,35),response.getHeader("X-Trace-Id"));assertNull(MDC.get("traceId"));assertNull(TraceContext.current());
    }
    @Test void duplicateParentIsRejectedEvenFromTrustedPeer() throws Exception {
        var request=new MockHttpServletRequest("GET","/auth/code");request.setRemoteAddr("127.0.0.1");
        request.addHeader("traceparent",PARENT);request.addHeader("traceparent",PARENT);
        var response=new MockHttpServletResponse();
        new FcpTraceFilter(Set.of("127.0.0.1")).doFilter(request,response,(req,res)-> { });
        finish(request);
        assertNotEquals(PARENT.substring(3,35),response.getHeader("X-Trace-Id"));
    }
    @Test void nestedForwardAndErrorDoNotCompleteBeforeRequestDestruction() throws Exception {
        Logger logger=(Logger)LoggerFactory.getLogger("fcp.request");
        var lines=new ArrayList<String>();var contexts=new ArrayList<Map<String,String>>();
        var capture=new AppenderBase<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();lines.add(event.getFormattedMessage());contexts.add(Map.copyOf(event.getMDCPropertyMap()));
            }
        };
        capture.setContext(logger.getLoggerContext());capture.start();logger.addAppender(capture);
        try {
            var request=new MockHttpServletRequest("GET","/forward");var response=new MockHttpServletResponse();
            var filter=new FcpTraceFilter(Set.of());
            filter.doFilter(request,response,(outer,output)-> {
                request.setDispatcherType(DispatcherType.FORWARD);
                filter.doFilter(outer,output,(inner,target)->((HttpServletResponse)target).setStatus(207));
                assertTrue(lines.isEmpty(),"Nested FORWARD is not end of request");
                ((HttpServletResponse)output).reset();((HttpServletResponse)output).setStatus(418);
            });
            assertTrue(lines.isEmpty(),"Initial chain return may be followed by ERROR dispatch");
            request.setDispatcherType(DispatcherType.ERROR);
            filter.doFilter(request,response,(req,res)->((HttpServletResponse)res).setStatus(422));
            assertTrue(lines.isEmpty());assertNull(TraceContext.current());assertNull(MDC.get("traceId"));
            String trace=response.getHeader("X-Trace-Id");assertNotNull(trace,"reset must preserve correlation headers");
            var completion=new FcpTraceFilter.CompletionListener();
            var destroyed=new ServletRequestEvent(request.getServletContext(),request);
            completion.requestDestroyed(destroyed);completion.requestDestroyed(destroyed);
            assertEquals(1,lines.size());assertTrue(lines.get(0).contains(" status=422 "));
            assertEquals(trace,contexts.get(0).get("traceId"));
            assertNull(TraceContext.current());assertNull(MDC.get("traceId"));
        } finally { logger.detachAppender(capture);capture.stop(); }
    }
    @Test void pooledWorkCopiesAndRestoresContext() throws Exception {
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try {
            var trace=TraceContext.start(null,false);
            Runnable work;
            try(var ignored=NativeTrace.open(trace)) {
                work=NativeTrace.wrap(()-> { assertEquals(trace,TraceContext.current());assertEquals(trace.traceId(),MDC.get("traceId")); });
            }
            pool.submit(work).get(5,TimeUnit.SECONDS);
            pool.submit(()-> { assertNull(TraceContext.current());assertNull(MDC.get("traceId")); }).get(5,TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
    }
}
