import fcp.*;
import fcp.observability.*;
import java.io.*;
import java.util.*;

/** Metadata boundaries and unchanged generic path; all inputs are synthetic canaries. */
public class TraceRpcSummaryTest {
 static int checks;
 static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
 public static void main(String[] args)throws Exception {
  var identity=new TraceEvents.PluginIdentity("x".repeat(128),"1".repeat(100));
  check(identity.pluginId().length()==128&&identity.pluginVersion().length()==100,"manifest maximum labels preserved");
  var buildVersion=new TraceEvents.PluginIdentity("plugin+secret","1.2.3-rc.1+build.7");
  check(buildVersion.pluginId().equals("other")&&buildVersion.pluginVersion().equals("1.2.3-rc.1+build.7"),"build metadata plus only allowed for version");
  check(new TraceEvents.RpcSummary(identity,"cap+secret","BUSY").capability().equals("other"),"plus not allowed in capability");
  for(String value:Arrays.asList(null,"","invalid?query=secret","line\nbreak","with space","slash/path","unicode名字")){
   var invalid=new TraceEvents.PluginIdentity(value,value);
   check(invalid.pluginId().equals("other")&&invalid.pluginVersion().equals("other"),"invalid metadata replaced whole");
   check(new TraceEvents.RpcSummary(identity,value,value).capability().equals("other"),"invalid capability replaced");
   check(new TraceEvents.RpcSummary(identity,"cap",value).errorCode().equals("OTHER"),"unknown error never copied");
  }
  check(new TraceEvents.PluginIdentity("x".repeat(129),"1".repeat(101)).equals(new TraceEvents.PluginIdentity(null,null)),"overlength metadata not truncated");
  check(new TraceEvents.RpcSummary(identity,"x".repeat(129),"ARBITRARY_SECRET_CODE").capability().equals("other"),"bounded capability");
  check(new TraceEvents.RpcSummary(identity,"cap","ARBITRARY_SECRET_CODE").errorCode().equals("OTHER"),"alphabetic unknown error redacted");
  check(new TraceEvents.RpcSummary(identity,"cap","x".repeat(1000000)).errorCode().equals("OTHER"),"overlength error rejected before hash lookup");
  for(String code:List.of("BUSY","DEADLINE","CANCELLED","TRANSPORT","FRAME_LIMIT","PROTOCOL","CLOSED","RUNNER_EXITED","BUSINESS_RULE","INVALID_INPUT","PLUGIN_REJECTED","INTERNAL_ERROR"))check(new TraceEvents.RpcSummary(identity,"cap",code).errorCode().equals(code),"known error "+code);
  var original=System.err;var bytes=new ByteArrayOutputStream();
  try(var capture=new PrintStream(bytes,true,java.nio.charset.StandardCharsets.UTF_8)){
   System.setErr(capture);long accepted=TraceEvents.metrics().get("accepted");
   TraceEvents.recordRpc(identity,"cap","invoke",true,null,System.nanoTime());
   check(TraceEvents.metrics().get("accepted")==accepted,"no trace no event");
   try(var scope=TraceContext.open(TraceContext.start(null,false))){
    TraceEvents.record("http.request","GET","200",System.nanoTime());
    TraceEvents.recordRpc(identity,"x".repeat(128),"o".repeat(96),false,"NONE",System.nanoTime());
    TraceEvents.recordRpc(new TraceEvents.PluginIdentity(null,null),"x".repeat(129),"o".repeat(97),false,"not-an-error-secret",System.nanoTime());
   }
   long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
   while(bytes.toString(java.nio.charset.StandardCharsets.UTF_8).chars().filter(c->c=='\n').count()<3){if(System.nanoTime()>deadline)throw new AssertionError("sink not drained");Thread.sleep(5);}
   var lines=bytes.toString(java.nio.charset.StandardCharsets.UTF_8).lines().toList();
   var http=Schema.map(Json.parse(lines.get(0)));check(http.keySet().equals(Set.of("event","operation","outcome","durationNanos","traceId","spanId","parentSpanId","requestId")),"HTTP field schema unchanged, no RPC map");
   var maximum=Schema.map(Json.parse(lines.get(1)));check(maximum.get("capability").equals("x".repeat(128))&&maximum.get("operation").equals("o".repeat(96)),"max safe labels preserved");
   check(maximum.get("outcome").equals("ERROR")&&maximum.get("errorCode").equals("OTHER"),"error NONE cannot become success");
   var invalid=Schema.map(Json.parse(lines.get(2)));for(String field:List.of("pluginId","pluginVersion","capability","operation"))check(invalid.get(field).equals("other"),"bounded serialized "+field);
   check(!bytes.toString().contains("secret"),"arbitrary strings never serialized");
  }finally{System.setErr(original);}
  System.out.println("TraceRpcSummary PASS checks="+checks);
 }
}
