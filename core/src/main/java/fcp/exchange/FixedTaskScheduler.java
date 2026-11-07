package fcp.exchange;

import fcp.host.TrustedContext;
import fcp.observability.TraceContext;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/** Host-owned fixed schedules. Context factory resolves current TASK grants on every tick.
 * No HTTP payload can supply an identity, URL or schedule. Failures are reported, not swallowed.
 * Single-instance only; durable progress/receipts are maintained by ExchangeService.
 */
public final class FixedTaskScheduler implements AutoCloseable {
    public record Schedule(String tenant,String taskPrincipal,String config,Duration interval) {
        public Schedule { if(interval.compareTo(Duration.ofSeconds(1))<0)throw new IllegalArgumentException("Schedule interval"); }
    }
    @FunctionalInterface public interface TaskContexts { TrustedContext authenticate(String tenant,String taskPrincipal); }
    private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"fcp-exchange-tasks");t.setDaemon(true);return t;});
    public FixedTaskScheduler(ExchangeService service,TaskContexts contexts,List<Schedule> schedules,BiConsumer<Schedule,Throwable> failure){
        Objects.requireNonNull(failure);Set<String> seen=new HashSet<>();
        for(Schedule s:schedules){if(!seen.add(s.tenant()+"/"+s.taskPrincipal()+"/"+s.config())){executor.shutdownNow();throw new IllegalArgumentException("Duplicate schedule");}
            executor.scheduleWithFixedDelay(()->{try(var traceScope=TraceContext.open(TraceContext.start(null,false))){try{TrustedContext c=contexts.authenticate(s.tenant(),s.taskPrincipal());if(c.principal().kind()!=TrustedContext.Kind.TASK||!c.principal().id().equals(s.taskPrincipal())||!c.tenantId().equals(s.tenant()))throw new SecurityException("Task context mismatch");service.runTask(c,s.config());}catch(Exception e){failure.accept(s,e);}}},s.interval().toMillis(),s.interval().toMillis(),TimeUnit.MILLISECONDS);
        }
    }
    public void close(){executor.shutdown();try{if(!executor.awaitTermination(15,TimeUnit.SECONDS))throw new IllegalStateException("Task still in flight; retain exchange service until drained");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Task drain interrupted",e);}}
}
