package dev.fcp.host;
import fcp.FcpException;
import fcp.runtime.PersistedBackoff;
import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public final class RecoveryBackoffTest {
 public static void main(String[] args)throws Exception {
  Path root=Files.createTempDirectory("fcp-recovery-test-");
  try(var journal=new PersistedBackoff(root)) {
   CountDownLatch done=new CountDownLatch(1);AtomicInteger attempts=new AtomicInteger();AtomicLong deniedAt=new AtomicLong(),acceptedAt=new AtomicLong();
   try(var recovery=new PluginRecovery(()->Set.of("fixture"),tenant->{
    attempts.incrementAndGet();
    try{journal.attempt();acceptedAt.set(System.nanoTime());done.countDown();}
    catch(FcpException e){if(!e.code.equals("RESTART_BACKOFF")||e.retryAfterMillis!=250)throw new AssertionError(e);deniedAt.set(System.nanoTime());journal.advance();throw e;}
   })) {
    if(!done.await(2,TimeUnit.SECONDS))throw new AssertionError("Persisted backoff was not rescheduled before normal 5-second interval");
    long millis=TimeUnit.NANOSECONDS.toMillis(acceptedAt.get()-deniedAt.get());
    if(millis<250||attempts.get()!=2)throw new AssertionError("Backoff protection/scheduling violated: "+millis+"ms, attempts="+attempts);
    journal.verifyStillProtected();journal.verifyBudgetStillEnforced();
    System.out.println("PASS actual durable restart journal + real recovery scheduler: denied at persisted time, retry after "+millis+"ms, original 250ms guard still enforced; reconcile body is an explicit fixture");
   }
  } finally {try(var files=Files.walk(root)){for(Path path:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(path);}}
 }
}
