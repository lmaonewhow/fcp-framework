import fcp.*;
import fcp.observability.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Fixed release completion fields and bounded async behavior; no native acceptance claim. */
public class TraceReleaseSummaryTest {
 static int checks;
 static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 static List<Map<String,Object>> rows(ByteArrayOutputStream bytes,int expected)throws Exception{
  long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
  for(;;){
   String raw=bytes.toString(StandardCharsets.UTF_8);List<Map<String,Object>> rows=new ArrayList<>();
   for(String line:raw.substring(0,raw.lastIndexOf('\n')+1).split("\n"))if(!line.isBlank())rows.add(Schema.map(Json.parse(line)));
   if(rows.size()>=expected)return rows;
   if(System.nanoTime()>deadline)throw new AssertionError("Missing trace events");Thread.sleep(5);
  }
 }
 public static void main(String[] args)throws Exception{
  PrintStream previous=System.err;var bytes=new ByteArrayOutputStream();
  try(var capture=new PrintStream(bytes,true,StandardCharsets.UTF_8)){
   System.setErr(capture);var before=TraceEvents.metrics();
   TraceEvents.recordReleaseInvocation(null,-1,true,"secret-invalid-business",true,System.nanoTime());
   check(TraceEvents.metrics().equals(before),"No active trace neither records nor rejects metadata");
   var trace=TraceContext.start(null,false);int recorded=0,invalid=0;List<String> expectedOutcomes=new ArrayList<>();
   try(var scope=TraceContext.open(trace)){
    for(boolean technical:List.of(false,true))for(String business:List.of("SUCCESS","FAILURE","UNKNOWN"))for(boolean unknown:List.of(false,true)){
     TraceEvents.recordReleaseInvocation("release-blue",7,technical,business,unknown,System.nanoTime());recorded++;
     expectedOutcomes.add(unknown||business.equals("UNKNOWN")?"UNKNOWN":technical||business.equals("FAILURE")?"FAILURE":"SUCCESS");
    }
    for(String id:List.of("r".repeat(100),"r".repeat(256),"生产 release/β\\\"")){
     TraceEvents.recordReleaseInvocation(id,9007199254740991L,false,"SUCCESS",false,System.nanoTime());recorded++;
    }
    for(String id:Arrays.asList(null,""," ","\u2003","r".repeat(257))){
     TraceEvents.recordReleaseInvocation(id,0,false,"SUCCESS",false,System.nanoTime());invalid++;
    }
    for(char c=0;c<160;c++)if(Character.isISOControl(c)){
     TraceEvents.recordReleaseInvocation("release"+c+"secret",0,false,"SUCCESS",false,System.nanoTime());invalid++;
    }
    for(String business:Arrays.asList(null,"success"," SUCCESS","secret-invalid-business","s".repeat(1000000))){
     TraceEvents.recordReleaseInvocation("release-blue",0,false,business,false,System.nanoTime());invalid++;
    }
    for(long revision:new long[]{-1,9007199254740992L,Long.MAX_VALUE}){
     TraceEvents.recordReleaseInvocation("release-blue",revision,false,"SUCCESS",false,System.nanoTime());invalid++;
    }
    TraceEvents.record("http.request","GET","200",System.nanoTime());recorded++;
    TraceEvents.record("task.exchange","run","OK",System.nanoTime());recorded++;
   }
   var emitted=rows(bytes,recorded);check(emitted.size()==recorded,"Exactly one record per valid traced call");
   for(int i=0;i<15;i++){
    var event=emitted.get(i);check(event.keySet().equals(Set.of("event","operation","outcome","durationNanos","traceId","spanId","parentSpanId","requestId","release")),"Closed release top-level fields");
    check(event.get("event").equals("release.invocation")&&event.get("operation").equals("invoke"),"Fixed event/operation labels");
    check(event.get("traceId").equals(trace.traceId())&&event.get("requestId").equals(trace.requestId()),"Current trace retained");
    var release=Schema.map(event.get("release"));check(release.keySet().equals(Set.of("selectedReleaseId","routeRevision","technicalFailure","businessOutcome","unknownResult")),"Closed release metadata");
    if(i<12)check(event.get("outcome").equals(expectedOutcomes.get(i)),"UNKNOWN then FAILURE then SUCCESS precedence");
    else check(Schema.number(release.get("routeRevision"))==9007199254740991L,"Exact maximum persistent revision");
   }
   check(Schema.map(emitted.get(12).get("release")).get("selectedReleaseId").equals("r".repeat(100)),"Complete 100-character release ID");
   check(Schema.map(emitted.get(13).get("release")).get("selectedReleaseId").equals("r".repeat(256)),"Complete routing domain maximum");
   check(Schema.map(emitted.get(14).get("release")).get("selectedReleaseId").equals("生产 release/β\\\""),"Domain charset retained through JSON escaping");
   for(int i=15;i<17;i++)check(emitted.get(i).keySet().equals(Set.of("event","operation","outcome","durationNanos","traceId","spanId","parentSpanId","requestId")),"HTTP/task field schema unchanged");
   var after=TraceEvents.metrics();check(after.get("accepted")-before.get("accepted")==recorded,"Invalid metadata never enqueued");
   check(after.get("dropped")-before.get("dropped")==invalid,"Invalid metadata safely dropped and counted");
   check(after.get("sinkFailures").equals(before.get("sinkFailures")),"Invalid metadata cannot reach serializer");
   check(!bytes.toString(StandardCharsets.UTF_8).contains("secret"),"Rejected metadata never serialized");
   var generic=new TraceEvents.Event("http.request","GET","200",0,trace.traceId(),trace.spanId(),"",trace.requestId());
   check(generic.release()==null&&generic.rpc()==null,"Eight-argument constructor compatibility");
   var rpc=new TraceEvents.RpcSummary(new TraceEvents.PluginIdentity("plugin","1.0.0"),"cap","NONE");
   var rpcEvent=new TraceEvents.Event("plugin.rpc","invoke","RETURNED",0,trace.traceId(),trace.spanId(),"",trace.requestId(),rpc);
   check(rpcEvent.rpc()==rpc&&rpcEvent.release()==null,"Nine-argument RPC constructor compatibility");
   var releaseEvent=new TraceEvents.Event("release.invocation","invoke","SUCCESS",0,trace.traceId(),trace.spanId(),"",trace.requestId(),null,new TraceEvents.ReleaseMetadata("release-blue",7,false,"SUCCESS",false));
   var started=new CountDownLatch(1);var unblock=new CountDownLatch(1);
   try(var recorder=new TraceEvents.Recorder(2,event->{started.countDown();try{unblock.await();}catch(InterruptedException ignored){}})){
    recorder.record(releaseEvent);check(started.await(5,TimeUnit.SECONDS),"Sink started");long start=System.nanoTime();
    for(int i=0;i<10000;i++)recorder.record(releaseEvent);
    var metrics=recorder.metrics();check(metrics.get("queued")==2&&metrics.get("accepted")==3&&metrics.get("dropped")==9998,"Original bounded full-queue behavior");
    check(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(3),"Stalled sink does not block release producer");unblock.countDown();
   }
  }finally{System.setErr(previous);}
  System.out.println("TraceReleaseSummary PASS checks="+checks+"; fixed schema and bounded diagnostics; native/MySQL/browser NOT_RUN");
 }
}
