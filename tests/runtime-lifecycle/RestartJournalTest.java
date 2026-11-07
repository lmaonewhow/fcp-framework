package fcp.runtime;
import fcp.FcpException;import java.nio.file.*;import java.time.Instant;
public class RestartJournalTest {
 public static void main(String[] args)throws Exception {
  Path path=Files.createTempDirectory("fcp-clean-budget-");Instant now=Instant.parse("2026-01-01T00:00:00Z");
  try(var journal=new InvocationJournal(path)){
   journal.reserveRestart("p",now,3);journal.completeCleanSession("p",now);
   try{journal.reserveRestart("p",now.plusMillis(249),3);throw new AssertionError("Clean stop bypassed minimum interval");}catch(FcpException e){if(!e.code.equals("RESTART_BACKOFF")||e.retryAfterMillis!=1)throw e;}
   journal.reserveRestart("p",now.plusMillis(250),3);
  }
  try(var journal=new InvocationJournal(path)){
   journal.reserveRestart("p",now.plusMillis(500),3);journal.reserveRestart("p",now.plusMillis(750),3);
   try{journal.reserveRestart("p",now.plusMillis(1000),3);throw new AssertionError("Unknown attempts lost across journal reopen");}catch(FcpException e){if(!e.code.equals("RESTART_BUDGET"))throw e;}
  }
  System.out.println("PASS controlled-clock actual journal: clean completion retains exact 250ms interval and does not refund unknown attempts across reopen");
 }
}
