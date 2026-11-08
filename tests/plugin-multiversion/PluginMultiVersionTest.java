import fcp.*;import fcp.control.*;import fcp.host.*;import fcp.runtime.*;import fcp.runtime.multiversion.*;import fcp.plugin.*;import fcp.release.routing.*;
import java.nio.file.*;import java.time.*;import java.util.*;import java.util.concurrent.*;

public class PluginMultiVersionTest {
 static int checks;static void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
 interface Run{void run()throws Exception;}static void denied(String code,Run r)throws Exception{checks++;try{r.run();throw new AssertionError("Expected "+code);}catch(FcpException e){if(!e.code.equals(code))throw new AssertionError("Expected "+code+" got "+e.code,e);}}
 static class Host implements HostCapabilities {boolean deny;public Descriptor descriptor(){return new Descriptor("fixture","1","fcp.host/1","embedded-single-instance",Map.of());}public Clock clock(){return Clock.systemUTC();}public Decision authorize(TrustedContext c,Grant g){return new Decision(!deny,1);}public void audit(Audit event){}}
 static TrustedContext ctx(String tenant,TrustedContext.Kind kind){return new TrustedContext(UUID.randomUUID().toString(),"trace",tenant,new TrustedContext.Principal(kind,kind==TrustedContext.Kind.USER?"user":"job"),1,Set.of(),Instant.now().plusSeconds(60));}
 static long rev(PluginReleaseRuntime r,TrustedContext c)throws Exception{return Schema.number(r.state(c).get("revision"));}
 public static void main(String[] args)throws Exception {
  Path root=Files.createTempDirectory("plugin-multiversion"),input=Path.of(args[0]);Host host=new Host();Set<String> grants=ConcurrentHashMap.newKeySet();
  var repo=new VerifiedPackageRepository(root.resolve("packages"),Set.of("test"));Map<String,PackageInstaller.InstalledPackage> packages=new HashMap<>();
  for(String name:List.of("ci.python","ci.node","ci.python-1.1.0","ci.python-1.2.0")){Path file=input.resolve(name+".zip");packages.put(name,repo.stageOnly(file,VerifiedPackageRepository.hash(Files.readAllBytes(file)),name));}
  var one=packages.get("ci.python");var two=packages.get("ci.python-1.1.0");var node=packages.get("ci.node");
  var v1=new PluginReleaseRuntime.Version("ci.python","1.0.0",one.sha256());var v2=new PluginReleaseRuntime.Version("ci.python","1.1.0",two.sha256());var vn=new PluginReleaseRuntime.Version("ci.node","1.0.0",node.sha256());
  var r1=new PluginReleaseRuntime.Release("release1",Map.of("ci.python",v1,"ci.node",vn));var r2=new PluginReleaseRuntime.Release("release2",Map.of("ci.python",v2,"ci.node",vn));
  grants.add(one.sha256());grants.add(node.sha256());byte[] key=new byte[32];Arrays.fill(key,(byte)42);
  var router=new DurableReleaseRouter(new FileStateJournal(root.resolve("route")),key,Clock.systemUTC(),Duration.ofMinutes(5));router.put(new ReleasePolicy("plugins","release1","release2",0,Set.of("b"),Set.of(),true,0),0);
  TrustedContext admin=ctx("000000",TrustedContext.Kind.USER),a=ctx("a",TrustedContext.Kind.USER),b=ctx("b",TrustedContext.Kind.USER),task=ctx("a",TrustedContext.Kind.TASK);String oldAffinity;long firstPid,secondPid;
  PluginReleaseRuntime.RuntimeFactory factory=(id,scoped)->new EngineRuntimePort(scoped,repo,Map.of("python",args[1],"node",args[2]),root.resolve("calls-"+id),20);
  PluginReleaseRuntime.VersionAuthorization auth=(c,v,g)->new HostCapabilities.Decision(grants.contains(v.digest())&&!host.deny,1);
  try(var runtime=new PluginReleaseRuntime(host,new FileStateJournal(root.resolve("control")),router,factory,auth,v->repo.resolve(v.pluginId(),v.version(),v.digest()))) {
   runtime.register(admin,r1,0);runtime.register(admin,r2,rev(runtime,admin));runtime.activate(admin,"release1",rev(runtime,admin));runtime.activate(admin,"release2",rev(runtime,admin));
   var incompatible=packages.get("ci.python-1.2.0");var bad=new PluginReleaseRuntime.Release("bad-schema",Map.of("ci.python",new PluginReleaseRuntime.Version("ci.python","1.2.0",incompatible.sha256())));
   runtime.register(admin,bad,rev(runtime,admin));denied("STORAGE_ADAPTER_REQUIRED",()->runtime.activate(admin,"bad-schema",rev(runtime,admin)));
   check(runtime.status(admin,"bad-schema").get("activationState").equals("PREPARE_FAILED"),"known closed preparation failure is terminal, not crash UNKNOWN");
   var ca=runtime.catalog(a,null);var cb=runtime.catalog(b,null);oldAffinity=ca.decision().affinity();
   check(ca.decision().releaseId().equals("release1")&&cb.decision().releaseId().equals("release2"),"same trusted tenant decision chooses actual version group");
   var old=Schema.map(runtime.invoke(a,oldAffinity,"ci.python","echo",Map.of("message","old"),null,ca.snapshot().generation()));firstPid=Schema.number(old.get("pluginPid"));check(old.get("packageVersion").equals("1.0.0"),"actual stable process package");
   denied("PERMISSION_DENIED",()->runtime.invoke(b,cb.decision().affinity(),"ci.python","echo",Map.of(),null,cb.snapshot().generation()));
   denied("STALE_COMPOSITION",()->runtime.invoke(a,oldAffinity,"ci.python","echo",Map.of(),null,ca.snapshot().generation()+100));
   grants.add(two.sha256());var newer=Schema.map(runtime.invoke(b,cb.decision().affinity(),"ci.python","echo",Map.of(),null,cb.snapshot().generation()));secondPid=Schema.number(newer.get("pluginPid"));
   check(newer.get("packageVersion").equals("1.1.0")&&firstPid!=secondPid,"two real same-plugin versions coexist as distinct OS processes");
   for(var pair:List.of(Map.entry(a,ca),Map.entry(b,cb))){var s=pair.getValue();var result=Schema.map(runtime.invoke(pair.getKey(),s.decision().affinity(),"ci.node","echo",Map.of("dependency",true),null,s.snapshot().generation()));check(result.get("packageVersion").equals(s.decision().releaseId().equals("release1")?"1.0.0":"1.1.0"),"Node dependency remains inside its release registry");}
   denied("STALE_RELEASE_AFFINITY",()->runtime.invoke(b,oldAffinity,"ci.python","echo",Map.of(),null,ca.snapshot().generation()));
   runtime.pinTask(task,null,"task1","ci.python","echo",Map.of("message","pinned"));
   router.put(new ReleasePolicy("plugins","release1","release2",10000,Set.of(),Set.of(),true,0),router.revision());
   var newA=runtime.catalog(a,null);check(newA.decision().releaseId().equals("release2"),"new requests follow rollout without overwriting old runner");
   var taskResult=Schema.map(runtime.invokeTask(task,"task1",Map.of("message","pinned")));check(taskResult.get("packageVersion").equals("1.0.0"),"persistent task stays on original approved digest after rollout");
   grants.remove(one.sha256());denied("PERMISSION_DENIED",()->runtime.invokeTask(task,"task1",Map.of("message","pinned")));grants.add(one.sha256());
   denied("IDEMPOTENCY_CONFLICT",()->runtime.invokeTask(task,"task1",Map.of("message","changed")));
   runtime.pinTask(task,null,"drain-task","ci.python","echo",Map.of("message","drain-pinned"));
   var draining=runtime.beginDrain(admin,"release2",rev(runtime,admin));check(Boolean.TRUE.equals(draining.get("draining"))&&!Boolean.TRUE.equals(draining.get("admission"))&&Schema.number(draining.get("pendingTasks"))==1,"drain closes admissions and counts pinned tasks");
   denied("RELEASE_DISABLED",()->runtime.catalog(b,null));
   denied("RELEASE_DISABLED",()->runtime.pinTask(task,null,"new-task","ci.python","echo",Map.of()));
   denied("TASKS_PENDING",()->runtime.stop(admin,"release2"));
   check(Schema.map(runtime.invokeTask(task,"drain-task",Map.of("message","drain-pinned"))).get("packageVersion").equals("1.1.0"),"already pinned task completes during drain");
   check(Schema.number(runtime.status(admin,"release2").get("pendingTasks"))==0,"completed task drains pending count");
   runtime.activate(admin,"release2",rev(runtime,admin));
   runtime.pinTask(task,null,"cancel-before-start","ci.python","echo",Map.of());
   String cancelKey=Schema.map(runtime.state(admin).get("tasks")).entrySet().stream().filter(e->"cancel-before-start".equals(Schema.map(e.getValue()).get("taskId"))).findFirst().orElseThrow().getKey();
   denied("PERMISSION_DENIED",()->runtime.cancelTask(task,cancelKey,rev(runtime,admin),"TASK cannot act as release administrator"));
   denied("RELEASE_REVISION_CONFLICT",()->runtime.cancelTask(admin,cancelKey,rev(runtime,admin)-1,"Stale reviewed state"));
   runtime.cancelTask(admin,cancelKey,rev(runtime,admin),"Operator cancelled before any execution");
   denied("TASK_CANCELLED",()->runtime.invokeTask(task,"cancel-before-start",Map.of()));
   denied("TASK_CANCELLED",()->runtime.pinTask(task,null,"cancel-before-start","ci.python","echo",Map.of()));
   check(Schema.number(runtime.status(admin,"release2").get("pendingTasks"))==0,"cancelled tracked task retained but no longer blocks retirement");
   runtime.pinTask(task,null,"started-task","ci.python","echo",Map.of("message","fcp-slow:task"));
   String runningKey=Schema.map(runtime.state(admin).get("tasks")).entrySet().stream().filter(e->"started-task".equals(Schema.map(e.getValue()).get("taskId"))).findFirst().orElseThrow().getKey();
   ExecutorService taskPool=Executors.newSingleThreadExecutor();try{var execution=taskPool.submit(()->runtime.invokeTask(task,"started-task",Map.of("message","fcp-slow:task")));Thread.sleep(250);
    denied("TASK_RECONCILIATION_REQUIRED",()->runtime.cancelTask(admin,runningKey,rev(runtime,admin),"Cannot cancel a started task"));execution.get(10,TimeUnit.SECONDS);
   }finally{taskPool.shutdownNow();}
   ExecutorService pool=Executors.newSingleThreadExecutor();Future<Object> slow=pool.submit(()->runtime.invoke(a,oldAffinity,"ci.python","echo",Map.of("message","fcp-slow:drain"),null,ca.snapshot().generation()));Thread.sleep(250);
   var disabled=runtime.disable(admin,"release1",rev(runtime,admin));check(Schema.number(disabled.get("activeCalls"))>=1,"disable observes actual old-version in-flight call");
   denied("RUNNER_BUSY",()->runtime.stop(admin,"release1"));
   check(Schema.map(runtime.invoke(b,cb.decision().affinity(),"ci.python","echo",Map.of(),null,newA.snapshot().generation())).get("packageVersion").equals("1.1.0"),"other version remains callable while old drains");
   slow.get(10,TimeUnit.SECONDS);pool.shutdown();runtime.stop(admin,"release1");check(!ProcessHandle.of(firstPid).map(ProcessHandle::isAlive).orElse(false),"only disabled old version process stopped");check(ProcessHandle.of(secondPid).map(ProcessHandle::isAlive).orElse(false),"candidate process not stopped");
   runtime.activate(admin,"release1",rev(runtime,admin));
   runtime.pinTask(task,null,"legacy-pinned","ci.python","echo",Map.of());
  }
  try(var legacyJournal=new FileStateJournal(root.resolve("control"))){var snapshot=legacyJournal.load();var state=Schema.map(Json.parse(snapshot.json()));for(Object raw:Schema.map(state.get("tasks")).values()){var record=Schema.map(raw);if("legacy-pinned".equals(record.get("taskId")))record.remove("executionTracking");}legacyJournal.compareAndSet(snapshot.revision(),Json.write(state));}
  Thread.sleep(300);
  try(var runtime=new PluginReleaseRuntime(host,new FileStateJournal(root.resolve("control")),router,factory,auth,v->repo.resolve(v.pluginId(),v.version(),v.digest()))) {
   runtime.recover();var c=runtime.catalog(b,null);check(Schema.map(runtime.invoke(b,c.decision().affinity(),"ci.python","echo",Map.of(),null,c.snapshot().generation())).get("packageVersion").equals("1.1.0"),"runtime restart recovers approved compositions");
   String legacyKey=Schema.map(runtime.state(admin).get("tasks")).entrySet().stream().filter(e->"legacy-pinned".equals(Schema.map(e.getValue()).get("taskId"))).findFirst().orElseThrow().getKey();
   denied("TASK_RECONCILIATION_REQUIRED",()->runtime.cancelTask(admin,legacyKey,rev(runtime,admin),"Old PINNED is not proof of no execution"));
   runtime.invokeTask(task,"legacy-pinned",Map.of());
   var result=Schema.map(runtime.invokeTask(task,"task1",Map.of("message","pinned")));check(Schema.number(result.get("pluginPid"))==firstPid,"task completed receipt survives restart without executing in a new version/process");
   long recoveredPid=Schema.number(Schema.map(Schema.map(runtime.status(admin,"release2").get("processes")).get("ci.python")).get("pid"));
   ProcessHandle.of(recoveredPid).orElseThrow().destroyForcibly();ProcessHandle.of(recoveredPid).orElseThrow().onExit().get(5,TimeUnit.SECONDS);
   denied("RELEASE_NOT_READY",()->runtime.activate(admin,"release2",rev(runtime,admin)));
   router.put(new ReleasePolicy("plugins","release1","release2",0,Set.of(),Set.of(),false,1),router.revision());denied("RELEASE_DISABLED",()->runtime.invokeTask(task,"task1",Map.of("message","pinned")));
   denied("RELEASE_DISABLED",()->runtime.catalog(a,null));
  }
  System.out.println("PASS "+checks+" real multi-version runtime assertions; Native RuoYi/MySQL/browser NOT_RUN");
 }
}
