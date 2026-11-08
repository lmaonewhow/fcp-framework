import dev.fcp.host.*;import dev.fcp.host.release.*;import dev.fcp.host.ui.*;import fcp.*;import fcp.host.*;import fcp.control.*;import fcp.runtime.*;import fcp.runtime.multiversion.*;import fcp.release.control.*;import fcp.release.routing.*;import fcp.release.version.*;import java.nio.file.*;import java.time.*;import java.util.*;
public class RetireStoppedTest {
 public static void main(String[] args)throws Exception {
  Path dir=Files.createTempDirectory("retire-stopped-");HostCapabilities host=new HostCapabilities(){public Clock clock(){return Clock.systemUTC();}public Descriptor descriptor(){return new Descriptor("test","1","fcp.host/1","embedded-single-instance",Map.of());}public Decision authorize(TrustedContext c,Grant g){return new Decision(true,1);}public void audit(Audit a){}};
  TrustedContext actor=new TrustedContext("request","trace","000000",new TrustedContext.Principal(TrustedContext.Kind.USER,"1"),1,Set.of(),Instant.now().plusSeconds(120));
  var router=new DurableReleaseRouter(new FileStateJournal(dir.resolve("router")),new byte[32],Clock.systemUTC(),Duration.ofMinutes(1));router.put(new ReleasePolicy("native-test","stable","candidate",0,Set.of(),Set.of(),true,0),0);
  var packages=new VerifiedPackageRepository(dir.resolve("packages"),Set.of("test"));
  var versions=new JournalVersionProvider("test",new FileStateJournal(dir.resolve("versions")));
  VersionPluginPolicy policy=new VersionPluginPolicy(){public HostCapabilities.Decision authorize(TrustedContext c,Version v,HostCapabilities.Grant g){return new HostCapabilities.Decision(true,1);}public Approval read(TrustedContext c,GrantKey k){return null;}public Approval change(TrustedContext c,GrantKey k,boolean a,Instant e,long r){throw new UnsupportedOperationException();}};
  try(var runtime=new PluginReleaseRuntime(host,new FileStateJournal(dir.resolve("plugins")),router,(id,scoped)->new EngineRuntimePort(scoped,packages,Map.of(),dir.resolve("calls-"+id),3),(c,v,g)->new HostCapabilities.Decision(true,1),v->packages.resolve(v.pluginId(),v.version(),v.digest()))) {
   runtime.register(actor,new PluginReleaseRuntime.Release("candidate",Map.of()),0);runtime.activate(actor,"candidate",Schema.number(runtime.state(actor).get("revision")));runtime.beginDrain(actor,"candidate",Schema.number(runtime.state(actor).get("revision")));runtime.stop(actor,"candidate");
   var app=new PluginReleaseApplication(host,runtime,packages,versions,policy,c->Set.of());var components=new NativeCompositionComponents(versions,new UiBundleStore(dir),app,()->actor);
   System.out.println("before="+components.status("candidate"));
   var executor=new CompositeReleaseDeployment(new FileStateJournal(dir.resolve("executor")),router,components,"test",(r,g,v)->{});
   var plan=new ReleasePlan("test","stable","candidate","a".repeat(64),"b".repeat(64),1,1,1,1,1);
   var command=new DeploymentProvider.Command("retire",plan,DeploymentProvider.Action.RETIRE,0,1);
   executor.submit(command);
   var result=executor.observe("test","retire").orElseThrow();if(!result.complete()||result.outcome()!=DeploymentProvider.ExecutionOutcome.SUCCEEDED)throw new AssertionError("Already stopped target must retire without reactivation");if(!components.status("candidate").stopped())throw new AssertionError("Stopped target resurrected");System.out.println("PASS already stopped native composition retires without reactivation");
  }
 }
}
