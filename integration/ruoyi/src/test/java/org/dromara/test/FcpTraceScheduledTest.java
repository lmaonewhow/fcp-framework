package org.dromara.test;

import fcp.observability.TraceContext;
import org.dromara.fcp.observability.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("prod")
class FcpTraceScheduledTest {
    @Test void oneShotAndCollectionCallsKeepParentWhileRecurringTicksStartNewRoots() throws Exception {
        ScheduledExecutorService raw=Executors.newSingleThreadScheduledExecutor();
        var executor=(ScheduledExecutorService)FcpTraceConfiguration.fcpTraceExecutors()
            .postProcessAfterInitialization(raw,"scheduledExecutorService");
        try {
            var parent=TraceContext.start(null,false);
            var ticks=new LinkedBlockingQueue<String>();
            ScheduledFuture<?> recurring;
            try(var ignored=NativeTrace.open(parent)) {
                assertEquals(parent.traceId(),executor.schedule(()->TraceContext.current().traceId(),0,TimeUnit.MILLISECONDS).get(5,TimeUnit.SECONDS));
                Callable<String> job=()-> { assertEquals(TraceContext.current().traceId(),MDC.get("traceId"));return TraceContext.current().traceId(); };
                var results=executor.invokeAll(List.of(job,job));
                for(var result:results)assertEquals(parent.traceId(),result.get());
                assertEquals(parent.traceId(),executor.invokeAny(List.of(job,job)));
                recurring=executor.scheduleAtFixedRate(()->ticks.add(TraceContext.current().traceId()),0,10,TimeUnit.MILLISECONDS);
            }
            String first=ticks.poll(5,TimeUnit.SECONDS),second=ticks.poll(5,TimeUnit.SECONDS);
            recurring.cancel(false);
            assertNotNull(first);assertNotNull(second);assertNotEquals(parent.traceId(),first);assertNotEquals(first,second);
            assertNull(raw.submit(TraceContext::current).get(5,TimeUnit.SECONDS));
            assertNull(raw.submit(()->MDC.get("traceId")).get(5,TimeUnit.SECONDS));
            var delayed=new LinkedBlockingQueue<String>();
            var delay=executor.scheduleWithFixedDelay(()->delayed.add(TraceContext.current().traceId()),0,10,TimeUnit.MILLISECONDS);
            String third=delayed.poll(5,TimeUnit.SECONDS),fourth=delayed.poll(5,TimeUnit.SECONDS);
            delay.cancel(false);assertNotNull(third);assertNotNull(fourth);assertNotEquals(third,fourth);
        } finally { executor.shutdownNow(); }
    }
}
