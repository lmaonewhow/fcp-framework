import fcp.*;import fcp.control.*;import fcp.host.*;import fcp.plugin.*;import fcp.runtime.*;import fcp.runtime.multiversion.*;import fcp.release.routing.*;
import java.nio.file.*;import java.time.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
/** Actual Python -> host callback. In-memory handler is explicitly not a JDBC transaction test. */
public class ReleaseCommitFenceTest {
 public static void main(String[] args)throws Exception {
  Path root=Files.createTempDirectory("release-commit-fence");var host=new PluginMultiVersionTest.Host();var repo=new VerifiedPackageRepository(root.resolve("packages"),Set.of("test"));var pkg=RealRuntimeTest.install(repo,root,"hostuser","python",false);
  var router=new DurableReleaseRouter(new FileStateJournal(root.resolve("route")),new byte[32],Clock.systemUTC(),Duration.ofMinutes(5));router.put(new ReleasePolicy("p","one","two",0,Set.of(),Set.of(),true,0),0);
  var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);var effects=new AtomicInteger();
  PluginReleaseRuntime.RuntimeFactory factory=(id,scoped)->{var engine=new EngineRuntimePort(scoped,repo,Map.of("python",args[0]),root.resolve("calls"),5);for(var cap:pkg.manifest().requires())engine.registerHostCapability(cap,(source,context,capability,input)->{entered.countDown();try{if(!finish.await(1,TimeUnit.SECONDS))throw new AssertionError("commit fixture timed out");}catch(InterruptedException e){throw new RuntimeException(e);}effects.incrementAndGet();return Map.of("ok",true);});return engine;};
  var admin=PluginMultiVersionTest.ctx("a",TrustedContext.Kind.USER);var task=PluginMultiVersionTest.ctx("a",TrustedContext.Kind.TASK);
  var input=Map.<String,Object>of("dependency","host.storage.put","payload",Map.of("dataScope","tenant:a"));
  try(var runtime=new PluginReleaseRuntime(host,new FileStateJournal(root.resolve("state")),router,factory,(c,v,g)->new HostCapabilities.Decision(true,1),v->repo.resolve(v.pluginId(),v.version(),v.digest()))){
   runtime.register(admin,new PluginReleaseRuntime.Release("one",Map.of("hostuser",new PluginReleaseRuntime.Version("hostuser","1.0.0",pkg.sha256()))),0);runtime.activate(admin,"one",PluginMultiVersionTest.rev(runtime,admin));runtime.pinTask(task,null,"task","hostuser","write",input);
   var pool=Executors.newFixedThreadPool(2);try{
    var call=pool.submit(()->{try{return runtime.invokeTask(task,"task",input);}catch(FcpException e){return e.code;}});
    RealRuntimeTest.check(entered.await(2,TimeUnit.SECONDS),"real task entered controlled host effect");
    var stop=pool.submit(()->router.put(new ReleasePolicy("p","one","two",0,Set.of(),Set.of(),false,1),router.revision()));
    Thread.sleep(100);RealRuntimeTest.check(!stop.isDone(),"STOPPED cannot be acknowledged before admitted commit finishes");finish.countDown();stop.get(2,TimeUnit.SECONDS);call.get(2,TimeUnit.SECONDS);
    RealRuntimeTest.check(effects.get()==1,"exactly one admitted effect committed");PluginMultiVersionTest.denied("RELEASE_DISABLED",()->runtime.invokeTask(task,"task",input));RealRuntimeTest.check(effects.get()==1,"closed epoch cannot start new task host effect");
    router.put(new ReleasePolicy("p","one","two",0,Set.of(),Set.of(),true,2),router.revision());
    runtime.pinTask(task,null,"uncertain-write","hostuser","write",Map.of("crash",true));
    PluginMultiVersionTest.denied("OUTCOME_UNKNOWN",()->runtime.invokeTask(task,"uncertain-write",Map.of("crash",true)));
    String taskKey=Schema.map(runtime.state(admin).get("tasks")).entrySet().stream().filter(e->"uncertain-write".equals(Schema.map(e.getValue()).get("taskId"))).findFirst().orElseThrow().getKey();
    PluginMultiVersionTest.denied("TASK_RECONCILIATION_REQUIRED",()->runtime.cancelTask(admin,taskKey,PluginMultiVersionTest.rev(runtime,admin),"Never cancel an unknown write"));
    RealRuntimeTest.check(Schema.number(runtime.status(admin,"one").get("pendingTasks"))==1,"unknown write remains pending after process crash");
   }finally{finish.countDown();pool.shutdownNow();}
  }
  System.out.println("PASS actual TASK Python callback serialized against emergency route closure; JDBC NOT_RUN");
 }
}
