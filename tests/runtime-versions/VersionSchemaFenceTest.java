import fcp.*;
import fcp.control.*;
import fcp.plugin.*;
import fcp.runtime.*;
import dev.fcp.host.PluginVersionApplication;
import java.nio.file.*;
import java.util.*;

/** Schema token is an explicit fixture; package bytes and durable CAS journal are real. */
public class VersionSchemaFenceTest {
    static class SchemaRuntime implements RuntimePort {
        final VerifiedPackageRepository packages;String token="schema-one";boolean changeOnStop;
        SchemaRuntime(VerifiedPackageRepository packages){this.packages=packages;}
        public void resetAdmissions(){}
        public void verifyInstalledPackage(PackageInstaller.InstalledPackage pkg){try{packages.verify(pkg);}catch(java.io.IOException e){throw new FcpException("PACKAGE_INTEGRITY","fixture package verification",e);}}
        public void verifyStorageCompatibility(String p,String v,String d){}
        public String storageCompatibilityFingerprint(String p,String v,String d){return token;}
        public void prepare(String p,String v,String d){throw new AssertionError("Version switch must not enable a runner");}
        public boolean ready(String p,String v,String d){return false;}
        public void publish(String t,Set<String> p,long generation){if(!p.isEmpty())throw new AssertionError("Unexpected publication");}
        public long activeCalls(String t,String p){return 0;}
        public void cancel(String t,String p){}
        public void stop(String p){if(changeOnStop)token="schema-two";}
    }
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("version-schema-fence-"),inputs=Path.of(args[0]);var repository=new VerifiedPackageRepository(root.resolve("packages"),Set.of("test"));
        Path oldArchive=inputs.resolve("ci.python.zip"),newArchive=inputs.resolve("ci.python-1.1.0.zip");var old=repository.stageOnly(oldArchive,VerifiedPackageRepository.hash(Files.readAllBytes(oldArchive)),"old");var target=repository.stageOnly(newArchive,VerifiedPackageRepository.hash(Files.readAllBytes(newArchive)),"new");
        var host=new VersionLifecycleTest.Host();var context=VersionLifecycleTest.context("t1");var runtime=new SchemaRuntime(repository);String digest;
        try(var coordinator=new LifecycleCoordinator(root.resolve("journal"),host,runtime)){
            coordinator.register(context,old,0);var application=new PluginVersionApplication(host,repository,coordinator,root.resolve("uploads"));
            var plan=VersionLifecycleTest.plan(application,context,"UPGRADE","1.1.0",target.sha256(),coordinator.revision());runtime.token="schema-two";
            VersionLifecycleTest.rejected("CONFIRMATION_REQUIRED",()->VersionLifecycleTest.confirm(application,context,plan,"changed-before-confirm"));VersionLifecycleTest.check(coordinator.revision()==1,"schema change rejected before version intent");runtime.token="schema-one";
            var raced=VersionLifecycleTest.plan(application,context,"UPGRADE","1.1.0",target.sha256(),coordinator.revision());digest=(String)raced.get("digest");runtime.changeOnStop=true;
            VersionLifecycleTest.rejected("SCHEMA_CHANGED",()->VersionLifecycleTest.confirm(application,context,raced,"schema-race"));VersionLifecycleTest.check(coordinator.installedMetadata(context,"ci.python").get("digest").equals(old.sha256()),"schema race never switches selected package");
            VersionLifecycleTest.rejected("VERSION_OPERATION_PENDING",()->coordinator.plan(context,"t1","ci.python",LifecycleCoordinator.Action.ENABLE,coordinator.revision()));
        }
        var recoveryRuntime=new SchemaRuntime(repository); // Test restores its simulated token; production code never changes a database schema.
        try(var coordinator=new LifecycleCoordinator(root.resolve("journal"),host,recoveryRuntime)){
            var app=new PluginVersionApplication(host,repository,coordinator,root.resolve("uploads"));var result=app.confirm(context,"lost-plan",digest,"schema-race",true);
            VersionLifecycleTest.check(result.get("phase").equals("COMPLETE")&&coordinator.installedMetadata(context,"ci.python").get("digest").equals(target.sha256()),"explicit same-key UNKNOWN recovery rechecks schema before switch");
        }
        System.out.println("PASS schema-token fixture: reviewed token bound to plan, prepare race remains disabled/UNKNOWN, explicit same-key recovery rechecks token");
    }
}
