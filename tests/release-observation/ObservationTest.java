import fcp.*;
import fcp.control.*;
import fcp.host.*;
import fcp.release.control.*;
import fcp.release.deploy.*;
import fcp.release.routing.*;
import fcp.release.observation.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;

public class ObservationTest {
    static int checks;
    static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
    interface Throwing{void run()throws Exception;}
    static void reject(Throwing r,String m)throws Exception{try{r.run();throw new AssertionError(m);}catch(IllegalArgumentException|IllegalStateException|FcpException expected){checks++;}}
    static class Time extends Clock {Instant now=Instant.now();public Instant instant(){return now;}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}void add(long seconds){now=now.plusSeconds(seconds);}}
    static TrustedContext user(Time time,String tenant){return new TrustedContext(UUID.randomUUID().toString(),"trace",tenant,new TrustedContext.Principal(TrustedContext.Kind.USER,"u"),1,Set.of(),time.instant().plusSeconds(60));}
    static int port()throws Exception{try(var s=new java.net.ServerSocket(0,0,InetAddress.getLoopbackAddress())){return s.getLocalPort();}}
    static void collector()throws Exception {
        Time t=new Time();WindowedObservation o=new WindowedObservation(t,100);o.bind("r",1,1);
        for(int i=1;i<=100;i++)o.begin("r",1).complete(false,RequestObserver.BusinessOutcome.SUCCESS,false,i*1_000_000L);
        t.add(2);var w=o.closeWindow("r",1);check(w.complete(),"exact window complete");check(w.sample().p95Millis()==95,"nearest rank p95 not average");check(w.sample().requests()==100,"all completed counted");
        check(o.closeWindow("r",1).sample().requests()==0,"closed samples never reused");
        o.begin("r",1).complete(false,RequestObserver.BusinessOutcome.UNKNOWN,false,1);check(!o.closeWindow("r",1).complete(),"missing business signal incomplete");
        var ticket=o.begin("r",1);check(!o.closeWindow("r",1).complete(),"inflight prevents passing window");ticket.complete(false,RequestObserver.BusinessOutcome.SUCCESS,false,1);check(o.closeWindow("r",1).sample().requests()==0,"late completion cannot contaminate next window");
        for(int i=0;i<101;i++)o.begin("r",1).complete(false,RequestObserver.BusinessOutcome.SUCCESS,false,1);check(!o.closeWindow("r",1).complete(),"overflow fail closed");
        reject(()->o.begin("r",2),"unbound generation refused");reject(()->o.bind("r",2,1),"binding cannot change");
        Path incidentPath=Files.createTempDirectory("release-incidents-");
        try(var incidentJournal=new FileStateJournal(incidentPath)){var durable=new WindowedObservation(t,10,incidentJournal);durable.bind("persistent",4,4);durable.securityViolation("persistent",4);}
        try(var incidentJournal=new FileStateJournal(incidentPath)){var restarted=new WindowedObservation(t,10,incidentJournal);check(restarted.confirmedSecurityViolation("persistent",4),"durable security event survives collector restart without samples");check(!restarted.confirmedSecurityViolation("persistent",5),"durable incident generation scoped");}
        o.securityViolation("r",1);check(o.confirmedSecurityViolation("r",1),"security latched");check(o.closeWindow("r",1).sample().securityViolation(),"security survives window close");check(!o.confirmedSecurityViolation("r",2),"security generation scoped");
    }
    static class CrashJournal implements StateJournal {
        final Path path;FileStateJournal inner;boolean crash;
        CrashJournal(Path p)throws Exception{path=p;inner=new FileStateJournal(p);}
        public Snapshot load()throws java.io.IOException{return inner.load();}
        public Snapshot compareAndSet(long rev,String json)throws java.io.IOException{var s=inner.compareAndSet(rev,json);if(crash&&Schema.map(Json.parse(json)).containsKey("pending")){crash=false;throw new AssertionError("INJECTED_AFTER_POLICY_INTENT");}return s;}
        void restart()throws Exception{inner.close();inner=new FileStateJournal(path);}
        public void close()throws java.io.IOException{inner.close();}
    }
    static class Fixture implements AutoCloseable {
        final Time time=new Time();final WindowedObservation metrics=new WindowedObservation(time,1000);final Path temp=Files.createTempDirectory("fcp-observation-");
        final FileStateJournal execution=new FileStateJournal(temp.resolve("execution")),routeState=new FileStateJournal(temp.resolve("routes")),control=new FileStateJournal(temp.resolve("control"));
        final CrashJournal autoState=new CrashJournal(temp.resolve("automation"));
        final DurableReleaseRouter router;final LocalProcessDeploymentProvider executor;final ReleaseController releases;DurableRolloutAutomation automation;
        Fixture(String python)throws Exception {
            byte[] key=new byte[32];Arrays.fill(key,(byte)'x');Path script=Path.of("tests/release-observation/service.py").toAbsolutePath();String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(script)));
            var stable=new LocalProcessDeploymentProvider.ServiceSpec("stable","a".repeat(64),script,hash,List.of(python,"{artifact}"),URI.create("http://127.0.0.1:"+port()),Set.of("/success","/failure","/missing"));
            var candidate=new LocalProcessDeploymentProvider.ServiceSpec("candidate","b".repeat(64),script,hash,List.of(python,"{artifact}"),URI.create("http://127.0.0.1:"+port()),Set.of("/success","/failure","/missing"));
            router=new DurableReleaseRouter(routeState,key,time,Duration.ofMinutes(1));executor=new LocalProcessDeploymentProvider(execution,router,List.of(stable,candidate),"test",time,key,metrics);
            releases=new ReleaseController(control,executor,(c,p)->{},p->p.requireCompatibleSchema(7,false),time);automation=new DurableRolloutAutomation(autoState,releases,metrics,(c,p)->{},time);
            var state=releases.createPlan(operator(),0,"plan",new ReleasePlan("test","stable","candidate","a".repeat(64),"b".repeat(64),7,7,8,7,8));
            state=releases.execute(operator(),Schema.number(state.get("revision")),"deploy",DeploymentProvider.Action.DEPLOY,0);
            releases.execute(operator(),Schema.number(state.get("revision")),"canary",DeploymentProvider.Action.CANARY,1000);
        }
        TrustedContext operator(){return user(time,"operator");}
        String candidateTenant()throws Exception{for(int i=0;i<100000;i++)if(router.decide(user(time,"t"+i)).releaseId().equals("candidate"))return "t"+i;throw new AssertionError();}
        void requests(String path,int n)throws Exception{String tenant=candidateTenant();for(int i=0;i<n;i++){var r=executor.invoke(user(time,tenant),null,"POST",path,new byte[0]);check(r.status()==200,"actual signed service HTTP200");}}
        void enable()throws Exception{automation.enable(operator(),Schema.number(automation.snapshot(operator()).get("revision")),new DurableRolloutAutomation.Policy(List.of(2000,10000),Duration.ofSeconds(2),new MetricsGate.Limits(3,Duration.ofSeconds(30),.1,10000)));}
        public void close()throws Exception{executor.stopOwnedProcesses();autoState.close();control.close();routeState.close();execution.close();}
    }
    static void flows(String python)throws Exception {
        try(var f=new Fixture(python)) {
            f.requests("/success",3);f.enable();check(f.automation.tick(f.operator()).get("status").equals("ENABLED"),"minimum dwell does not advance");
            f.time.add(2);f.automation.tick(f.operator());check(f.releases.snapshot(f.operator()).get("phase").equals("PAUSED"),"preapproval samples discarded; low sample pauses");
            f.requests("/success",4);f.time.add(3);f.automation.tick(f.operator());check(f.releases.snapshot(f.operator()).get("phase").equals("PAUSED"),"paused never automatically resumes");
        }
        try(var f=new Fixture(python)) {
            f.enable();f.requests("/success",4);f.time.add(2);f.autoState.crash=true;
            try{f.automation.tick(f.operator());throw new AssertionError("crash not injected");}catch(AssertionError expected){check(expected.getMessage().equals("INJECTED_AFTER_POLICY_INTENT"),"after policy intent interruption");}
            check(Schema.number(f.releases.snapshot(f.operator()).get("basisPoints"))==1000,"no deployment before persisted policy intent recovery");
            f.autoState.restart();f.automation=new DurableRolloutAutomation(f.autoState,f.releases,f.metrics,(c,p)->{},f.time);
            f.automation.tick(f.operator());check(Schema.number(f.releases.snapshot(f.operator()).get("basisPoints"))==2000,"same immutable step recovered and actually expanded");
            f.requests("/success",4);f.time.add(2);f.automation.tick(f.operator());check(f.releases.snapshot(f.operator()).get("phase").equals("FULL"),"actual full rollout");check(f.automation.snapshot(f.operator()).get("status").equals("COMPLETE"),"policy terminal complete");
        }
        try(var f=new Fixture(python)) {
            f.enable();f.requests("/failure",4);f.time.add(2);f.automation.tick(f.operator());check(f.releases.snapshot(f.operator()).get("phase").equals("ABORTED"),"HTTP200 business failure causes actual abort");
            check(!f.router.policy().orElseThrow().enabled(),"ingress actually closed");reject(()->f.executor.invoke(user(f.time,"x"),null,"POST","/success",new byte[0]),"writes denied after metrics abort");
        }
        try(var f=new Fixture(python)) {
            f.enable();f.requests("/missing",4);f.time.add(2);f.automation.tick(f.operator());check(f.releases.snapshot(f.operator()).get("phase").equals("PAUSED"),"missing business signal never success evidence");
        }
        try(var f=new Fixture(python)) {
            f.enable();ObservationProvider unavailable=new ObservationProvider(){public Window closeWindow(String id,long gen)throws java.io.IOException{throw new java.io.IOException("SOURCE_DOWN");}public boolean confirmedSecurityViolation(String id,long gen)throws java.io.IOException{throw new java.io.IOException("SOURCE_DOWN");}};
            f.automation=new DurableRolloutAutomation(f.autoState,f.releases,unavailable,(c,p)->{},f.time);
            f.automation.tick(f.operator());check(f.releases.snapshot(f.operator()).get("phase").equals("PAUSED"),"unavailable source causes actual pause");check(f.automation.snapshot(f.operator()).get("status").equals("PAUSED"),"reset failure cannot lose pause outcome");
        }
        try(var f=new Fixture(python)) {
            f.enable();var other=new TrustedContext("r","t","other",new TrustedContext.Principal(TrustedContext.Kind.USER,"u"),1,Set.of(),f.time.instant().plusSeconds(60));reject(()->f.automation.tick(other),"different tenant cannot use existing automation approval");
            var expired=user(f.time,"operator");f.time.add(61);reject(()->f.automation.tick(expired),"expired user context not stored or replayed");
        }
        try(var f=new Fixture(python)) {
            f.enable();f.metrics.securityViolation("candidate",2);f.automation.tick(f.operator());check(f.releases.snapshot(f.operator()).get("phase").equals("ABORTED"),"confirmed security abort before dwell with zero samples");
            var task=new TrustedContext("r","t","operator",new TrustedContext.Principal(TrustedContext.Kind.TASK,"task"),1,Set.of(),f.time.instant().plusSeconds(30));reject(()->f.automation.tick(task),"no blanket TASK release privilege");
        }
    }
    public static void main(String[] args)throws Exception{collector();flows(args[0]);System.out.println("PASS "+checks+" observation assertions; real two processes, HTTP business failure, durable policy restart; native/MySQL/browser NOT_RUN");}
}
