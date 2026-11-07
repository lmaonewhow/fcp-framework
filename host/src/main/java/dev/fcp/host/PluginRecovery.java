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
        this(coordinator::knownTenants, tenant -> coordinator.reconcile(identity.apply(tenant),tenant));
    }
    // Package-private seam tests the real scheduler without granting infrastructure authority.
    PluginRecovery(java.util.function.Supplier<java.util.Set<String>> tenants,java.util.function.Consumer<String> reconcile) {
        executor=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"fcp-runtime-recovery");t.setDaemon(true);return t;});
        executor.schedule(new Runnable(){
            public void run(){
                long delay=5000;
                try {
                    for(String tenant:tenants.get()) {
                        try{reconcile.accept(tenant);failures.remove(tenant);}
                        catch(Exception e){
                            String code=e instanceof fcp.FcpException f?f.code:"RECOVERY_UNAVAILABLE";
                            failures.put(tenant,code);
                            // Honor the persisted admission deadline, not a faster global polling loop.
                            // The next reconcile still runs all live authorization and restart-budget checks.
                            if(e instanceof fcp.FcpException f&&"RESTART_BACKOFF".equals(f.code)&&f.retryAfterMillis>0)
                                delay=Math.min(delay,f.retryAfterMillis);
                            System.err.println("FCP recovery failed: "+code+" ("+e.getClass().getSimpleName()+")");
                        }
                    }
                } finally {
                    if(!executor.isShutdown())try{executor.schedule(this,delay,TimeUnit.MILLISECONDS);}catch(RejectedExecutionException ignored){/* Concurrent close. */}
                }
            }
        },0,TimeUnit.MILLISECONDS);
    }
    public Map<String,String> failures(){return Map.copyOf(failures);}
    public void close(){executor.shutdownNow();}
}
