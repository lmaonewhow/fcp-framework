package org.dromara.test;

import fcp.observability.TraceContext;
import org.dromara.fcp.observability.NativeTrace;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.web.server.ErrorPage;
import org.springframework.boot.web.server.ErrorPageRegistrar;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerMapping;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import static org.junit.jupiter.api.Assertions.*;

/** Real Undertow dispatch lifecycle, not MockMvc's simulated dispatches. Test-only servlet endpoints. */
@Tag("prod")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"server.address=127.0.0.1","fcp.trace.trusted-peers="})
@Import(FcpTraceDispatchSpringTest.Fixtures.class)
class FcpTraceDispatchSpringTest {
    @LocalServerPort int port;
    private static final String PREFIX="/__fcp_trace_test/";
    record Event(String text,Map<String,String> mdc) { }
    private static final List<Event> EVENTS=new CopyOnWriteArrayList<>();
    private static final Map<String,Long> FORWARD_LOGS=new ConcurrentHashMap<>();
    private static final Map<String,Boolean> ASYNC_RAW_EMPTY=new ConcurrentHashMap<>();
    private static final Map<String,Boolean> ASYNC_CLEAN=new ConcurrentHashMap<>();
    static long count(String trace) { return EVENTS.stream().filter(e->trace.equals(e.mdc().get("traceId"))).count(); }
    @TestConfiguration(proxyBeanMethods=false)
    static class Fixtures {
        @Bean ServletRegistrationBean<HttpServlet> dispatchFixture() {
            var bean=new ServletRegistrationBean<HttpServlet>(new HttpServlet() {
                @Override protected void service(HttpServletRequest req,HttpServletResponse res) throws ServletException,IOException {
                    String action=req.getRequestURI().substring(PREFIX.length());
                    String trace=TraceContext.current().traceId();
                    if(!trace.equals(MDC.get("traceId")))throw new ServletException("Dispatch scope not bound");
                    req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,PREFIX+"{action}");
                    switch(action) {
                        case "forward" -> {
                            req.getRequestDispatcher(PREFIX+"target").forward(req,res);
                            FORWARD_LOGS.put(trace,count(trace));
                        }
                        case "target" -> { res.setStatus(207);res.getWriter().write("FORWARD:"+trace); }
                        case "error" -> res.sendError(418);
                        case "error-target" -> { res.reset();res.setStatus(422);res.getWriter().write(req.getDispatcherType()+":"+trace); }
                        case "throw" -> throw new ServletException("synthetic-private-error-canary");
                        case "reset" -> { res.reset();res.setStatus(201);res.getWriter().write(trace); }
                        case "async" -> {
                            AsyncContext async=req.startAsync();async.setTimeout(5000);
                            // Servlet AsyncContext.start is not a Spring-managed executor.
                            // Explicit wrapping is required for business code inside its runnable.
                            Runnable work=NativeTrace.wrap(()-> {
                                try {
                                    HttpServletResponse output=(HttpServletResponse)async.getResponse();
                                    output.reset();output.setStatus(202);output.getWriter().write(TraceContext.current().traceId());
                                    async.complete();
                                } catch(IOException failure) { throw new java.io.UncheckedIOException(failure); }
                            });
                            async.start(()-> {
                                ASYNC_RAW_EMPTY.put(trace,TraceContext.current()==null&&MDC.get("traceId")==null);
                                work.run();ASYNC_CLEAN.put(trace,TraceContext.current()==null&&MDC.get("traceId")==null);
                            });
                        }
                        case "async-error" -> {
                            AsyncContext async=req.startAsync();async.setTimeout(5000);
                            async.start(()-> { throw new IllegalStateException("synthetic-private-async-canary"); });
                        }
                        case "async-dispatch" -> {
                            AsyncContext async=req.startAsync();async.setTimeout(5000);
                            async.start(()->async.dispatch(PREFIX+"async-target"));
                        }
                        case "async-target" -> { res.setStatus(209);res.getWriter().write(req.getDispatcherType()+":"+trace); }
                        case "async-timeout" -> {
                            AsyncContext async=req.startAsync();async.setTimeout(100);
                            async.addListener(new AsyncListener() {
                                public void onComplete(AsyncEvent event) { }
                                public void onError(AsyncEvent event) { }
                                public void onStartAsync(AsyncEvent event) { }
                                public void onTimeout(AsyncEvent event) throws IOException {
                                    ((HttpServletResponse)event.getAsyncContext().getResponse()).setStatus(504);
                                    event.getAsyncContext().getResponse().getWriter().write(trace);
                                    event.getAsyncContext().complete();
                                }
                            });
                        }
                        default -> res.sendError(404);
                    }
                }
            },PREFIX+"*");
            bean.setAsyncSupported(true);return bean;
        }
        @Bean ErrorPageRegistrar traceTestErrorPage() {
            return registry->registry.addErrorPages(new ErrorPage(HttpStatus.I_AM_A_TEAPOT,PREFIX+"error-target"));
        }
    }
    @Test void finalCompletionCoversForwardErrorResetAndAsyncWithoutDuplicates() throws Exception {
        EVENTS.clear();FORWARD_LOGS.clear();ASYNC_CLEAN.clear();ASYNC_RAW_EMPTY.clear();
        Logger logger=(Logger)LoggerFactory.getLogger("fcp.request");
        var capture=new AppenderBase<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();EVENTS.add(new Event(event.getFormattedMessage(),Map.copyOf(event.getMDCPropertyMap())));
            }
        };
        capture.setContext(logger.getLoggerContext());capture.start();logger.addAppender(capture);
        try {
            var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            Map<String,Integer> cases=new LinkedHashMap<>();
            cases.put("forward",207);cases.put("error",422);cases.put("reset",201);cases.put("async",202);
            cases.put("async-error",500);cases.put("async-dispatch",209);cases.put("async-timeout",504);cases.put("throw",500);
            Set<String> traces=new HashSet<>();
            for(var scenario:cases.entrySet()) {
                var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+PREFIX+scenario.getKey()))
                    .timeout(Duration.ofSeconds(15)).GET().build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(scenario.getValue().intValue(),response.statusCode(),scenario.getKey());
                String trace=response.headers().firstValue("X-Trace-Id").orElseThrow();assertTrue(trace.matches("[0-9a-f]{32}"));
                assertTrue(traces.add(trace));
                String request=response.headers().firstValue("X-Request-Id").orElseThrow();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(count(trace)==0&&System.nanoTime()<deadline)Thread.sleep(10);
                assertEquals(1,count(trace),scenario.getKey());
                Event event=EVENTS.stream().filter(e->trace.equals(e.mdc().get("traceId"))).findFirst().orElseThrow();
                assertEquals(request,event.mdc().get("requestId"));assertTrue(event.mdc().get("spanId").matches("[0-9a-f]{16}"));
                assertTrue(event.text().contains(" status="+scenario.getValue()+" "),event.text());
                assertTrue(event.text().contains("businessCode=none businessFailed=none"),"Non-native-R bodies have no inferred business result");
                assertFalse(event.text().contains("synthetic-private-error-canary"));
                assertFalse(event.text().contains("synthetic-private-async-canary"));
                if(!scenario.getKey().equals("throw")&&!scenario.getKey().equals("async-error"))
                    assertTrue(event.text().contains("route="+PREFIX+"{action} "),event.text());
                if(scenario.getKey().equals("forward")) {
                    assertEquals("FORWARD:"+trace,response.body());assertEquals(Long.valueOf(0),FORWARD_LOGS.get(trace));
                } else if(scenario.getKey().equals("error"))assertEquals("ERROR:"+trace,response.body());
                else if(scenario.getKey().equals("async-dispatch"))assertEquals("ASYNC:"+trace,response.body());
                else if(scenario.getKey().equals("async")) {
                    assertEquals(trace,response.body());
                    while(!ASYNC_CLEAN.containsKey(trace)&&System.nanoTime()<deadline)Thread.sleep(10);
                    assertEquals(Boolean.TRUE,ASYNC_CLEAN.get(trace));
                    assertEquals(Boolean.TRUE,ASYNC_RAW_EMPTY.get(trace),"Raw container async runnable does not inherit request context");
                }
                if(scenario.getKey().equals("async-timeout")||scenario.getKey().equals("async-error")||scenario.getKey().equals("throw"))assertTrue(event.text().contains("failed=true"));
            }
            Thread.sleep(100);
            for(String trace:traces)assertEquals(1,count(trace),"Listener/dispatch callbacks must not duplicate completion");
            assertNull(TraceContext.current());assertNull(MDC.get("traceId"));
        } finally { logger.detachAppender(capture);capture.stop(); }
    }
}
