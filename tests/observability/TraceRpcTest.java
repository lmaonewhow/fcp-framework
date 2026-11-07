import fcp.*;
import fcp.observability.*;
import fcp.host.*;
import fcp.plugin.*;
import fcp.runtime.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;

/** Real stdio workers, including verified managed-package metadata, not native-host acceptance. */
public class TraceRpcTest {
 static int checks;
 static final String SECRET="synthetic-payload-query-token-principal-error-stderr-canary";
 static void check(boolean v,String s){checks++;if(!v)throw new AssertionError(s);}
 static void rejected(Runnable work,String code){try{work.run();throw new AssertionError("Expected "+code);}catch(FcpException e){check(code.equals(e.code),"preserve error "+code);}}
 static List<Map<String,Object>> events(ByteArrayOutputStream bytes,int expected)throws Exception {
  long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
  for(;;){
   String text=bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
   if(text.contains(SECRET))throw new AssertionError("payload/query/token/principal/message/stderr in telemetry");
   List<Map<String,Object>> events=new ArrayList<>();
   for(String line:text.substring(0,text.lastIndexOf('\n')+1).split("\n"))if(!line.isBlank())events.add(Schema.map(Json.parse(line)));
   if(events.size()>=expected)return events;
   if(System.nanoTime()>deadline)throw new AssertionError("telemetry not drained: "+events.size()+" / "+expected);
   Thread.sleep(10);
  }
 }
 static void summary(Map<String,Object> event,String id,String version,String capability,String operation,String outcome,String code,String traceId){
  check(event.keySet().equals(Set.of("event","operation","outcome","durationNanos","traceId","spanId","parentSpanId","requestId","pluginId","pluginVersion","capability","errorCode")),"fixed field whitelist");
  check(event.get("pluginId").equals(id)&&event.get("pluginVersion").equals(version),"host plugin identity/version");
  check(event.get("capability").equals(capability)&&event.get("operation").equals(operation),"export and operation");
  check(event.get("outcome").equals(outcome)&&event.get("errorCode").equals(code),"safe outcome/error "+code);
  check(event.get("traceId").equals(traceId),"shared trace correlation");
 }
 static Map<String,Object> launcher(String exe,int language){return Json.obj("argv",List.of(exe,Path.of("tests/observability/"+(language==0?"python_worker.py":"node_worker.mjs")).toAbsolutePath().toString()),"env",Map.of(),"requires",List.of());}
 static void legacy(String[] args,ByteArrayOutputStream bytes)throws Exception {
  var root=TraceContext.start(null,false);int expected=0;
  try(var scope=TraceContext.open(root)){
   for(int i=0;i<2;i++) {
    String plugin=i==0?"trace.python":"trace.node",version=i==0?"1.2.3+build.7":"2.0.1",capability=i==0?"scores.compute":"roster.query";
    var document=Json.obj("id",plugin,"version",version,"runtime",Json.obj("launcher","worker","timeout",5,"traceContext","w3c","methods",Map.of()));
    try(var worker=new ProcessProvider(document,Path.of("."),Map.of("worker",launcher(args[i],i)))){
     document.put("id",SECRET);document.put("version",SECRET); // Cached host identity, not a later mutable document.
     for(int j=0;j<3;j++) {
      var result=Schema.map(worker.invoke(null,capability,"invoke",Map.of("payload",SECRET,"context",Map.of("principal",SECRET))));
      check(result.get("traceId").equals(root.traceId()),"cross-language root");
      check(!result.get("parentSpanId").equals(root.spanId()),"RPC host span inserted");
      check(!result.get("spanId").equals(result.get("parentSpanId")),"plugin child span");
      check(result.get("downstream").equals("00-"+root.traceId()+"-"+result.get("spanId")+"-00"),"outbound context");
      check(TraceContext.current()==root,"caller restored");
      summary(events(bytes,++expected).get(expected-1),plugin,version,capability,"invoke","RETURNED","NONE",root.traceId());
     }
     for(String code:List.of("BUSINESS_RULE","INVALID_INPUT","PLUGIN_REJECTED")){
      rejected(()->worker.invoke(null,capability,"invoke",Map.of("mode","error","errorCode",code.equals("PLUGIN_REJECTED")?SECRET:code)),code);
      summary(events(bytes,++expected).get(expected-1),plugin,version,capability,"invoke","ERROR",code,root.traceId());
     }
     Object unknown=worker.invoke(null,capability,"invoke",Map.of("mode","business-unknown"));
     check(Boolean.FALSE.equals(Schema.map(unknown).get("ok")),"unknown business result unchanged");
     summary(events(bytes,++expected).get(expected-1),plugin,version,capability,"invoke","RETURNED","NONE",root.traceId());
     rejected(()->worker.invoke(null,capability,"invoke",Map.of("mode","protocol-error")),"PROTOCOL");
     summary(events(bytes,++expected).get(expected-1),plugin,version,capability,"invoke","ERROR","PROTOCOL",root.traceId());
     rejected(()->worker.invokeBounded(null,capability,"invoke",Map.of("mode","timeout"),250),"DEADLINE");
     summary(events(bytes,++expected).get(expected-1),plugin,version,capability,"invoke","ERROR","DEADLINE",root.traceId());
     worker.close();rejected(()->worker.invoke(null,capability,"invoke",Map.of()),"CLOSED");
     summary(events(bytes,++expected).get(expected-1),plugin,version,capability,"invoke","ERROR","CLOSED",root.traceId());
    }
   }
  }
 }
 static class Host implements HostCapabilities {
  public Descriptor descriptor(){return new Descriptor("trace-test","1","fcp.host/1","embedded-single-instance",Map.of());}
  public Clock clock(){return Clock.systemUTC();}
  public Decision authorize(TrustedContext c,Grant g){return new Decision(true,1);}
  public void audit(Audit event){}
 }
 static void managed(String[] args,ByteArrayOutputStream bytes)throws Exception {
  Path dir=Files.createTempDirectory("fcp-trace-managed-");var repository=new VerifiedPackageRepository(dir.resolve("inventory"),Set.of("test"));
  var root=TraceContext.start(null,false);var context=new TrustedContext(root.requestId(),root.traceId(),"synthetic-tenant",new TrustedContext.Principal(TrustedContext.Kind.USER,SECRET),1,Set.of("tenant:synthetic-tenant"),Instant.now().plusSeconds(30));
  try(var scope=TraceContext.open(root);var runtime=new EngineRuntimePort(new Host(),repository,Map.of("python",args[0],"node",args[1]),dir.resolve("calls"),3)){
   for(int i=0;i<3;i++){
    if(i==2)Thread.sleep(300); // Honor the real persisted minimum restart interval before replacing Python's version.
    String plugin=i%2==0?"managed.python":"managed.node",version=i==0?"3.2.1":i==1?"4.0.2":"3.2.2",entry=i%2==0?"runtime.py":"runtime.mjs";
    var cap=Json.obj("id","scores.compute","version","1.0.0","permission","scores.compute","sideEffects",false,"idempotent",false,"timeoutMillis",2000,"inputSchema",Map.of("type","object"),"outputSchema",Map.of("type","object"));
    var manifest=Json.obj("manifestVersion",1,"pluginId",plugin,"version",version,"publisher","test","contractVersion","1.0","runtime",Json.obj("type","local","entry",entry,"argv",List.of()),"provides",List.of(cap));
    Path zip=dir.resolve(plugin+".zip");
    try(var out=new ZipOutputStream(Files.newOutputStream(zip))){
     for(var file:Map.of("plugin.json",Json.write(manifest).getBytes(java.nio.charset.StandardCharsets.UTF_8),entry,Files.readAllBytes(Path.of("tests/runtime-lifecycle/plugins/"+entry))).entrySet()){
      out.putNextEntry(new ZipEntry(file.getKey()));out.write(file.getValue());out.closeEntry();
     }
    }
    var pkg=repository.install(zip,VerifiedPackageRepository.hash(Files.readAllBytes(zip)),"trace-"+i);
    runtime.verifyInstalledPackage(pkg);runtime.prepare(plugin,version,pkg.sha256());runtime.publish("synthetic-tenant",Set.of(plugin),i+1);
    runtime.invoke(context,plugin,"scores.compute",Map.of("message",SECRET),null,null);
    List<Map<String,Object>> captured=events(bytes,TraceEvents.metrics().get("accepted").intValue());
    var invoked=captured.stream().filter(e->plugin.equals(e.get("pluginId"))&&version.equals(e.get("pluginVersion"))&&"invoke".equals(e.get("operation"))).findFirst().orElseThrow(()->new AssertionError("managed package identity missing"));
    summary(invoked,plugin,version,"scores.compute","invoke","RETURNED","NONE",root.traceId());
    check(captured.stream().anyMatch(e->plugin.equals(e.get("pluginId"))&&version.equals(e.get("pluginVersion"))&&"handshake".equals(e.get("operation"))&&"lifecycle".equals(e.get("capability"))),"managed lifecycle package metadata");
    rejected(()->runtime.invoke(context,plugin,"scores.compute",Map.of("crash",true),null,null),"TRANSPORT");
    captured=events(bytes,TraceEvents.metrics().get("accepted").intValue());
    var failed=captured.stream().filter(e->plugin.equals(e.get("pluginId"))&&version.equals(e.get("pluginVersion"))&&"ERROR".equals(e.get("outcome"))).findFirst().orElseThrow();
    summary(failed,plugin,version,"scores.compute","invoke","ERROR","TRANSPORT",root.traceId());
   }
  }
 }
 public static void main(String[] args)throws Exception {
  var original=System.err;var bytes=new ByteArrayOutputStream();
  try(var capture=new PrintStream(bytes,true,java.nio.charset.StandardCharsets.UTF_8)){
   System.setErr(capture);legacy(args,bytes);managed(args,bytes);events(bytes,TraceEvents.metrics().get("accepted").intValue());check(TraceContext.current()==null,"root cleaned");
  }finally{System.setErr(original);}
  System.out.println("TraceRpc PASS checks="+checks+" actualPythonAndNode=true managedPackages=true");
 }
}
