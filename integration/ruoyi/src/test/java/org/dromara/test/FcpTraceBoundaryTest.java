package org.dromara.test;

import fcp.observability.TraceContext;
import org.dromara.fcp.observability.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.*;
import java.util.Set;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("prod")
class FcpTraceBoundaryTest {
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
            assertTrue(response.getHeader("X-Trace-Id").matches("[a-f0-9]{32}"));
            assertNull(TraceContext.current());assertNull(MDC.get("traceId"));
        }
    }
    @Test void trustedPeerContinuationAndFailureCleanup() {
        var request=new MockHttpServletRequest("GET","/auth/code");request.addHeader("traceparent",PARENT);request.setRemoteAddr("127.0.0.1");
        var response=new MockHttpServletResponse();
        assertThrows(IllegalStateException.class,()->new FcpTraceFilter(Set.of("127.0.0.1")).doFilter(request,response,(req,res)-> { throw new IllegalStateException("private-canary"); }));
        assertEquals(PARENT.substring(3,35),response.getHeader("X-Trace-Id"));assertNull(MDC.get("traceId"));assertNull(TraceContext.current());
    }
    @Test void duplicateParentIsRejectedEvenFromTrustedPeer() throws Exception {
        var request=new MockHttpServletRequest("GET","/auth/code");request.setRemoteAddr("127.0.0.1");
        request.addHeader("traceparent",PARENT);request.addHeader("traceparent",PARENT);
        var response=new MockHttpServletResponse();
        new FcpTraceFilter(Set.of("127.0.0.1")).doFilter(request,response,(req,res)-> { });
        assertNotEquals(PARENT.substring(3,35),response.getHeader("X-Trace-Id"));
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
