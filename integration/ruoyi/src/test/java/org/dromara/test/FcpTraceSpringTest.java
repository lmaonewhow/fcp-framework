package org.dromara.test;

import fcp.observability.TraceContext;
import org.dromara.fcp.observability.NativeTrace;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.task.AsyncTaskExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletRequest;
import org.dromara.common.log.event.LogininforEvent;
import org.dromara.common.core.constant.Constants;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import static org.junit.jupiter.api.Assertions.*;

/** Actual Undertow/servlet filter chain on loopback, backed by native CI's isolated MySQL/Redis. */
@Tag("prod")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"server.address=127.0.0.1","captcha.enable=true","fcp.trace.trusted-peers="})
class FcpTraceSpringTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired DataSource source;
    @Autowired ApplicationEventPublisher events;
    @Autowired @Qualifier("applicationTaskExecutor") AsyncTaskExecutor executor;
    private HttpResponse<String> get(String path) throws Exception {
        try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                .timeout(Duration.ofSeconds(15)).header("traceparent","00-0123456789abcdef0123456789abcdef-0123456789abcdef-01")
                .GET().build(),HttpResponse.BodyHandlers.ofString());
        }
    }
    private String trace(HttpResponse<String> response) {
        String trace=response.headers().firstValue("X-Trace-Id").orElseThrow();
        assertTrue(trace.matches("[a-f0-9]{32}"));
        assertNotEquals("0123456789abcdef0123456789abcdef",trace,"Public traceparent must not set server correlation");
        assertTrue(response.headers().firstValue("X-Request-Id").orElseThrow().matches("[a-f0-9]{32}"));
        return trace;
    }
    @Test void actualNativeCaptchaSucceedsAndUnauthenticatedRoutesRejectWithTrace() throws Exception {
        var captcha=get("/auth/code");String previous=trace(captcha);
        assertEquals(200,captcha.statusCode());
        var body=json.readTree(captcha.body());assertEquals(200,body.path("code").asInt());
        assertTrue(body.path("data").path("captchaEnabled").asBoolean());
        assertFalse(body.path("data").path("uuid").asText().isEmpty());
        assertFalse(body.path("data").path("img").asText().isEmpty());
        for(String path:new String[]{"/system/user/list","/fcp/plugins"}) {
            var rejected=get(path);String current=trace(rejected);assertNotEquals(previous,current);previous=current;
            var rejection=json.readTree(rejected.body());
            assertEquals(401,rejection.path("code").asInt(),"Actual native Sa-Token authentication must reject "+path);
        }
        assertNull(TraceContext.current());
    }
    @Test void nativeAsyncLoginAuditPersistsAndCorrelatesWithoutLoggingUsername() throws Exception {
        // Never publish the synthetic write against a user's existing database.
        try(var connection=source.getConnection()) {
            String url=connection.getMetaData().getURL();
            assertTrue(url.startsWith("jdbc:mysql://"));
            URI endpoint=URI.create(url.substring(5));
            assertTrue(java.util.Set.of("127.0.0.1","localhost","[::1]","::1").contains(endpoint.getHost()),"Audit test requires loopback MySQL");
            assertTrue(connection.getCatalog().matches("fcp_ci_[a-z0-9_]+"),"Audit write is restricted to disposable native CI databases");
        }
        String marker="trace_ci_"+UUID.randomUUID().toString().replace("-","");
        String message="synthetic trace audit verification";
        var trace=TraceContext.start(null,false);
        record Captured(String logger,String text,Map<String,String> mdc,String thread) { }
        var captured=new LinkedBlockingQueue<Captured>();
        var capture=new AppenderBase<ILoggingEvent>() {
            @Override protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                captured.add(new Captured(event.getLoggerName(),event.getFormattedMessage(),
                    Map.copyOf(event.getMDCPropertyMap()),event.getThreadName()));
            }
        };
        Logger audit=(Logger)LoggerFactory.getLogger("fcp.audit");
        Logger login=(Logger)LoggerFactory.getLogger(org.dromara.system.service.impl.SysLogininforServiceImpl.class);
        capture.setContext(audit.getLoggerContext());capture.start();audit.addAppender(capture);login.addAppender(capture);
        try {
            var request=new MockHttpServletRequest("POST","/auth/login");
            request.setRemoteAddr("127.0.0.1");request.addHeader("User-Agent","FCP synthetic native audit test");
            var event=new LogininforEvent();event.setRequest(request);event.setTenantId("000000");
            event.setUsername(marker);event.setStatus(Constants.LOGIN_FAIL);event.setMessage(message);event.setArgs(new Object[0]);
            String publishingThread=Thread.currentThread().getName();
            try(var scope=NativeTrace.open(trace)) { events.publishEvent(event); }
            assertNull(TraceContext.current());assertNull(MDC.get("traceId"));
            Captured linked=null;
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(System.nanoTime()<deadline) {
                Captured line=captured.poll(200,TimeUnit.MILLISECONDS);
                if(line==null)continue;
                assertFalse(line.text().contains(marker),"Do not duplicate the synthetic username into diagnostic logs");
                if(line.logger().equals("fcp.audit") && trace.traceId().equals(line.mdc().get("traceId"))) { linked=line;break; }
            }
            assertNotNull(linked,"Real @Async native audit listener must emit its committed row correlation");
            assertNotEquals(publishingThread,linked.thread(),"Event listener must actually execute asynchronously");
            assertEquals(trace.requestId(),linked.mdc().get("requestId"));assertEquals(trace.spanId(),linked.mdc().get("spanId"));
            var match=java.util.regex.Pattern.compile("native_audit kind=login auditId=([0-9]+)").matcher(linked.text());
            assertTrue(match.matches());long loggedId=Long.parseLong(match.group(1));
            try(var connection=source.getConnection();var query=connection.prepareStatement(
                    "SELECT info_id,status FROM sys_logininfor WHERE tenant_id=? AND user_name=? AND msg=?")) {
                query.setString(1,"000000");query.setString(2,marker);query.setString(3,message);
                try(var rows=query.executeQuery()) {
                    assertTrue(rows.next(),"Native MyBatis listener must persist the synthetic audit row");
                    assertEquals(loggedId,rows.getLong("info_id"));assertEquals(Constants.FAIL,rows.getString("status"));
                    assertFalse(rows.next(),"Exactly one row belongs to this unique synthetic event");
                }
            }
            assertNull(executor.submit(TraceContext::current).get(5,TimeUnit.SECONDS));
            assertNull(executor.submit(()->MDC.get("traceId")).get(5,TimeUnit.SECONDS));
        } finally {
            audit.detachAppender(capture);login.detachAppender(capture);capture.stop();
            // Delete only this test's random marker, never clear the audit table.
            try(var connection=source.getConnection();var cleanup=connection.prepareStatement(
                    "DELETE FROM sys_logininfor WHERE tenant_id=? AND user_name=? AND msg=?")) {
                cleanup.setString(1,"000000");cleanup.setString(2,marker);cleanup.setString(3,message);cleanup.executeUpdate();
            }
        }
    }
    @Test void configuredAsyncExecutorPropagatesThenClears() throws Exception {
        var trace=TraceContext.start(null,false);
        try(var ignored=NativeTrace.open(trace)) {
            assertEquals(trace.traceId(),executor.submit(()->TraceContext.current().traceId()).get(5,TimeUnit.SECONDS));
        }
        assertNull(executor.submit(TraceContext::current).get(5,TimeUnit.SECONDS));
    }
}
