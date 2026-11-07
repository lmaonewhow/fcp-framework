package org.dromara.test;

import fcp.observability.TraceContext;
import org.dromara.fcp.observability.FcpTraceConfiguration;
import org.dromara.fcp.observability.NativeTrace;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Real Boot task auto-configuration, including RuoYi's force mode. No DB/Redis or bean overriding. */
@Tag("prod")
class FcpTraceExecutorContextTest {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withAllowBeanDefinitionOverriding(false)
            .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class))
            .withUserConfiguration(FcpTraceConfiguration.class,AsyncProbeConfiguration.class)
            .withPropertyValues("spring.task.execution.mode=force","spring.task.execution.thread-name-prefix=native-regression-");
    }
    @Test void forcedBootExecutorOwnsReservedNameAndKeepsConfiguredPoolWithAsyncTrace() {
        runner().withPropertyValues("spring.threads.virtual.enabled=false","spring.task.execution.pool.core-size=3",
            "spring.task.execution.pool.max-size=5","spring.task.execution.pool.queue-capacity=7")
            .run(context-> {
                assertNull(context.getStartupFailure(),"Native force mode must start with overriding disabled");
                var executor=context.getBean("applicationTaskExecutor",ThreadPoolTaskExecutor.class);
                assertEquals(3,executor.getCorePoolSize());assertEquals(5,executor.getMaxPoolSize());
                assertEquals(7,executor.getThreadPoolExecutor().getQueue().remainingCapacity());
                assertFalse(context.containsBean("fcpAsyncExecutor"));
                assertEquals("applicationTaskExecutor",context.getBeanFactory().getBeanDefinition("applicationTaskExecutor").getFactoryMethodName());
                verifyAsync(context.getBean(Probe.class),executor);
            });
    }
    @Test void virtualThreadSelectionIsStillOwnedByBoot() {
        // Native builds run on JDK 21; the project's compiler release remains Java 17.
        runner().withPropertyValues("spring.threads.virtual.enabled=true").run(context-> {
            assertNull(context.getStartupFailure());
            var executor=context.getBean("applicationTaskExecutor",SimpleAsyncTaskExecutor.class);
            assertEquals("applicationTaskExecutorVirtualThreads",context.getBeanFactory().getBeanDefinition("applicationTaskExecutor").getFactoryMethodName());
            verifyAsync(context.getBean(Probe.class),executor);
        });
    }
    private static void verifyAsync(Probe probe,AsyncTaskExecutor executor) throws Exception {
        var trace=TraceContext.start(null,false);
        try(var ignored=NativeTrace.open(trace)) {
            Snapshot snapshot=probe.capture().get(5,TimeUnit.SECONDS);
            assertEquals(trace.traceId(),snapshot.trace());assertEquals(trace.traceId(),snapshot.mdc());
            assertTrue(snapshot.thread().startsWith("native-regression-"),"@Async must use Boot's configured executor");
            assertNotEquals(Thread.currentThread().getName(),snapshot.thread());
        }
        assertNull(TraceContext.current());assertNull(MDC.get("traceId"));
        assertNull(executor.submit(TraceContext::current).get(5,TimeUnit.SECONDS));
        assertNull(executor.submit(()->MDC.get("traceId")).get(5,TimeUnit.SECONDS));
    }
    record Snapshot(String trace,String mdc,String thread) { }
    public static class Probe {
        @Async public CompletableFuture<Snapshot> capture() {
            return CompletableFuture.completedFuture(new Snapshot(TraceContext.current().traceId(),MDC.get("traceId"),Thread.currentThread().getName()));
        }
    }
    @Configuration(proxyBeanMethods=false)
    @EnableAsync(proxyTargetClass=true)
    static class AsyncProbeConfiguration {
        @Bean Probe nativeAsyncProbe() { return new Probe(); }
    }
}
