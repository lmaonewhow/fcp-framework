import fcp.observability.TraceContext;
import java.util.*;
public class TraceBenchmark {
 static volatile Object consume;
 public static void main(String[] args) {
  for(int i=0;i<20000;i++)consume=TraceContext.start(null,false);
  long[] samples=new long[9];int operations=100000;
  for(int r=0;r<samples.length;r++){long start=System.nanoTime();for(int i=0;i<operations;i++)try(var scope=TraceContext.open(TraceContext.start(null,false))){consume=TraceContext.current().traceparent();}samples[r]=System.nanoTime()-start;}
  Arrays.sort(samples);
  System.out.printf(java.util.Locale.ROOT,"{\"scope\":\"root generation + attach + serialize + cleanup microbenchmark, no HTTP/database/exporter\",\"operationsPerRound\":%d,\"rounds\":%d,\"medianNanosPerOperation\":%.1f,\"maxRoundNanosPerOperation\":%.1f,\"productionPerformance\":\"NOT_RUN\"}%n",operations,samples.length,(double)samples[4]/operations,(double)samples[8]/operations);
 }
}
