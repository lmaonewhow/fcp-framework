import fcp.*;import fcp.control.*;import fcp.exchange.*;import fcp.host.*;
import java.io.*;import java.nio.file.*;import java.time.*;import java.util.*;import java.util.concurrent.atomic.AtomicReference;

/** Actual file durability with controlled host Clock. No production time override or business delete. */
public class ExportRetentionTest {
 static int checks;static void check(boolean b){checks++;if(!b)throw new AssertionError("retention check "+checks);}
 static class ClockControl extends Clock {volatile Instant now=Instant.parse("2026-01-01T00:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now;}}
 static class Host implements HostCapabilities {final ClockControl clock=new ClockControl();final List<Audit> audits=new ArrayList<>();public Descriptor descriptor(){return new Descriptor("test","1","fcp.host/1","embedded-single-instance",Map.of());}public Clock clock(){return clock;}public Decision authorize(TrustedContext c,Grant g){return new Decision(true,1);}public void audit(Audit a){audits.add(a);}}
 static class FaultJournal implements StateJournal {final StateJournal real;boolean before,after;FaultJournal(StateJournal r){real=r;}public Snapshot load()throws IOException{return real.load();}public Snapshot compareAndSet(long revision,String json)throws IOException{if(before){before=false;throw new IOException("synthetic-before-CAS");}var s=real.compareAndSet(revision,json);if(after){after=false;throw new IOException("synthetic-uncertain-CAS");}return s;}public void close()throws IOException{real.close();}}
 static TrustedContext ctx(Host h){return new TrustedContext("request","trace","tenant",new TrustedContext.Principal(TrustedContext.Kind.USER,"user"),1,Set.of("class:a"),h.clock.instant().plusSeconds(60));}
 static Map<String,Object> state(StateJournal journal)throws Exception{return Schema.map(Json.parse(journal.load().json()));}
 static Map<String,Object> download(StateJournal journal,String id)throws Exception{return Schema.map(state(journal).get("download:"+id));}
 static void denied(ExchangeService service,Host host,String id){try{service.download(ctx(host),id);throw new AssertionError("Expired download allowed");}catch(FcpException e){check(e.code.equals("DOWNLOAD_DENIED"));}}
 public static void main(String[] args)throws Exception {
 Path root=Files.createTempDirectory("retention-");Host host=new Host();Map<String,Object> business=Map.of("student_code","synthetic-student");var commits=new java.util.concurrent.atomic.AtomicInteger();
 var port=new ExchangeService.ExecutionPort(){public String version(TrustedContext c,String p){return "1:fixture";}public Object call(TrustedContext c,String p,String op,Map<String,Object> input,String key){if(op.equals("exchange.preview"))return Map.of("valid",true,"revision",0);if(op.equals("exchange.commit")){commits.incrementAndGet();return Map.of("committed",true);}return Map.of("rows",List.of(business));}};
 var config=new ExchangeService.Config("fixture","roster","class:a","campus-v1-csv","1","1",Set.of("student_code"),"none","task");
 Map<String,Object> preserved=Map.of("receipt:keep",Map.of("result","retained"),"task:keep",Map.of("cursor","never-skip"),"preview:keep",Map.of("content","unrelated preview"),"audit:keep",Map.of("action","retained"),"business:keep",Map.of("rows",List.of(business)));
 String first,second,uncertain;
 try(var journal=new FaultJournal(new FileStateJournal(root));var service=new ExchangeService(host,port,journal,List.of(config),Map.of())){
   var seed=new LinkedHashMap<String,Object>(preserved);String legacy=UUID.randomUUID().toString();seed.put("download:"+legacy,Map.of("owner","legacy","config","fixture","fields",List.of("student_code"),"rows",List.of(business),"expires",host.clock.instant().plusSeconds(300).toString()));journal.real.compareAndSet(0,Json.write(seed));service.cleanupExpiredExports();
   first=(String)service.export(ctx(host),"fixture",Set.of("student_code")).get("handle");
   check(Instant.parse((String)download(journal,first).get("expires")).equals(host.clock.instant().plusSeconds(86400)));
   host.clock.now=host.clock.now.plusSeconds(3600);check(service.cleanupExpiredExports()==0);check(download(journal,legacy).containsKey("rows"));check(service.download(ctx(host),first).length>0);
   host.clock.now=host.clock.now.plusSeconds(22*3600);second=(String)service.export(ctx(host),"fixture",Set.of("student_code")).get("handle");check(service.cleanupExpiredExports()==0);
   host.clock.now=host.clock.now.plusSeconds(3600);denied(service,host,first);check(service.download(ctx(host),second).length>0);
   journal.before=true;try{service.cleanupExpiredExports();throw new AssertionError();}catch(IOException expected){check(download(journal,first).containsKey("rows"));}
   check(service.cleanupExpiredExports()==2);check(!download(journal,first).containsKey("rows"));check(download(journal,first).containsKey("cleanupAudit"));check(download(journal,first).containsKey("contentDigest"));check(download(journal,second).containsKey("rows"));
   long revision=journal.load().revision();check(service.cleanupExpiredExports()==0);check(revision==journal.load().revision());denied(service,host,first);
   for(var item:preserved.entrySet())check(state(journal).get(item.getKey()).equals(item.getValue()));check(business.get("student_code").equals("synthetic-student"));check(host.audits.size()==4);
   uncertain=(String)service.export(ctx(host),"fixture",Set.of("student_code")).get("handle");host.clock.now=host.clock.now.plusSeconds(86400);journal.after=true;
   try{service.cleanupExpiredExports();throw new AssertionError();}catch(IOException expected){check(!download(journal,uncertain).containsKey("rows"));}
   revision=journal.load().revision();check(service.cleanupExpiredExports()==0);check(revision==journal.load().revision());
 }
 // Restart loads durable tombstones, keeps all receipts/cursors/audits and resumes future cleanup.
 try(var journal=new FaultJournal(new FileStateJournal(root));var service=new ExchangeService(host,port,journal,List.of(config),Map.of())){
   check(service.cleanupExpiredExports()==0);denied(service,host,uncertain);for(var item:preserved.entrySet())check(state(journal).get(item.getKey()).equals(item.getValue()));
   String fresh=(String)service.export(ctx(host),"fixture",Set.of("student_code")).get("handle");host.clock.now=host.clock.now.plusSeconds(86400);var failure=new AtomicReference<Exception>();
   try(var maintenance=new ExportRetentionMaintenance(service,Duration.ofMillis(20),failure::set)){long until=System.nanoTime()+2_000_000_000L;while(download(journal,fresh).containsKey("rows")&&System.nanoTime()<until)Thread.sleep(10);check(!download(journal,fresh).containsKey("rows"));}check(failure.get()==null);
   var pending=service.preview(ctx(host),"fixture","pending",new ByteArrayInputStream("small".getBytes()));
   boolean limited=false;String large="x".repeat(750_000);
   for(int i=0;i<12;i++){var before=journal.load();try{service.preview(ctx(host),"fixture","bounded-"+i,new ByteArrayInputStream(large.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(FcpException expected){check(expected.code.equals("RESOURCE_LIMIT"));check(journal.load().equals(before));limited=true;break;}}
   check(limited);check(journal.load().json().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=6*1024*1024);
   for(int i=0;i<8;i++){try{service.preview(ctx(host),"fixture","fill-"+i,new ByteArrayInputStream("x".repeat(100_000).getBytes()));}catch(FcpException limit){check(limit.code.equals("RESOURCE_LIMIT"));break;}}
   try{service.confirm(ctx(host),(String)pending.get("previewId"),(String)pending.get("digest"),"capacity-commit");throw new AssertionError("Capacity must be checked before commit");}catch(FcpException limit){check(limit.code.equals("RESOURCE_LIMIT"));check(commits.get()==0);}


 }
 System.out.println("PASS "+checks+" export retention checks: 24h, legacy extension, exact expiry, bounded snapshot cleanup, durable audit/receipt/cursor preservation, CAS failures, restart and automatic host maintenance");
 }
}
