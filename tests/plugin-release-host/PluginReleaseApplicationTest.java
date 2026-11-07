import dev.fcp.host.*;
import fcp.*;
import fcp.host.*;
import fcp.control.*;
import fcp.plugin.*;
import fcp.runtime.*;
import fcp.runtime.multiversion.*;
import fcp.release.routing.*;
import fcp.release.version.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Real package/Engine/Python process test. Authorization is a fixture, NOT native/MySQL acceptance. */
public class PluginReleaseApplicationTest {
    static int checks;static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    interface Call{void run()throws Exception;}
    static void rejected(String code,Call c)throws Exception{try{c.run();throw new AssertionError("Expected "+code);}catch(FcpException e){check(e.code.equals(code),"expected "+code+", got "+e.code);}}
    static class Host implements HostCapabilities {
        boolean denied;public Clock clock(){return Clock.systemUTC();}
        public Descriptor descriptor(){return new Descriptor("release-app-test","1","fcp.host/1","embedded-single-instance",Map.of("ui.slot.dashboard.status","1"));}
        public Decision authorize(TrustedContext c,Grant g){return new Decision(!denied,1);}public void audit(Audit e){}
    }
    static class Policy implements VersionPluginPolicy {
        final Set<String> allowed=new HashSet<>();
        public HostCapabilities.Decision authorize(TrustedContext c,Version v,HostCapabilities.Grant g){return new HostCapabilities.Decision(allowed.contains(v.digest()),1);}
        public Approval read(TrustedContext c,GrantKey k){return null;}
        public Approval change(TrustedContext c,GrantKey k,boolean allow,Instant expiry,long expected){if(allow)allowed.add(k.version().digest());else allowed.remove(k.version().digest());return new Approval(k,"fcp:plugin:echo",expected+1,allow,expiry);}
    }
    static TrustedContext ctx(String tenant){return new TrustedContext(UUID.randomUUID().toString(),"trace",tenant,new TrustedContext.Principal(TrustedContext.Kind.USER,"operator"),1,Set.of(),Instant.now().plusSeconds(90));}
    public static void main(String[] args)throws Exception{
        check(PluginReleaseApplication.requireLegacyQuiescent(null).isEmpty(),"empty legacy state permits explicit mode");
        String stopped=Json.write(Map.of("tenants",Map.of("t1",Map.of("enabled",List.of())),"installs",Map.of("old",Map.of()),"operations",Map.of()));
        check(PluginReleaseApplication.requireLegacyQuiescent(stopped).equals(Set.of("old")),"stopped legacy inventory retained for process checks");
        rejected("MODE_CONFLICT",()->PluginReleaseApplication.requireLegacyQuiescent(Json.write(Map.of("tenants",Map.of("t1",Map.of("enabled",List.of("old"))),"installs",Map.of(),"operations",Map.of()))));
        rejected("MODE_CONFLICT",()->PluginReleaseApplication.requireLegacyQuiescent(Json.write(Map.of("tenants",Map.of(),"installs",Map.of(),"operations",Map.of("pending",Map.of("phase","UNKNOWN"))))));
        Path root=Files.createTempDirectory("plugin-release-app-"),inputs=Path.of(args[0]);var packages=new VerifiedPackageRepository(root.resolve("packages"),Set.of("test"));
        var one=packages.stageOnly(inputs.resolve("ci.python.zip"),VerifiedPackageRepository.hash(Files.readAllBytes(inputs.resolve("ci.python.zip"))),"one");
        var two=packages.stageOnly(inputs.resolve("ci.python-1.1.0.zip"),VerifiedPackageRepository.hash(Files.readAllBytes(inputs.resolve("ci.python-1.1.0.zip"))),"two");
        Host host=new Host();Policy policy=new Policy();var admin=ctx("platform");
        try(var versionJournal=new FileStateJournal(root.resolve("versions"));var routeJournal=new FileStateJournal(root.resolve("routes"));var compositionJournal=new FileStateJournal(root.resolve("compositions"))){
            VersionProvider versions=new JournalVersionProvider("test",versionJournal);var router=new DurableReleaseRouter(routeJournal,new byte[32],host.clock(),Duration.ofMinutes(1));
            router.put(new ReleasePolicy("product","old","new",0,Set.of("t2"),Set.of(),true,0),0);
            try(var runtime=new PluginReleaseRuntime(host,compositionJournal,router,(id,scoped)->new EngineRuntimePort(scoped,packages,Map.of("python",args[1]),root.resolve("calls-"+id),10),(c,v,g)->policy.authorize(c,new VersionPluginPolicy.Version(v.pluginId(),v.version(),v.digest()),g),v->packages.resolve(v.pluginId(),v.version(),v.digest()))){
                var observed=new java.util.ArrayList<fcp.release.observation.RequestObserver.BusinessOutcome>();
                fcp.release.observation.RequestObserver observer=(release,revision)->{check(Set.of("old","new").contains(release),"observer uses trusted selected release");return (technical,business,unknown,nanos)->observed.add(business);};
                var app=new PluginReleaseApplication(host,runtime,packages,versions,policy,c->Set.of("fcp:plugin:view","fcp:plugin:echo"),observer,null);
                var r1=new PluginReleaseRuntime.Release("old",Map.of("ci.python",new PluginReleaseRuntime.Version("ci.python","1.0.0",one.sha256())));
                var r2=new PluginReleaseRuntime.Release("new",Map.of("ci.python",new PluginReleaseRuntime.Version("ci.python","1.1.0",two.sha256())));
                rejected("SYSTEM_RELEASE_NOT_FOUND",()->app.register(admin,r1.json(),0));
                for(var item:List.of(one,two)){String id=item==one?"a1":"a2";versions.registerArtifact(new SystemArtifact(id,SystemArtifact.Kind.PLUGIN,"ci.python",item.manifest().version(),item.sha256(),Files.size(inputs.resolve(item==one?"ci.python.zip":"ci.python-1.1.0.zip")),"artifact:"+id,Map.of(),Map.of()));}
                versions.registerRelease(new SystemRelease("old","1",Map.of("plugin.ci.python","a1"),Map.of(),Map.of()));versions.registerRelease(new SystemRelease("new","2",Map.of("plugin.ci.python","a2"),Map.of(),Map.of()));
                var forged=new PluginReleaseRuntime.Release("old",Map.of("ci.python",new PluginReleaseRuntime.Version("ci.python","1.1.0",two.sha256())));
                rejected("SYSTEM_RELEASE_PLUGIN_MISMATCH",()->app.register(admin,forged.json(),0));
                app.register(admin,r1.json(),Schema.number(app.state(admin).get("revision")));app.register(admin,r2.json(),Schema.number(app.state(admin).get("revision")));app.activate(admin,"old",Schema.number(app.state(admin).get("revision")));app.activate(admin,"new",Schema.number(app.state(admin).get("revision")));
                var t1=ctx("t1");var t2=ctx("t2");policy.allowed.add(one.sha256());
                var c1=app.catalog(t1,null);var c2=app.catalog(t2,null);
                check(c1.get("releaseId").equals("old")&&c2.get("releaseId").equals("new"),"catalog uses same server release policy");
                check(!Schema.list(Schema.map(c1.get("catalog")).get("pages")).isEmpty(),"approved old version page projected");
                check(Schema.list(Schema.map(c2.get("catalog")).get("pages")).isEmpty(),"new package does not inherit old digest approval");
                String export=EngineRuntimePort.export("ci.python","echo");long g1=Schema.number(Schema.map(c1.get("catalog")).get("revision")),g2=Schema.number(Schema.map(c2.get("catalog")).get("revision"));
                rejected("PERMISSION_DENIED",()->app.invoke(t2,(String)c2.get("affinity"),export,Map.of("message","no-grant"),null,g2));
                policy.allowed.add(two.sha256());var output1=Schema.map(app.invoke(t1,(String)c1.get("affinity"),export,Map.of("message","one"),null,g1));var output2=Schema.map(app.invoke(t2,(String)c2.get("affinity"),export,Map.of("message","two"),null,g2));
                var grantedProjection=app.catalog(t2,(String)c2.get("affinity"));
                check(!grantedProjection.get("projectionFingerprint").equals(c2.get("projectionFingerprint"))&&Schema.number(Schema.map(grantedProjection.get("catalog")).get("revision"))==g2,"grant changes projection fingerprint without changing Engine invocation revision");
                check(output1.get("packageVersion").equals("1.0.0")&&output2.get("packageVersion").equals("1.1.0"),"actual isolated Python versions match selected catalog");
                check(!output1.get("pluginPid").equals(output2.get("pluginPid")),"actual separate OS processes");
                check(!observed.isEmpty()&&observed.stream().allMatch(outcome->outcome==fcp.release.observation.RequestObserver.BusinessOutcome.UNKNOWN),"unclassified real plugin responses never become business success");
                var classified=new PluginReleaseApplication(host,runtime,packages,versions,policy,c->Set.of("fcp:plugin:view","fcp:plugin:echo"),observer,(caller,release,plugin,capability,result)->{check(caller==t1&&release.equals("old")&&plugin.equals("ci.python")&&capability.equals("echo"),"classifier receives trusted host identity and package selection");return fcp.release.observation.RequestObserver.BusinessOutcome.SUCCESS;});
                classified.invoke(t1,(String)c1.get("affinity"),export,Map.of("message","trusted-classifier"),null,g1);
                check(observed.get(observed.size()-1)==fcp.release.observation.RequestObserver.BusinessOutcome.SUCCESS,"only explicit host classifier confirms business outcome");
                rejected("RELEASE_AFFINITY_REQUIRED",()->app.invoke(t1,null,export,Map.of(),null,g1));
                rejected("CAPABILITY_MISSING",()->app.invoke(t1,(String)c1.get("affinity"),"bogus",Map.of(),null,g1));
                policy.allowed.remove(two.sha256());rejected("PERMISSION_DENIED",()->app.invoke(t2,(String)c2.get("affinity"),export,Map.of(),null,g2));
                var revokedProjection=app.catalog(t2,(String)c2.get("affinity"));
                check(!revokedProjection.get("projectionFingerprint").equals(grantedProjection.get("projectionFingerprint"))&&Schema.number(Schema.map(revokedProjection.get("catalog")).get("revision"))==g2&&Schema.list(Schema.map(revokedProjection.get("catalog")).get("pages")).isEmpty(),"same-identity digest revoke produces new empty UI projection at original generation");
                var options=app.grantOptions(admin,new VersionPluginPolicy.Version("ci.python","1.1.0",two.sha256()),"t2","tenant:t2",TrustedContext.Kind.USER,"*");
                check(options.get("digest").equals(two.sha256())&&!Schema.list(options.get("options")).isEmpty(),"approval options bind verified package digest");
                host.denied=true;rejected("PERMISSION_DENIED",()->app.state(admin));host.denied=false;
                var draining=app.beginDrain(admin,"new",Schema.number(app.state(admin).get("revision")));
                check(Boolean.TRUE.equals(draining.get("draining"))&&Boolean.FALSE.equals(draining.get("admission")),"drain closes new admission without deleting release");
                check(Schema.number(app.status(admin,"new").get("pendingTasks"))==0,"native status exposes pinned task drain count");
                check(Boolean.TRUE.equals(app.stop(admin,"new").get("stopped")),"drained release stops independently");
                check(Boolean.TRUE.equals(app.status(admin,"old").get("ready")),"other version remains ready");
                router.put(new ReleasePolicy("product","old","new",0,Set.of("t2"),Set.of(),false,1),1);
                rejected("RELEASE_DISABLED",()->app.catalog(t1,(String)c1.get("affinity")));
            }
        }
        check(Schema.number(ProcessProvider.metrics().get("activeProcesses"))==0,"no actual process leak");
        System.out.println("PASS "+checks+" application assertions. Native RuoYi/MySQL/browser NOT_RUN.");
    }
}
