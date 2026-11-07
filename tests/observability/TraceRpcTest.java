import fcp.*;
import fcp.observability.TraceContext;
import java.nio.file.*;
import java.util.*;
public class TraceRpcTest {
 static int checks;
 static void check(boolean v,String s){checks++;if(!v)throw new AssertionError(s);}
 public static void main(String[] args)throws Exception {
  for(int i=0;i<2;i++) {
   var document=Json.obj("runtime",Json.obj("launcher","worker","timeout",5,"traceContext","w3c","methods",Map.of()));
   var launcher=Json.obj("argv",List.of(args[i],Path.of("tests/observability/"+(i==0?"python_worker.py":"node_worker.mjs")).toAbsolutePath().toString()),"env",Map.of(),"requires",List.of());
   try(var worker=new ProcessProvider(document,Path.of("."),Map.of("worker",launcher))){
    for(int j=0;j<3;j++) {
     var root=TraceContext.start(null,false);
     try(var scope=TraceContext.open(root)){
      var result=Schema.map(worker.invoke(null,"echo","run",Map.of()));
      check(result.get("traceId").equals(root.traceId()),"cross-language root");
      check(!result.get("parentSpanId").equals(root.spanId()),"RPC host span inserted");
      check(!result.get("spanId").equals(result.get("parentSpanId")),"plugin child span");
      check(result.get("downstream").equals("00-"+root.traceId()+"-"+result.get("spanId")+"-00"),"outbound context");
      check(TraceContext.current()==root,"caller restored");
     }
    }
   }
  }
  check(TraceContext.current()==null,"root cleaned");System.out.println("TraceRpc PASS checks="+checks+" actualPythonAndNode=true");
 }
}
