package fcp.exchange;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Host-owned cleanup of generated export snapshots only. Starts immediately, then every minute.
 * No plugin capability, HTTP route, arbitrary file path or permission mutation is registered.
 */
public final class ExportRetentionMaintenance implements AutoCloseable {
    private final ScheduledExecutorService executor;
    public ExportRetentionMaintenance(ExchangeService service,Consumer<Exception> failures){this(service,Duration.ofMinutes(1),failures);}
    /** Host/test scheduling interval only; retention is always 24 hours inside ExchangeService. */
    public ExportRetentionMaintenance(ExchangeService service,Duration interval,Consumer<Exception> failures){
        Objects.requireNonNull(service);Objects.requireNonNull(failures);
        if(interval.isNegative()||interval.isZero()||interval.toMillis()<1)throw new IllegalArgumentException("Maintenance interval");
        executor=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"fcp-export-retention");t.setDaemon(true);return t;});
        executor.scheduleWithFixedDelay(()->{try{service.cleanupExpiredExports();}catch(Exception error){failures.accept(error);}},0,interval.toMillis(),TimeUnit.MILLISECONDS);
    }
    public void close(){executor.shutdown();try{if(!executor.awaitTermination(15,TimeUnit.SECONDS))throw new IllegalStateException("Export maintenance still running");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Export maintenance drain interrupted",e);}}
}
