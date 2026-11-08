import fcp.*;
import fcp.observability.*;
import java.nio.file.*;
import java.util.*;

/** Real stdio RPC and stderr sink, without plugin business work or native/database claims. */
public class TraceRpcBenchmark {
 public static void main(String[] args)throws Exception {
  int warmup=500,count=2000;
  var document=Json.obj("id","benchmark.plugin","version","1.2.3","runtime",Json.obj("launcher","worker","timeout",5));
  var launcher=Json.obj("argv",List.of(args[0],args[1]),"env",Map.of(),"requires",List.of());
  long[] samples=new long[count];
  long elapsed;
  try(var worker=new ProcessProvider(document,Path.of("."),Map.of("worker",launcher))){
   for(int i=0;i<warmup;i++)invoke(worker);
   long start=System.nanoTime();
   for(int i=0;i<count;i++){long before=System.nanoTime();invoke(worker);samples[i]=System.nanoTime()-before;}
   elapsed=System.nanoTime()-start;
  }
  long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
  while(TraceEvents.metrics().get("queued")>0){if(System.nanoTime()>deadline)throw new AssertionError("sink blocked");Thread.sleep(5);}
  Thread.sleep(100); // Driver also verifies exact emitted line count after JVM exit.
  Arrays.sort(samples);
  System.out.println(Json.write(Json.obj("requests",count,"warmupRequests",warmup,"p50Micros",samples[count/2]/1000.0,"p95Micros",samples[(int)(count*.95)-1]/1000.0,
   "p99Micros",samples[(int)(count*.99)-1]/1000.0,"requestsPerSecond",count/(elapsed/1e9),"telemetry",TraceEvents.metrics())));
 }
 static void invoke(ProcessProvider worker){try(var trace=TraceContext.open(TraceContext.start(null,false))){if(!Boolean.TRUE.equals(Schema.map(worker.invoke(null,"benchmark.capability","invoke",Map.of())).get("ok")))throw new AssertionError("RPC result");}}
}
