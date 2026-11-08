import fcp.Json;
import fcp.observability.*;
import fcp.release.observation.*;
import fcp.release.routing.DurableReleaseRouter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Real invocation wrapper and async queue; not native/MySQL/browser acceptance. */
public class ObservedInvocationTraceTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static final String PRIVATE="synthetic-private-affinity-token-tenant-user-input";
    private static DurableReleaseRouter.Decision decision(String release,long revision){
        return new DurableReleaseRouter.Decision(release,PRIVATE,revision,PRIVATE,PRIVATE,Instant.now().plusSeconds(60));
    }
    record Completion(String release,long revision,boolean technical,RequestObserver.BusinessOutcome business,boolean unknown) {}
    private static class Observer implements RequestObserver {
        final List<Completion> completed=new ArrayList<>();
        public Ticket begin(String release,long revision){return (technical,business,unknown,nanos)->{
            check(nanos>=0,"Nonnegative original ticket duration");completed.add(new Completion(release,revision,technical,business,unknown));
        };}
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> records(ByteArrayOutputStream bytes,int expected)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<deadline) {
            String output=bytes.toString(StandardCharsets.UTF_8);var records=new ArrayList<Map<String,Object>>();
            for(String line:output.substring(0,output.lastIndexOf('\n')+1).split("\\R"))if(!line.isBlank()) {
                var item=(Map<String,Object>)Json.parse(line);if("release.invocation".equals(item.get("event")))records.add(item);
            }
            if(records.size()>=expected)return records;
            Thread.sleep(10);
        }
        throw new AssertionError("Missing asynchronous release summaries");
    }
    @SuppressWarnings("unchecked") public static void main(String[] args)throws Exception {
        var bytes=new ByteArrayOutputStream();PrintStream previous=System.err;var observer=new Observer();var trace=TraceContext.start(null,false);
        try(var capture=new PrintStream(bytes,true,StandardCharsets.UTF_8)) {
            System.setErr(capture);long accepted=TraceEvents.metrics().get("accepted");
            check("plain".equals(ObservedInvocation.invoke(observer,decision("untraced",0),false,()->"plain",r->RequestObserver.BusinessOutcome.SUCCESS)),"Untraced result unchanged");
            check(TraceEvents.metrics().get("accepted")==accepted&&TraceContext.current()==null,"Untraced call emits no summary or new trace");observer.completed.clear();
            int[] classified={0};
            try(var scope=TraceContext.open(trace)) {
                check("same-plugin-version".equals(ObservedInvocation.invoke(observer,decision("release-blue",7),false,()->"same-plugin-version",r->{classified[0]++;return RequestObserver.BusinessOutcome.SUCCESS;})),"Success result unchanged");
                check(classified[0]==1,"Host classifier called exactly once");
                check("same-plugin-version".equals(ObservedInvocation.invoke(observer,decision("release-green",8),false,()->"same-plugin-version",r->RequestObserver.BusinessOutcome.SUCCESS)),"Same plugin version can select distinct releases");
                ObservedInvocation.invoke(observer,decision("no-classifier",9),false,()->PRIVATE,null);
                ObservedInvocation.invoke(observer,decision("null-classifier",10),false,()->PRIVATE,r->null);
                ObservedInvocation.invoke(observer,decision("business-failure",11),false,()->PRIVATE,r->RequestObserver.BusinessOutcome.FAILURE);
                IOException readFailure=new IOException(PRIVATE);
                try {ObservedInvocation.invoke(observer,decision("read-failure",12),false,()->{throw readFailure;},null);throw new AssertionError("Expected read failure");}
                catch(IOException actual){check(actual==readFailure,"Original read exception identity retained");}
                IOException writeFailure=new IOException(PRIVATE);
                try {ObservedInvocation.invoke(observer,decision("write-unknown",13),true,()->{throw writeFailure;},null);throw new AssertionError("Expected write failure");}
                catch(IOException actual){check(actual==writeFailure,"Unknown write exception identity retained");}
                RuntimeException classifierFailure=new IllegalStateException(PRIVATE);
                try {ObservedInvocation.invoke(observer,decision("classifier-failure",14),true,()->PRIVATE,r->{throw classifierFailure;});throw new AssertionError("Expected classifier failure");}
                catch(RuntimeException actual){check(actual==classifierFailure,"Classifier exception identity retained");}
                ObservedInvocation.invoke(observer,decision("r".repeat(100),9007199254740991L),false,()->PRIVATE,r->RequestObserver.BusinessOutcome.UNKNOWN);
            }
            check(TraceContext.current()==null,"Caller trace scope cleans up");
            var rows=records(bytes,9);check(rows.size()==9,"Exactly one summary per traced invocation");check(observer.completed.size()==9,"Original observer completed exactly once per call");
            for(int i=0;i<rows.size();i++) {
                var row=rows.get(i);var completion=observer.completed.get(i);
                check(trace.traceId().equals(row.get("traceId"))&&trace.requestId().equals(row.get("requestId")),"Selected release retains trace/request correlation");
                check("invoke".equals(row.get("operation")),"Fixed operation");
                var release=(Map<String,Object>)row.get("release");
                check(release.keySet().equals(Set.of("selectedReleaseId","routeRevision","technicalFailure","businessOutcome","unknownResult")),"Closed release fields");
                check(completion.release().equals(release.get("selectedReleaseId")),"Exact selected release including 100 characters");
                check(completion.revision()==((Number)release.get("routeRevision")).longValue(),"Exact persistent route revision");
                check(completion.technical()==(Boolean)release.get("technicalFailure"),"Original technical classification unchanged");
                check(completion.business().name().equals(release.get("businessOutcome")),"Business classification unchanged");
                check(completion.unknown()==(Boolean)release.get("unknownResult"),"Unknown flag unchanged");
                String expected=completion.unknown()||completion.business()==RequestObserver.BusinessOutcome.UNKNOWN?"UNKNOWN":completion.technical()||completion.business()==RequestObserver.BusinessOutcome.FAILURE?"FAILURE":"SUCCESS";
                check(expected.equals(row.get("outcome")),"Exact outcome precedence; unknown never success");
            }
            check(!observer.completed.get(7).technical()&&observer.completed.get(7).unknown(),"Classifier throw retains existing observer semantics");
            check(!bytes.toString(StandardCharsets.UTF_8).contains(PRIVATE),"No affinity, policy, identity, token, input/result or exception text");
            RuntimeException ticketFailure=new IllegalStateException("ticket failure");
            try(var scope=TraceContext.open(trace)) {
                try {ObservedInvocation.invoke((release,revision)->(technical,business,unknown,nanos)->{throw ticketFailure;},decision("ticket-failure",15),false,()->"ok",r->RequestObserver.BusinessOutcome.SUCCESS);throw new AssertionError("Expected ticket failure");}
                catch(RuntimeException actual){check(actual==ticketFailure,"Original ticket failure retained");}
            }
            check(records(bytes,10).size()==10,"Diagnostic finally records without suppressing ticket failure");
            long dropped=TraceEvents.metrics().get("dropped");
            try(var scope=TraceContext.open(trace)){
                check("unchanged".equals(ObservedInvocation.invoke(observer,decision("invalid\nrelease",16),false,()->"unchanged",r->RequestObserver.BusinessOutcome.SUCCESS)),"Invalid diagnostic metadata cannot change return value");
            }
            check(TraceEvents.metrics().get("dropped")==dropped+1,"Invalid diagnostic metadata is counted as dropped");
            long failures=TraceEvents.metrics().get("sinkFailures");
            try(var broken=new PrintStream(OutputStream.nullOutputStream()){@Override public void println(String value){throw new IllegalStateException(PRIVATE);}};var scope=TraceContext.open(trace)){
                System.setErr(broken);
                check("unchanged".equals(ObservedInvocation.invoke(observer,decision("sink-failure",17),false,()->"unchanged",r->RequestObserver.BusinessOutcome.SUCCESS)),"Failing sink cannot change invocation result");
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(TraceEvents.metrics().get("sinkFailures")==failures&&System.nanoTime()<until)Thread.sleep(5);
                check(TraceEvents.metrics().get("sinkFailures")==failures+1,"Asynchronous sink failure is observed");
            }finally{System.setErr(capture);}
        } finally {System.setErr(previous);}
        System.out.println("ObservedInvocationTrace PASS checks="+checks+"; real wrapper/async queue; native/MySQL/browser NOT_RUN");
    }
}
