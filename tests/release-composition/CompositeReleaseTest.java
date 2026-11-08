import dev.fcp.host.release.CompositeReleaseDeployment;
import fcp.*;
import fcp.control.*;
import fcp.host.*;
import fcp.release.control.*;
import fcp.release.routing.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static fcp.release.control.DeploymentProvider.*;

/** Real child JVM lifecycle and real durable router; component counters/failpoints are explicit test doubles. */
public class CompositeReleaseTest {
 static int checks;
 static void check(boolean value,String name){if(!value)throw new AssertionError(name);checks++;System.out.println("PASS "+name);}
 interface Work {void run()throws Exception;}
 static void denied(Work work,String name)throws Exception {try{work.run();throw new AssertionError(name);}catch(IOException|FcpException expected){check(true,name);}}
 public static class Child {public static void main(String[] a)throws Exception{System.out.println("READY");System.out.flush();Thread.sleep(300000);}}
 static final ReleasePlan PLAN=new ReleasePlan("test","stable","candidate","a".repeat(64),"b".repeat(64),1,1,1,1,1);
 static Command cmd(String id,Action a,int bp,long gen){return new Command(id,PLAN,a,bp,gen);}
 static TrustedContext ctx(String tenant){return new TrustedContext("req","trace",tenant,new TrustedContext.Principal(TrustedContext.Kind.USER,"user"),1,Set.of(),Instant.now().plusSeconds(300));}
 static class Processes implements CompositeReleaseDeployment.Components,AutoCloseable {
  final Map<String,Process> children=new ConcurrentHashMap<>();final Map<String,Long> tasks=new ConcurrentHashMap<>();final Set<String> draining=ConcurrentHashMap.newKeySet();
  volatile boolean failStatus;volatile CountDownLatch entered,release;int starts,stops;
  public void verify(ReleasePlan p)throws IOException{if(entered!=null){entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IOException("test latch timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}}}
  public void prepare(String id)throws IOException{Process p=children.get(id);if(p!=null&&p.isAlive())return;String java=Path.of(System.getProperty("java.home"),"bin/java").toString();p=new ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),Child.class.getName()).redirectError(ProcessBuilder.Redirect.INHERIT).start();children.put(id,p);if(!"READY".equals(new BufferedReader(new InputStreamReader(p.getInputStream())).readLine()))throw new IOException("Child not ready");starts++;draining.remove(id);}
  public CompositeReleaseDeployment.Status status(String id)throws IOException{if(failStatus)throw new IOException("injected status outage");Process p=children.get(id);boolean alive=p!=null&&p.isAlive();return new CompositeReleaseDeployment.Status(alive,!alive,0,tasks.getOrDefault(id,0L));}
  public void beginDrain(String id){draining.add(id);}
  public void stop(String id)throws IOException{Process p=children.get(id);if(p!=null&&p.isAlive()){p.destroy();try{if(!p.waitFor(5,TimeUnit.SECONDS))throw new IOException("Child stop timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}stops++;}}
  public void close()throws IOException{for(String id:children.keySet())stop(id);}
 }
 static class FaultJournal implements StateJournal {
  final StateJournal delegate;volatile String failComplete;
  FaultJournal(StateJournal d){delegate=d;}
  public Snapshot load()throws IOException{return delegate.load();}
  public Snapshot compareAndSet(long r,String json)throws IOException{if(failComplete!=null && "COMPLETE".equals(Schema.map(Schema.map(Schema.map(Json.parse(json)).get("operations")).get(failComplete)).get("phase"))){failComplete=null;throw new IOException("injected receipt loss before durable save");}return delegate.compareAndSet(r,json);}
  public void close()throws IOException{delegate.close();}
 }
 static class Fixture implements AutoCloseable {
  final Path root=Files.createTempDirectory("fcp-composition-");FaultJournal ops=new FaultJournal(new FileStateJournal(root.resolve("ops")));FileStateJournal routes=new FileStateJournal(root.resolve("routes"));final Processes processes=new Processes();DurableReleaseRouter router=new DurableReleaseRouter(routes,new byte[32],Clock.systemUTC(),Duration.ofMinutes(5));
  CompositeReleaseDeployment executor=newExecutor();
  Fixture()throws IOException{}
  CompositeReleaseDeployment newExecutor(){return new CompositeReleaseDeployment(ops,router,processes,"test",(r,g,v)->{});}
  void restart()throws IOException{ops.close();routes.close();ops=new FaultJournal(new FileStateJournal(root.resolve("ops")));routes=new FileStateJournal(root.resolve("routes"));router=new DurableReleaseRouter(routes,new byte[32],Clock.systemUTC(),Duration.ofMinutes(5));executor=newExecutor();}
  Observation observe(String id)throws IOException{return executor.observe("test",id).orElseThrow();}
  void applied(Command c)throws IOException{executor.submit(c);var o=observe(c.operationId());check(o.complete()&&o.outcome()==ExecutionOutcome.SUCCEEDED,c.action()+" observed durable success");}
  public void close()throws IOException{processes.close();ops.close();routes.close();}
 }
 static void lifecycle()throws Exception{try(Fixture f=new Fixture()){
  f.applied(cmd("deploy",Action.DEPLOY,0,1));check(f.processes.starts==2&&f.processes.children.values().stream().allMatch(Process::isAlive),"DEPLOY runs two actual JVM children");
  f.applied(cmd("canary",Action.CANARY,5000,2));Set<String> selected=new HashSet<>();for(int i=0;i<100;i++)selected.add(f.router.decide(ctx("t"+i)).releaseId());check(selected.equals(Set.of("stable","candidate")),"real router directs tenants to both releases");
  f.applied(cmd("pause",Action.PAUSE,5000,3));check(f.router.policy().orElseThrow().basisPoints()==5000,"PAUSE preserves observed allocation");
  f.applied(cmd("promote",Action.PROMOTE,10000,4));check(f.router.decide(ctx("x")).releaseId().equals("candidate"),"PROMOTE actually selects candidate");
  f.applied(cmd("abort",Action.ABORT,10000,5));denied(()->f.router.decide(ctx("x")),"ABORT actually rejects new routing");
  f.applied(cmd("rollback",Action.ROLLBACK,0,6));check(f.router.decide(ctx("x")).releaseId().equals("stable"),"ROLLBACK selects stable without replaying writes");
  f.processes.tasks.put("candidate",1L);Command retire=cmd("retire",Action.RETIRE,0,7);f.executor.submit(retire);check(!f.observe("retire").complete()&&f.processes.children.get("candidate").isAlive()&&f.processes.draining.contains("candidate"),"pending pinned task blocks retirement while process stays alive");
  f.processes.tasks.put("candidate",0L);check(f.observe("retire").complete()&&!f.processes.children.get("candidate").isAlive()&&f.processes.children.get("stable").isAlive(),"drained retirement stops actual candidate only");
 }}
 static void failedStatus()throws Exception{try(Fixture f=new Fixture()){f.applied(cmd("d",Action.DEPLOY,0,1));f.processes.failStatus=true;f.executor.submit(cmd("bad",Action.CANARY,1000,2));var o=f.observe("bad");check(o.complete()&&o.outcome()==ExecutionOutcome.FAILED,"FAILED remains terminal when subsequent component status also throws");f.executor.submit(cmd("contain",Action.ABORT,0,3));check(f.observe("contain").routingMode()==RoutingMode.STOPPED,"status outage cannot block containment");f.processes.failStatus=false;}}
 static void routeReceiptLoss()throws Exception{try(Fixture f=new Fixture()){f.applied(cmd("d",Action.DEPLOY,0,1));f.ops.failComplete="p";Command promote=cmd("p",Action.PROMOTE,10000,2);denied(()->f.executor.submit(promote),"route applied but receipt save fault injected");long revision=f.router.revision();check(f.router.decide(ctx("x")).releaseId().equals("candidate"),"route effect really happened before lost receipt");f.restart();check(f.observe("p").complete(),"fresh executor reconciles INTENT against durable router effect");check(f.router.revision()==revision&&f.processes.starts==2,"reconciliation neither repeats route mutation nor starts children");}}
 static void stopReceiptLoss()throws Exception{try(Fixture f=new Fixture()){f.applied(cmd("d",Action.DEPLOY,0,1));f.ops.failComplete="r";denied(()->f.executor.submit(cmd("r",Action.RETIRE,0,2)),"stop effect before receipt save fault injected");check(!f.processes.children.get("candidate").isAlive(),"child was actually stopped before lost receipt");f.restart();check(f.observe("r").complete()&&f.processes.stops==1,"STOP_INTENT reconciles stopped process without repeating stop");}}
 static void concurrentContainment()throws Exception{try(Fixture f=new Fixture()){f.applied(cmd("d",Action.DEPLOY,0,1));var second=f.newExecutor();f.processes.entered=new CountDownLatch(1);f.processes.release=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(3);try{
  Future<?> older=pool.submit(()->{try{f.executor.submit(cmd("old",Action.PROMOTE,10000,2));}catch(IOException e){throw new UncheckedIOException(e);}});check(f.processes.entered.await(5,TimeUnit.SECONDS),"older route effect reached controlled component barrier");Future<Boolean> routerAvailable=pool.submit(()->{synchronized(f.router){return true;}});check(routerAvailable.get(2,TimeUnit.SECONDS),"component verification does not hold router monitor (avoids runtime/router inversion)");CountDownLatch abortStarted=new CountDownLatch(1);Future<?> abort=pool.submit(()->{abortStarted.countDown();try{second.submit(cmd("abort",Action.ABORT,0,3));}catch(IOException e){throw new UncheckedIOException(e);}});check(abortStarted.await(5,TimeUnit.SECONDS),"second executor attempts simultaneous ABORT");f.processes.release.countDown();older.get(5,TimeUnit.SECONDS);abort.get(5,TimeUnit.SECONDS);check(!f.router.policy().orElseThrow().enabled(),"shared owner lock serializes effects and ABORT wins after old route");f.executor.submit(cmd("old",Action.PROMOTE,10000,2));check(!f.router.policy().orElseThrow().enabled(),"old operation replay cannot reopen sealed router");denied(()->f.router.decide(ctx("x")),"actual decisions remain stopped after concurrent replay");
 }finally{f.processes.release.countDown();pool.shutdownNow();}}}
 static void missedSubmitContainment()throws Exception{try(Fixture f=new Fixture()){
  f.applied(cmd("d",Action.DEPLOY,0,1));
  // The control journal recorded generation 2, but the executor never received it.
  check(f.executor.recovery(cmd("abort-gap",Action.ABORT,0,3))==RecoveryDisposition.NOT_ACCEPTED,"missed containment can recover the original higher generation");
  f.executor.resumeNotAccepted(cmd("abort-gap",Action.ABORT,0,3));check(f.observe("abort-gap").complete(),"recovered containment is actually observed");
  denied(()->f.router.decide(ctx("x")),"ABORT skips provably unreceived generation and closes ingress");
  denied(()->f.executor.submit(cmd("late",Action.PROMOTE,10000,2)),"late missing generation cannot reopen route after containment");
  denied(()->f.executor.submit(cmd("skip-normal",Action.PROMOTE,10000,5)),"ordinary action cannot skip generation");
 }}
 public static void main(String[] args)throws Exception{lifecycle();missedSubmitContainment();failedStatus();routeReceiptLoss();stopReceiptLoss();concurrentContainment();System.out.println("PASS "+checks+" composition assertions; real JVM children/router, injected component counters and receipt faults; native Spring/MySQL/browser and distributed transactions NOT_TESTED");}
}
