package org.dromara.fcp.observability;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.concurrent.atomic.LongAdder;

/** Bounded best-effort diagnostic output. Queue loss is explicit, never request backpressure. */
public final class TraceAsyncAppender extends AsyncAppender {
    private static final LongAdder DROPPED = new LongAdder();
    public TraceAsyncAppender() { setNeverBlock(true);setDiscardingThreshold(0);setQueueSize(2048); }
    public static long droppedCount() { return DROPPED.sum(); }
    public long getDroppedCount() { return droppedCount(); }
    @Override protected synchronized void append(ILoggingEvent event) {
        if(getRemainingCapacity()==0) { DROPPED.increment();return; }
        super.append(event);
    }
}
