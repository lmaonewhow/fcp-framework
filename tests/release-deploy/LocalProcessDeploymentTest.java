import fcp.*;
import fcp.control.*;
import fcp.host.*;
import fcp.release.control.*;
import fcp.release.deploy.*;
import fcp.release.routing.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;

public class LocalProcessDeploymentTest {
    static int assertions;
    static void check(boolean v,String message){assertions++;if(!v)throw new AssertionError(message);}
    static int port()throws Exception{try(var socket=new java.net.ServerSocket(0,0,java.net.InetAddress.getLoopbackAddress())){return socket.getLocalPort();}}
    static TrustedContext user(String tenant){return new TrustedContext(UUID.randomUUID().toString(),"trace",tenant,new TrustedContext.Principal(TrustedContext.Kind.USER,"u"),1,Set.of(),Instant.now().plusSeconds(30));}
    static void rejected(Throwing action,String label)throws Exception{try{action.run();throw new AssertionError("Not rejected: "+label);}catch(java.io.IOException|IllegalArgumentException expected){assertions++;}}
    interface Throwing{void run()throws Exception;}
    static final class RestartableJournal implements StateJournal {
        final Path path;FileStateJournal current;
        RestartableJournal(Path path)throws Exception{this.path=path;current=new FileStateJournal(path);}
        void restart()throws Exception{current.close();current=new FileStateJournal(path);}
        public Snapshot load()throws java.io.IOException{return current.load();}
        public Snapshot compareAndSet(long expected,String json)throws java.io.IOException{return current.compareAndSet(expected,json);}
        public void close()throws java.io.IOException{current.close();}
    }
    static long count(URI origin)throws Exception {
        var client=java.net.http.HttpClient.newHttpClient();var response=client.send(java.net.http.HttpRequest.newBuilder(origin.resolve("/fcp/count")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        return Schema.number(Schema.map(Json.parse(response.body())).get("requests"));
    }
    static void knownFailureAllowsAbort(String python,DeploymentProvider.Action failedAction)throws Exception {
        Path temp=Files.createTempDirectory("fcp-known-release-failure-");Path script=Path.of("tests/release-deploy/service.py").toAbsolutePath();
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(script)));byte[] key=new byte[32];Arrays.fill(key,(byte)'x');String a="a".repeat(64),b="b".repeat(64);
        var stable=new LocalProcessDeploymentProvider.ServiceSpec("stable",a,script,digest,List.of(python,"{artifact}"),URI.create("http://127.0.0.1:"+port()),Set.of("/value"));
        var candidate=new LocalProcessDeploymentProvider.ServiceSpec("candidate",b,script,digest,List.of(python,"{artifact}"),URI.create("http://127.0.0.1:"+port()),Set.of("/value"));
        var plan=new ReleasePlan("local","stable","candidate",a,b,7,7,8,7,8);
        try(var state=new FileStateJournal(temp.resolve("executor"));var routes=new FileStateJournal(temp.resolve("routes"));var control=new FileStateJournal(temp.resolve("control"))){
            var router=new DurableReleaseRouter(routes,key,Clock.systemUTC(),Duration.ofMinutes(1));var executor=new LocalProcessDeploymentProvider(state,router,List.of(stable,candidate),"local",Clock.systemUTC(),key);
            try{
                var controller=new ReleaseController(control,executor,(c,p)->{},p->p.requireCompatibleSchema(7,false),Clock.systemUTC());
                var view=controller.createPlan(user("operator"),0,"plan",plan);view=controller.execute(user("operator"),Schema.number(view.get("revision")),"deploy",DeploymentProvider.Action.DEPLOY,0);view=controller.execute(user("operator"),Schema.number(view.get("revision")),"canary",DeploymentProvider.Action.CANARY,1000);
                var record=Schema.map(Schema.map(Schema.map(Json.parse(state.load().json())).get("processes")).get("candidate"));var process=ProcessHandle.of(Schema.number(record.get("pid"))).orElseThrow();process.destroy();process.onExit().get(5,java.util.concurrent.TimeUnit.SECONDS);
                long routeRevision=router.revision();int requested=failedAction==DeploymentProvider.Action.PAUSE?1000:2000;
                view=controller.execute(user("operator"),Schema.number(view.get("revision")),"known-failure",failedAction,requested);
                check(view.get("phase").equals("FAILED"),failedAction+" known failure clears controller pending");
                check(!view.containsKey("pending"),"known failed receipt terminal");check(router.revision()==routeRevision,"known failure performs no route mutation");
                var failed=new DeploymentProvider.Command("known-failure",plan,failedAction,requested,3);
                check(executor.recovery(failed)==DeploymentProvider.RecoveryDisposition.ACCEPTED,"failed command remains accepted immutable receipt");executor.resumeNotAccepted(failed);check(router.revision()==routeRevision,"failed command never reexecuted");
                view=controller.execute(user("operator"),Schema.number(view.get("revision")),"contain",DeploymentProvider.Action.ABORT,0);
                check(view.get("phase").equals("ABORTED"),"ABORT allowed after known failure");check(!router.policy().orElseThrow().enabled(),"actual route stopped after known failure");
                try{executor.invoke(user("t"),null,"POST","/value",new byte[]{1});throw new AssertionError("write after abort");}catch(FcpException expected){check(expected.code.equals("RELEASE_DISABLED"),"new write denied after failure containment");}
            }finally{executor.stopOwnedProcesses();}
        }
    }
    public static void main(String[] args)throws Exception {
        Path temp=Files.createTempDirectory("fcp-process-release-");Path script=Path.of("tests/release-deploy/service.py").toAbsolutePath();
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(script)));byte[] key=new byte[32];Arrays.fill(key,(byte)'x');
        String a="a".repeat(64),b="b".repeat(64);
        var stable=new LocalProcessDeploymentProvider.ServiceSpec("stable",a,script,digest,List.of(args[0],"{artifact}"),URI.create("http://127.0.0.1:"+port()),Set.of("/value"));
        var candidate=new LocalProcessDeploymentProvider.ServiceSpec("candidate",b,script,digest,List.of(args[0],"{artifact}"),URI.create("http://127.0.0.1:"+port()),Set.of("/value"));
        var plan=new ReleasePlan("local","stable","candidate",a,b,7,7,8,7,8);
        var preflightAvailable=new java.util.concurrent.atomic.AtomicBoolean(true);
        try(var state=new FileStateJournal(temp.resolve("executor"));var routes=new FileStateJournal(temp.resolve("routes"));var controllerJournal=new RestartableJournal(temp.resolve("controller"))){
            var router=new DurableReleaseRouter(routes,key,Clock.systemUTC(),Duration.ofMinutes(1));
            var executor=new LocalProcessDeploymentProvider(state,router,List.of(stable,candidate),"local",Clock.systemUTC(),key);
            try {
                check(!executor.capabilities().contains(DeploymentProvider.Action.RETIRE),"unsupported retirement honest");
                DeploymentProvider interruptedBeforeSubmit=new DeploymentProvider(){
                    public Set<Action> capabilities(){return executor.capabilities();}
                    public void submit(Command c)throws java.io.IOException{throw new java.io.IOException("INJECTED_CRASH_BEFORE_EXECUTOR_SUBMIT");}
                    public Optional<Observation> observe(String environment,String id)throws java.io.IOException{return executor.observe(environment,id);}
                };
                var initialController=new ReleaseController(controllerJournal,interruptedBeforeSubmit,(c,p)->{},p->{if(!preflightAvailable.get())throw new java.io.IOException("REGISTRY_UNAVAILABLE_OR_SCHEMA_DIRTY");p.requireCompatibleSchema(7,false);},Clock.systemUTC());
                var controlState=initialController.createPlan(user("operator"),0,"plan",plan);
                long before=Schema.number(controlState.get("revision"));
                rejected(()->initialController.execute(user("operator"),before,"deploy",DeploymentProvider.Action.DEPLOY,0),"crash after controller intent before submit");
                check(executor.observe("local","deploy").isEmpty(),"executor not contacted before interruption");
                controllerJournal.restart();
                var controller=new ReleaseController(controllerJournal,executor,(c,p)->{},p->{if(!preflightAvailable.get())throw new java.io.IOException("REGISTRY_UNAVAILABLE_OR_SCHEMA_DIRTY");p.requireCompatibleSchema(7,false);},Clock.systemUTC());
                controlState=controller.snapshot(user("operator"));check(controlState.containsKey("pending"),"reopened durable controller retains pending intent");
                var pendingDeploy=new DeploymentProvider.Command("deploy",plan,DeploymentProvider.Action.DEPLOY,0,1);
                check(executor.supportsRecovery()&&!executor.supportsFencedContainment(),"explicit safe recovery without unsupported fencing");
                check(executor.recovery(pendingDeploy)==DeploymentProvider.RecoveryDisposition.NOT_ACCEPTED,"authoritative absence from retained executor ledger");
                controlState=controller.recover(user("operator"),Schema.number(controlState.get("revision")));
                check(executor.recovery(pendingDeploy)==DeploymentProvider.RecoveryDisposition.ACCEPTED,"accepted immutable receipt after recovery");
                executor.resumeNotAccepted(pendingDeploy); // Duplicate recovery cannot launch again.
                rejected(()->executor.recovery(new DeploymentProvider.Command("deploy",plan,DeploymentProvider.Action.DEPLOY,0,2)),"receipt exact command digest mismatch");
                check(controlState.get("phase").equals("READY"),"controller confirms actual readiness");
                var deploy=new DeploymentProvider.Command("deploy",plan,DeploymentProvider.Action.DEPLOY,0,1);
                check(executor.observe("local","deploy").orElseThrow().complete(),"actual both child processes healthy");
                check(router.policy().orElseThrow().basisPoints()==0,"stable routing");
                var response=executor.invoke(user("tenant1"),null,"GET","/value",new byte[0]);check(response.status()==200,"signed backend request accepted");check(Schema.map(Json.parse(new String(response.body()))).get("version").equals("stable"),"actual stable body");
                executor.submit(deploy);check(executor.observe("local","deploy").orElseThrow().complete(),"duplicate idempotent");
                rejected(()->executor.submit(new DeploymentProvider.Command("bad-gen",plan,DeploymentProvider.Action.EXPAND,1000,9)),"generation jump");
                controlState=controller.execute(user("operator"),Schema.number(controlState.get("revision")),"canary",DeploymentProvider.Action.CANARY,5000);
                check(controlState.get("phase").equals("CANARY"),"controller actual canary");
                String stableTenant=null,candidateTenant=null;for(int n=0;n<100;n++){String tenant="t"+n;if(router.decide(user(tenant)).releaseId().equals("stable"))stableTenant=tenant;else candidateTenant=tenant;if(stableTenant!=null&&candidateTenant!=null)break;}
                check(stableTenant!=null&&candidateTenant!=null,"both tenant cohorts");
                response=executor.invoke(user(candidateTenant),null,"GET","/value",new byte[0]);check(Schema.map(Json.parse(new String(response.body()))).get("version").equals("candidate"),"actual candidate body");
                var old=router.decide(user(candidateTenant));
                controlState=controller.execute(user("operator"),Schema.number(controlState.get("revision")),"full",DeploymentProvider.Action.PROMOTE,10000);
                check(controlState.get("phase").equals("FULL"),"full release observed before containment");
                preflightAvailable.set(false);
                long stableBefore=count(stable.origin()),candidateBefore=count(candidate.origin());
                var withoutRegistry=new LocalProcessDeploymentProvider(state,router,List.of(),"local",Clock.systemUTC(),key);
                var containmentController=new ReleaseController(controllerJournal,withoutRegistry,(c,p)->{},p->{throw new java.io.IOException("REGISTRY_UNAVAILABLE");},Clock.systemUTC());
                controlState=containmentController.execute(user("operator"),Schema.number(controlState.get("revision")),"abort",DeploymentProvider.Action.ABORT,0);
                check(withoutRegistry.observe("local","abort").orElseThrow().complete(),"containment requires no service registry or readiness");
                check(controlState.get("phase").equals("ABORTED"),"registry failure does not prevent containment");
                check(!router.policy().orElseThrow().enabled(),"abort closes governed ingress");
                check(executor.observe("local","abort").orElseThrow().routingMode()==DeploymentProvider.RoutingMode.STOPPED,"explicit stopped observation");
                try{executor.invoke(user("tenant-after-stop"),null,"POST","/value",new byte[]{1});throw new AssertionError("post accepted after abort");}catch(FcpException e){check(e.code.equals("RELEASE_DISABLED"),"write refused after stop");}
                try{executor.invoke(user("tenant-after-stop"),null,"GET","/value",new byte[0]);throw new AssertionError("read accepted after abort");}catch(FcpException e){check(e.code.equals("RELEASE_DISABLED"),"read refused after stop");}
                check(count(stable.origin())==stableBefore&&count(candidate.origin())==candidateBefore,"no new request reaches stable or candidate after abort");
                long stoppedRevision=Schema.number(controlState.get("revision"));
                rejected(()->controller.execute(user("operator"),stoppedRevision,"unsafe-resume",DeploymentProvider.Action.ROLLBACK,0),"resume requires compatible registry and schema");
                check(!router.policy().orElseThrow().enabled(),"failed resume leaves ingress closed");
                preflightAvailable.set(true);
                // A new executor object recovers durable receipts and verifies PID+startInstant+nonce; it never relaunches.
                var recovered=new LocalProcessDeploymentProvider(state,router,List.of(stable,candidate),"local",Clock.systemUTC(),key);
                check(recovered.observe("local","abort").orElseThrow().complete(),"restart observation verifies persisted containment");
                // Candidate failure must not prevent switching safely back to healthy stable.
                var saved=Schema.map(Json.parse(state.load().json()));var candidateRecord=Schema.map(Schema.map(saved.get("processes")).get("candidate"));
                var candidateHandle=ProcessHandle.of(Schema.number(candidateRecord.get("pid"))).orElseThrow();candidateHandle.destroy();candidateHandle.onExit().get(5,java.util.concurrent.TimeUnit.SECONDS);
                check(!recovered.observe("local","canary").orElseThrow().healthy(),"candidate failure observed");
                var recoveredController=new ReleaseController(controllerJournal,recovered,(c,p)->{},p->{if(!preflightAvailable.get())throw new java.io.IOException("REGISTRY_UNAVAILABLE_OR_SCHEMA_DIRTY");p.requireCompatibleSchema(7,false);},Clock.systemUTC());
                controlState=recoveredController.execute(user("operator"),Schema.number(controlState.get("revision")),"rollback",DeploymentProvider.Action.ROLLBACK,0);
                check(controlState.get("phase").equals("ROLLED_BACK"),"recovered controller actual rollback");
                check(recovered.observe("local","rollback").orElseThrow().complete(),"rollback actual route");
                check(router.decide(user(candidateTenant)).releaseId().equals("stable"),"rollback stable route decision");
                try{router.resume(user(candidateTenant),old.affinity());throw new AssertionError("old candidate affinity accepted");}catch(FcpException expected){assertions++;}
                response=recovered.invoke(user(candidateTenant),null,"GET","/value",new byte[0]);check(Schema.map(Json.parse(new String(response.body()))).get("version").equals("stable"),"actual rollback response");
                rejected(()->recovered.submit(new DeploymentProvider.Command("retire",plan,DeploymentProvider.Action.RETIRE,0,4)),"retire requires application drain protocol");
                var invalid=new LocalProcessDeploymentProvider.ServiceSpec("tamper","c".repeat(64),script,"0".repeat(64),List.of(args[0],"{artifact}"),URI.create("http://127.0.0.1:"+port()),Set.of("/value"));
                try(var other=new FileStateJournal(temp.resolve("other"))){var invalidExecutor=new LocalProcessDeploymentProvider(other,router,List.of(stable,invalid),"local",Clock.systemUTC(),key);var badPlan=new ReleasePlan("local","stable","tamper",a,"c".repeat(64),7,7,8,7,8);rejected(()->invalidExecutor.submit(new DeploymentProvider.Command("invalid",badPlan,DeploymentProvider.Action.DEPLOY,0,1)),"occupied unowned stable process never adopted");}
                try(var badJournal=new FileStateJournal(temp.resolve("bad-digest"))){var badStable=new LocalProcessDeploymentProvider.ServiceSpec("stable",a,script,"0".repeat(64),List.of(args[0],"{artifact}"),URI.create("http://127.0.0.1:"+port()),Set.of("/value"));var badExecutor=new LocalProcessDeploymentProvider(badJournal,router,List.of(badStable,candidate),"local",Clock.systemUTC(),key);var failedCommand=new DeploymentProvider.Command("bad-artifact",plan,DeploymentProvider.Action.DEPLOY,0,1);
                    rejected(()->badExecutor.submit(failedCommand),"artifact digest mismatch rejected before launch");
                    check(badExecutor.recovery(failedCommand)==DeploymentProvider.RecoveryDisposition.ACCEPTED,"accepted unresolved is never absence");
                    badExecutor.resumeNotAccepted(failedCommand);
                    check(!badExecutor.observe("local","bad-artifact").orElseThrow().complete(),"unresolved receipt not reexecuted");
                    check(badExecutor.recovery(new DeploymentProvider.Command("another",plan,DeploymentProvider.Action.DEPLOY,0,1))==DeploymentProvider.RecoveryDisposition.UNKNOWN,"another pending claim prevents absence recovery");}
            }finally{executor.stopOwnedProcesses();}
            check(!executor.observe("local","rollback").orElseThrow().healthy(),"dead processes never healthy");
        }
        knownFailureAllowsAbort(args[0],DeploymentProvider.Action.PAUSE);
        knownFailureAllowsAbort(args[0],DeploymentProvider.Action.EXPAND);
        System.out.println("PASS local process deployment assertions="+assertions+" (real Python child services; not native RuoYi/MySQL/browser acceptance)");
    }
}
