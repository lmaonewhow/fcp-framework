import fcp.*;
import fcp.control.*;
import fcp.host.*;
import fcp.plugin.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Explicit in-process RuntimePort fixture: validates coordinator behavior, NOT native runner acceptance. */
public class LifecycleTest {
    static class Host implements HostCapabilities {
        Set<String> denied=new HashSet<>();long authRevision=1;
        public Descriptor descriptor(){return new Descriptor("test","1","fcp.host/1","embedded-single-instance",Map.of());}
        public Clock clock(){return Clock.systemUTC();}
        public Decision authorize(TrustedContext c,Grant g){return new Decision(!denied.contains(g.pluginId()),authRevision);}
        public void audit(Audit event){}
    }
    static class FixtureRuntime implements RuntimePort {
        Map<String,Set<String>> catalogs=new HashMap<>();Set<String> stopped=new HashSet<>();long calls;boolean failPublish;
        public void resetAdmissions(){catalogs.clear();}
        public void verifyInstalledPackage(PackageInstaller.InstalledPackage pkg){} // fixture only
        public void verifyStorageCompatibility(String p,String v,String d){}
        public void prepare(String p,String v,String d){}
        public boolean ready(String p,String v,String d){return true;}
        public void publish(String t,Set<String> p,long generation){if(failPublish){failPublish=false;throw new IllegalStateException("injected publication failure");}catalogs.put(t,p);}
        public long activeCalls(String t,String p){return calls;}
        public void cancel(String t,String p){calls=0;}
        public void stop(String p){stopped.add(p);}
    }
    static TrustedContext context(String t){return new TrustedContext("r","trace",t,new TrustedContext.Principal(TrustedContext.Kind.USER,"admin"),1,Set.of("*"),Instant.now().plusSeconds(600));}
    static PackageInstaller.InstalledPackage pkg(Path root,String id,String deps)throws Exception {
        String raw="{\"manifestVersion\":1,\"pluginId\":\""+id+"\",\"version\":\"1.0.0\",\"publisher\":\"fixture\",\"contractVersion\":\"1.0\",\"runtime\":{\"type\":\"local\",\"entry\":\"entry.py\",\"argv\":[]},\"dependencies\":"+deps+"}";
        Path dir=Files.createDirectory(root.resolve(id));Files.writeString(dir.resolve("plugin.json"),raw);
        return new PackageInstaller.InstalledPackage(PluginManifest.parse(raw),"a".repeat(64),dir,false);
    }
    static void check(boolean v,String label){if(!v)throw new AssertionError(label);}
    static void rejected(Runnable r,String label){try{r.run();throw new AssertionError("not rejected: "+label);}catch(FcpException expected){}}
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("fcp-lifecycle-test-");Path journal=root.resolve("journal");Host host=new Host();FixtureRuntime rt=new FixtureRuntime();TrustedContext t1=context("t1"),t2=context("t2");
        String operationId;String failedId;
        try(LifecycleCoordinator c=new LifecycleCoordinator(journal,host,rt)){
            c.register(t1,pkg(root,"d","[]"),c.revision());c.register(t1,pkg(root,"p","[{\"pluginId\":\"d\",\"versionRange\":\"1.0.0\"}]"),c.revision());
            var enable=c.plan(t1,"t1","p",LifecycleCoordinator.Action.ENABLE,c.revision());
            check(enable.closure().equals(List.of("d","p")),"dependency closure");
            rejected(()->c.confirm(t1,enable,Set.of("p"),"bad"),"explicit closure");
            host.denied.add("d");rejected(()->c.confirm(t1,enable,Set.of("d","p"),"denied"),"revoked dependency");host.denied.clear();
            host.authRevision++;rejected(()->c.confirm(t1,enable,Set.of("d","p"),"stale-auth"),"authorization revision digest");
            var refreshed=c.plan(t1,"t1","p",LifecycleCoordinator.Action.ENABLE,c.revision());var result=c.confirm(t1,refreshed,Set.of("d","p"),"enable");
            check(c.confirm(t1,refreshed,Set.of("d","p"),"enable").equals(result),"idempotency replay");
            check(rt.catalogs.get("t1").equals(Set.of("d","p")),"atomic closure publication");
            c.confirm(t2,c.plan(t2,"t2","p",LifecycleCoordinator.Action.ENABLE,c.revision()),Set.of("d","p"),"enable");
            var disable=c.plan(t1,"t1","d",LifecycleCoordinator.Action.DISABLE,c.revision());check(disable.closure().equals(List.of("d","p")),"reverse dependency cascade");
            rt.calls=1;var drain=c.confirm(t1,disable,Set.of("d","p"),"disable");operationId=drain.id();check(drain.phase().equals("DRAINING"),"drain wait");check(rt.catalogs.get("t1").isEmpty(),"admission cutoff");check(rt.catalogs.get("t2").size()==2,"other tenant unaffected");
            c.decideDrain(t1,drain.id(),LifecycleCoordinator.DrainDecision.WAIT);c.decideDrain(t1,drain.id(),LifecycleCoordinator.DrainDecision.CANCEL);check(rt.stopped.isEmpty(),"shared process retained");
            rejected(()->c.plan(t1,"t2","p",LifecycleCoordinator.Action.ENABLE,c.revision()),"tenant mismatch");
            try{new LifecycleCoordinator(journal,host,new FixtureRuntime());throw new AssertionError("duplicate writer");}catch(java.io.IOException expected){}
            var a=c.plan(t1,"t1","p",LifecycleCoordinator.Action.ENABLE,c.revision());var b=c.plan(t1,"t1","d",LifecycleCoordinator.Action.ENABLE,c.revision());
            ExecutorService pool=Executors.newFixedThreadPool(2);List<Future<Boolean>> futures=pool.invokeAll(List.of(()->{try{c.confirm(t1,a,Set.of("d","p"),"race-a");return true;}catch(FcpException e){return false;}},()->{try{c.confirm(t1,b,Set.of("d"),"race-b");return true;}catch(FcpException e){return false;}}));int wins=0;for(var f:futures)if(f.get())wins++;pool.shutdown();check(wins==1,"concurrent CAS exactly one winner");
            var failurePlan=c.plan(t1,"t1","d",LifecycleCoordinator.Action.DISABLE,c.revision());rt.failPublish=true;
            try{c.confirm(t1,failurePlan,new HashSet<>(failurePlan.closure()),"crash");throw new AssertionError("missing injected failure");}catch(IllegalStateException expected){}
            var failed=c.confirm(t1,failurePlan,new HashSet<>(failurePlan.closure()),"crash");failedId=failed.id();check(failed.outcome().equals("OUTCOME_UNKNOWN"),"uncertain publication journaled");check(rt.catalogs.get("t1").isEmpty(),"failed publication fail closed");
        }
        try(LifecycleCoordinator c=new LifecycleCoordinator(journal,host,rt)){
            check(c.status(t2,"t2","p").effective().equals("UNKNOWN"),"restart not historical ready");check(rt.catalogs.isEmpty(),"restart admission closed");c.reconcile(t2,"t2");check(c.status(t2,"t2","p").effective().equals("READY"),"runner reconciliation");check(c.operation(t1,operationId).phase().equals("COMPLETE"),"durable journal receipt");c.reconcile(t1,"t1");check(c.operation(t1,failedId).phase().equals("COMPLETE"),"uncertain intent reconciled on restart");
        }
        Path bad=root.resolve("bad");Files.createDirectory(bad);Files.writeString(bad.resolve("lifecycle.json"),"{\"unexpected\":1}");try{new LifecycleCoordinator(bad,host,rt);throw new AssertionError("bad schema accepted");}catch(java.io.IOException expected){}
        Path link=root.resolve("link");Files.createSymbolicLink(link,journal);try{new LifecycleCoordinator(link.resolve("child"),host,rt);throw new AssertionError("symlink ancestor accepted");}catch(java.io.IOException expected){}
        System.out.println("PASS lifecycle fixture: dependencies, explicit cascade, live authorization, idempotency, tenant isolation, drain, file lock, concurrent CAS, restart reconciliation, publication failure recovery, invalid journal and symlink rejection");
    }
}
