import fcp.observability.*;
import java.util.concurrent.*;
public class TraceRecorderTest {
 public static void main(String[] args)throws Exception {
  var blocked=new CountDownLatch(1);var started=new CountDownLatch(1);
  var recorder=new TraceEvents.Recorder(2,event->{started.countDown();try{blocked.await();}catch(InterruptedException ignored){}});
  var t=TraceContext.start(null,false);var event=new TraceEvents.Event("plugin.rpc","run","OK",1,t.traceId(),t.spanId(),"",t.requestId());
  recorder.record(event);if(!started.await(5,TimeUnit.SECONDS))throw new AssertionError("sink not called");
  long begin=System.nanoTime();for(int i=0;i<100000;i++)recorder.record(event);long duration=System.nanoTime()-begin;
  var metrics=recorder.metrics();if(metrics.get("queued")!=2||metrics.get("dropped")!=99998||metrics.get("accepted")!=3)throw new AssertionError(metrics);
  if(duration>TimeUnit.SECONDS.toNanos(3))throw new AssertionError("blocked request path");
  blocked.countDown();recorder.close();
  System.out.println("TraceRecorder PASS slowSinkRequests=100000 elapsedNanos="+duration+" metrics="+metrics);
 }
}
