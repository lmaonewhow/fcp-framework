import fcp.*;
import fcp.control.*;
import fcp.runtime.*;
import dev.fcp.host.PluginVersionApplication;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.*;

/** Real child JVM halts after atomic durable INTENT, SWITCHED and COMPLETE writes. */
public class VersionCrashTest {
    static final class HaltJournal implements StateJournal {
        final StateJournal delegate;final String target;boolean armed;
        HaltJournal(Path directory,String target)throws Exception{delegate=new FileStateJournal(directory);this.target=target;}
        public Snapshot load()throws java.io.IOException{return delegate.load();}
        public Snapshot compareAndSet(long revision,String json)throws java.io.IOException{
            Snapshot saved=delegate.compareAndSet(revision,json);
            if(armed)for(Object value:Schema.obj(Schema.map(Json.parse(json)),"versionOperations").values())if(target.equals(Schema.map(value).get("phase")))Runtime.getRuntime().halt(91);
            return saved;
        }
        public void close()throws java.io.IOException{delegate.close();}
    }
    public static void main(String[] args)throws Exception {
        if(args[0].equals("child")){child(args);return;}
        Path inputs=Path.of(args[0]);String java=Path.of(System.getProperty("java.home"),"bin/java").toString();
        for(String phase:List.of("INTENT","SWITCHED","COMPLETE")){
            Path root=Files.createTempDirectory("version-crash-"+phase.toLowerCase()+"-");
            Process process=new ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),"VersionCrashTest","child",root.toString(),inputs.toString(),args[1],args[2],phase).inheritIO().start();
            VersionLifecycleTest.check(process.waitFor()==91,"child halted at actual durable "+phase);
            Map<String,Object> recovery=Schema.map(Json.parse(Files.readString(root.resolve("confirmation.json"))));var host=new VersionLifecycleTest.Host();var repo=new VerifiedPackageRepository(root.resolve("packages"),Set.of("test"));
            try(var runtime=new EngineRuntimePort(host,repo,Map.of("python",args[1],"node",args[2]),root.resolve("calls"),10);var coordinator=new LifecycleCoordinator(root.resolve("control"),host,runtime)){
                var app=new PluginVersionApplication(host,repo,coordinator,root.resolve("uploads"));var context=VersionLifecycleTest.context("t1");
                var receipt=app.confirm(context,"lost-http-plan",(String)recovery.get("digest"),"crash-version",true);
                VersionLifecycleTest.check(receipt.get("outcome").equals("APPLIED")&&receipt.get("phase").equals("COMPLETE"),"explicit durable recovery "+phase);
                VersionLifecycleTest.check(coordinator.installedMetadata(context,"ci.python").get("version").equals("1.1.0"),"recovered selected package "+phase);
                VersionLifecycleTest.check(runtime.snapshot(context).enabledManifests().isEmpty(),"recovery never enables plugin "+phase);
                VersionLifecycleTest.check(app.confirm(context,"lost-http-plan",(String)recovery.get("digest"),"crash-version",true).equals(receipt),"recovery idempotent "+phase);
            }
            System.out.println("PASS actual JVM version crash/recovery phase="+phase);
        }
    }
    static void child(String[] args)throws Exception {
        Path root=Path.of(args[1]),inputs=Path.of(args[2]);var repo=new VerifiedPackageRepository(root.resolve("packages"),Set.of("test"));var host=new VersionLifecycleTest.Host();var context=VersionLifecycleTest.context("t1");
        Path one=inputs.resolve("ci.python.zip"),two=inputs.resolve("ci.python-1.1.0.zip");var first=repo.stageOnly(one,VerifiedPackageRepository.hash(Files.readAllBytes(one)),"one");var second=repo.stageOnly(two,VerifiedPackageRepository.hash(Files.readAllBytes(two)),"two");
        var journal=new HaltJournal(root.resolve("control"),args[5]);
        try(var runtime=new EngineRuntimePort(host,repo,Map.of("python",args[3],"node",args[4]),root.resolve("calls"),10);var coordinator=new LifecycleCoordinator(journal,host,runtime)){
            coordinator.register(context,first,0);var app=new PluginVersionApplication(host,repo,coordinator,root.resolve("uploads"));var plan=app.plan(context,"ci.python","UPGRADE","1.1.0",second.sha256(),coordinator.revision());
            Path confirmation=root.resolve("confirmation.json");Files.writeString(confirmation,Json.write(plan));try(var file=FileChannel.open(confirmation,StandardOpenOption.WRITE)){file.force(true);}
            journal.armed=true;app.confirm(context,(String)plan.get("planId"),(String)plan.get("digest"),"crash-version",true);throw new AssertionError("No halt at "+args[5]);
        }
    }
}
