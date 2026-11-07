package fcp.runtime;
import fcp.FcpException;
import java.nio.file.Path;
import java.time.Instant;
/** Explicit reconciliation fixture backed by the actual durable restart journal. */
public final class PersistedBackoff implements AutoCloseable {
 private final InvocationJournal journal;
 private final Instant first=Instant.parse("2026-01-01T00:00:00Z");
 private volatile boolean advance;
 public PersistedBackoff(Path path)throws Exception {
  try(var original=new InvocationJournal(path)){original.reserveRestart("fixture",first,3);}
  journal=new InvocationJournal(path);
 }
 public void attempt(){journal.reserveRestart("fixture",advance?first.plusMillis(250):first,3);}
 public void advance(){advance=true;}
 public void verifyStillProtected(){try{journal.reserveRestart("fixture",first.plusMillis(499),3);throw new AssertionError("Backoff bypassed");}catch(FcpException e){if(!e.code.equals("RESTART_BACKOFF")||e.retryAfterMillis!=1)throw new AssertionError(e);}}
 public void verifyBudgetStillEnforced(){
  journal.reserveRestart("fixture",first.plusMillis(500),3);
  try{journal.reserveRestart("fixture",first.plusMillis(750),3);throw new AssertionError("Restart budget bypassed");}
  catch(FcpException e){if(!e.code.equals("RESTART_BUDGET")||e.retryAfterMillis!=-1)throw new AssertionError(e);}
 }
 public void close()throws Exception{journal.close();}
}
