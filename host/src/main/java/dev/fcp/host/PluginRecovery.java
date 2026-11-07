package dev.fcp.host;
import fcp.control.LifecycleCoordinator;
import fcp.host.TrustedContext;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Function;

/** Bounded reconciliation using a dedicated infrastructure identity, never an administrator session. */
public final class PluginRecovery implements AutoCloseable {
    private final ScheduledExecutorService executor;
    private final Map<String,String> failures=new ConcurrentHashMap<>();
    public PluginRecovery(LifecycleCoordinator coordinator,Function<String,TrustedContext> identity) {
        executor=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"fcp-runtime-recovery");t.setDaemon(true);return t;});
        executor.scheduleWithFixedDelay(()->{
            for(String tenant:coordinator.knownTenants()) {
                try{coordinator.reconcile(identity.apply(tenant),tenant);failures.remove(tenant);}
                catch(Exception e){failures.put(tenant,e instanceof fcp.FcpException f?f.code:"RECOVERY_UNAVAILABLE");}
            }
        },0,5,TimeUnit.SECONDS);
    }
    public Map<String,String> failures(){return Map.copyOf(failures);}
    public void close(){executor.shutdownNow();}
}
