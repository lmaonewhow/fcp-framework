import fcp.observability.TraceContext;
import java.util.*;
import java.util.concurrent.*;
public class TraceContextTest {
  static int assertions;
  static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
  public static void main(String[] args)throws Exception {
    String header="00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    var publicTrace=TraceContext.start(header,false);
    check(!publicTrace.traceId().equals(header.substring(3,35)),"public client cannot choose trace");
    var trusted=TraceContext.start(header,true);
    check(trusted.traceId().equals(header.substring(3,35)),"trusted upstream trace preserved");
    check(trusted.parentSpanId().equals("00f067aa0ba902b7"),"parent is upstream span");
    check(!trusted.spanId().equals(trusted.parentSpanId()),"new server span");
    check(trusted.traceparent().endsWith("-00"),"upstream flags do not force export budget");
    for(String bad:List.of("",header.toUpperCase(),header+"garbage",header+", "+header,header.replace("4bf92f3577b34da6a3ce929d0e0e4736","0".repeat(32)),header.replace("00f067aa0ba902b7","0".repeat(16)),header.replaceFirst("00-","ff-"),header.substring(0,54),"x".repeat(8192)))
      check(!TraceContext.start(bad,true).traceId().equals(trusted.traceId()),"reject malformed header");
    check(TraceContext.current()==null,"no implicit thread retention");
    var executor=Executors.newSingleThreadExecutor();
    try(var scope=TraceContext.open(trusted)) {
      var child=TraceContext.child();
      check(child.traceId().equals(trusted.traceId())&&child.parentSpanId().equals(trusted.spanId()),"child relation");
      try(var nested=TraceContext.open(child)){check(TraceContext.current()==child,"nested scope");}
      check(TraceContext.current()==trusted,"nested restore");
      var callback=TraceContext.remoteChild(child.traceparent());
      check(callback.traceId().equals(trusted.traceId())&&callback.parentSpanId().equals(child.spanId()),"same-trace callback extends plugin parent");
      check(callback.requestId().equals(trusted.requestId()),"callback retains server request ID");
      check(TraceContext.remoteChild("00-"+"1".repeat(32)+"-"+"2".repeat(16)+"-00").traceId().equals(trusted.traceId()),"foreign callback cannot replace root");
      var propagated=executor.submit(TraceContext.wrap(()->TraceContext.current().traceId())).get();
      check(propagated.equals(trusted.traceId()),"async propagation");
      executor.submit(TraceContext.wrap((Runnable)()->{throw new IllegalStateException("synthetic");}));
    }
    check(TraceContext.current()==null,"scope cleanup");
    check(executor.submit(()->TraceContext.current()).get()==null,"worker cleanup after exception");
    executor.shutdownNow();
    var ids=new HashSet<String>();for(int i=0;i<10000;i++)check(ids.add(TraceContext.start(null,false).traceId()),"unique root");
    System.out.println("TraceContext PASS assertions="+assertions);
  }
}
