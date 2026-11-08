import dev.fcp.host.*;
import fcp.*;
import fcp.host.*;
import fcp.observability.TraceContext;
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
        final List<TrustedContext> taskContexts=new ArrayList<>();
        public HostCapabilities.Decision authorize(TrustedContext c,Version v,HostCapabilities.Grant g){if(c.principal().kind()==TrustedContext.Kind.TASK){taskContexts.add(c);check(TraceContext.current()!=null&&TraceContext.current().traceId().equals(c.traceId())&&TraceContext.current().requestId().equals(c.requestId()),"TASK authorization sees bound entry correlation");}return new HostCapabilities.Decision(allowed.contains(v.digest()),1);}
        public Approval read(TrustedContext c,GrantKey k){return null;}
        public Approval change(TrustedContext c,GrantKey k,boolean allow,Instant expiry,long expected){if(allow)allowed.add(k.version().digest());else allowed.remove(k.version().digest());return new Approval(k,"fcp:plugin:echo",expected+1,allow,expiry);}
    }
    static TrustedContext ctx(String tenant){return new TrustedContext(UUID.randomUUID().toString(),"trace",tenant,new TrustedContext.Principal(TrustedContext.Kind.USER,"operator"),1,Set.of(),Instant.now().plusSeconds(90));}
    static TrustedContext t1ForTask(){return ctx("t1");}
    static void taskTraceChecks(ReleaseTaskApplication tasks,TrustedContext operator,StateJournal taskJournal,StateJournal runtimeJournal,Host host,Policy policy)throws Exception{
        check(TraceContext.current()==null,"TASK test starts on clean thread");
        var http=TraceContext.start(null,false);
        try(var scope=TraceContext.open(http)){
            var pin=tasks.pin(operator,"registered","trace-bound",Map.of("message","trace"));
            var result=tasks.run(operator,"registered","trace-bound");
            check(TraceContext.current()==http,"HTTP scope survives successful TASK calls");
            check(Schema.map(result.get("result")).get("packageVersion").equals("1.0.0"),"traced TASK uses actual pinned Python");
            int appRows=0;
            for(Object raw:Schema.list(Schema.map(Json.parse(taskJournal.load().json())).get("audit"))){
                var audit=Schema.map(raw);if(!"trace-bound".equals(audit.get("run")))continue;
                appRows++;check(http.traceId().equals(audit.get("traceId"))&&http.requestId().equals(audit.get("requestId")),"application audit retains entry trace/request");
            }
            check(appRows==4,"both pin and run audit pairs checked");
            var runtimeState=Schema.map(Json.parse(runtimeJournal.load().json()));
            String runtimeKey=Schema.map(runtimeState.get("tasks")).entrySet().stream().filter(e->pin.get("taskId").equals(Schema.map(e.getValue()).get("taskId"))).map(Map.Entry::getKey).findFirst().orElseThrow();
            int runtimeRows=0;
            for(Object raw:Schema.list(runtimeState.get("audit"))){
                var audit=Schema.map(raw);if(!String.valueOf(audit.get("action")).endsWith(":"+runtimeKey))continue;
                runtimeRows++;check(http.traceId().equals(audit.get("traceId"))&&"registered-principal".equals(audit.get("actor"))&&"t1".equals(audit.get("tenant")),"runtime audit keeps trace and deployment TASK authority");
            }
            check(runtimeRows==2,"real runtime records executing and completion");
            host.denied=true;
            try{rejected("PERMISSION_DENIED",()->tasks.pin(operator,"registered","denied",Map.of()));
                rejected("PERMISSION_DENIED",()->tasks.run(operator,"registered","trace-bound"));}
            finally{host.denied=false;}
            check(TraceContext.current()==http,"authorization rejection restores outer scope");
            tasks.pin(operator,"registered","trace-invalid",Map.of("undeclared",true));
            try{tasks.run(operator,"registered","trace-invalid");throw new AssertionError("invalid task accepted");}catch(FcpException expected){checks++;}
            check(TraceContext.current()==http,"runtime exception restores outer scope");
            check(tasks.status(operator,"registered","trace-invalid").get("state").equals("UNKNOWN"),"tracing preserves UNKNOWN");
        }
        check(TraceContext.current()==null,"closing HTTP scope clears reused thread");
        tasks.pin(operator,"registered","trace-root",Map.of("message","root"));
        check(TraceContext.current()==null,"unbound pin removes root");
        tasks.run(operator,"registered","trace-root");
        check(TraceContext.current()==null,"unbound run removes root");
        Set<String> pinTraces=new HashSet<>(),runTraces=new HashSet<>();
        for(Object raw:Schema.list(Schema.map(Json.parse(taskJournal.load().json())).get("audit"))){
            var audit=Schema.map(raw);if(!"trace-root".equals(audit.get("run")))continue;
            String trace=String.valueOf(audit.get("traceId")),request=String.valueOf(audit.get("requestId"));
            check(TraceContext.validTraceId(trace)&&TraceContext.validTraceId(request),"unbound audit uses valid IDs");
            (String.valueOf(audit.get("action")).startsWith("PIN")?pinTraces:runTraces).add(trace);
        }
        check(pinTraces.size()==1&&runTraces.size()==1&&!pinTraces.equals(runTraces),"unbound entries get distinct internally consistent roots");
        rejected("TASK_DEFINITION_MISSING",()->tasks.pin(operator,"missing","trace-root",Map.of()));
        rejected("TASK_RUN_MISSING",()->tasks.run(operator,"registered","missing"));
        host.denied=true;
        try{rejected("PERMISSION_DENIED",()->tasks.pin(operator,"registered","root-denied",Map.of()));
            rejected("PERMISSION_DENIED",()->tasks.run(operator,"registered","trace-root"));}
        finally{host.denied=false;}
        check(TraceContext.current()==null,"unbound exceptions and authorization failures do not leak");
        check(!policy.taskContexts.isEmpty()&&policy.taskContexts.stream().allMatch(c->c.principal().kind()==TrustedContext.Kind.TASK&&c.principal().id().equals("registered-principal")&&c.tenantId().equals("t1")&&c.dataScopes().equals(Set.of("tenant:t1"))),"correlation never imports USER authority");
    }
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
                policy.allowed.add(one.sha256());policy.allowed.add(two.sha256());
                try(var taskJournal=new FileStateJournal(root.resolve("tasks"))){
                    var operator=ctx("000000");
                    var definitions=List.of(new ReleaseTaskApplication.Definition("registered","t1","registered-principal","ci.python","echo"),new ReleaseTaskApplication.Definition("foreign","t2","foreign-principal","ci.python","echo"));
                    var tasks=new ReleaseTaskApplication(host,runtime,taskJournal,definitions);
                    taskTraceChecks(tasks,operator,taskJournal,compositionJournal,host,policy);
                    rejected("PERMISSION_DENIED",()->tasks.pin(t1ForTask(),"registered","one",Map.of()));
                    rejected("TASK_DEFINITION_MISSING",()->tasks.pin(operator,"browser-principal","one",Map.of()));
                    var pinned=tasks.pin(operator,"registered","one",Map.of("message","task-input"));
                    check(Schema.map(pinned.get("pinned")).get("release").equals("old"),"trusted task selected stable");
                    check(!pinned.containsKey("input"),"task payload excluded from status");
                    rejected("IDEMPOTENCY_CONFLICT",()->tasks.pin(operator,"registered","one",Map.of("message","changed")));
                    router.put(new ReleasePolicy("product","old","new",10000,Set.of(),Set.of(),true,0),router.revision());
                    var result=tasks.run(operator,"registered","one");
                    check(Schema.map(result.get("result")).get("packageVersion").equals("1.0.0"),"task stays pinned after rollout");
                    var reopened=new ReleaseTaskApplication(host,runtime,taskJournal,definitions);
                    check(reopened.run(operator,"registered","one").equals(result),"durable completed run does not repeat execution");
                    var foreign=tasks.pin(operator,"foreign","one",Map.of("message","foreign"));
                    check(!foreign.get("taskId").equals(pinned.get("taskId")),"same run ID isolated by deployed definition");
                    check(Schema.map(tasks.run(operator,"foreign","one").get("result")).get("packageVersion").equals("1.1.0"),"foreign task uses own trusted routing");
                    policy.allowed.remove(one.sha256());
                    var safeStatus=tasks.status(operator,"registered","one");
                    var safePin=tasks.pin(operator,"registered","one",Map.of("message","task-input"));
                    check(safeStatus.equals(safePin)&&!Json.write(safeStatus).contains("task-input")&&!safeStatus.containsKey("result")&&!safeStatus.containsKey("inputHash"),"revoked task status and pin replay reveal only control metadata");
                    // Corrupt/nested payload fields must not leak through future record expansion.
                    var snapshot=taskJournal.load();var stored=Schema.map(Json.parse(snapshot.json()));
                    for(Object value:Schema.map(stored.get("runs")).values()){
                        var row=Schema.map(value);if(!row.get("definition").equals("registered"))continue;
                        Schema.map(row.get("pinned")).put("result",Map.of("secret","nested-result"));
                        Schema.map(row.get("pinned")).put("input",Map.of("secret","nested-input"));
                    }
                    taskJournal.compareAndSet(snapshot.revision(),Json.write(stored));
                    check(!Json.write(tasks.status(operator,"registered","one")).contains("nested-")&&!Json.write(tasks.pin(operator,"registered","one",Map.of("message","task-input"))).contains("nested-"),"metadata allowlist excludes nested input and result");
                    rejected("PERMISSION_DENIED",()->tasks.run(operator,"registered","one"));
                    policy.allowed.add(one.sha256());
                    router.put(new ReleasePolicy("product","old","new",0,Set.of("t2"),Set.of(),true,0),router.revision());
                    var changed=new ReleaseTaskApplication(host,runtime,taskJournal,List.of(new ReleaseTaskApplication.Definition("registered","t1","changed-principal","ci.python","echo")));
                    rejected("TASK_DEFINITION_CHANGED",()->changed.status(operator,"registered","one"));
                    tasks.pin(operator,"registered","invalid-input",Map.of("undeclared",true));
                    try{tasks.run(operator,"registered","invalid-input");throw new AssertionError("invalid schema accepted");}catch(FcpException expected){checks++;}
                    check(tasks.status(operator,"registered","invalid-input").get("state").equals("UNKNOWN"),"post-admission failure is conservatively uncertain");
                    rejected("TASK_RUN_UNCERTAIN",()->reopened.run(operator,"registered","invalid-input"));

                }
                policy.allowed.remove(two.sha256());
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
                var semantic=new IdentityEchoClassifier(versions,List.of(new IdentityEchoClassifier.Binding("ci.python","echo",one.sha256(),"identity-echo/1")));
                var checkedApp=new PluginReleaseApplication(host,runtime,packages,versions,policy,c->Set.of("fcp:plugin:view","fcp:plugin:echo"),observer,semantic);
                checkedApp.invoke(t1,(String)c1.get("affinity"),export,Map.of("message","actual-identity-echo"),null,g1);
                check(observed.get(observed.size()-1)==fcp.release.observation.RequestObserver.BusinessOutcome.SUCCESS,"exact deployment-pinned identity contract verifies real subprocess output");
                checkedApp.invoke(t2,(String)c2.get("affinity"),export,Map.of("message","unapproved-contract"),null,g2);
                check(observed.get(observed.size()-1)==fcp.release.observation.RequestObserver.BusinessOutcome.UNKNOWN,"other package digest not implicitly classified");
                check(semantic.classifyRequest(t1,"old","ci.python","echo",Map.of("message","different"),output1)==fcp.release.observation.RequestObserver.BusinessOutcome.FAILURE,"echo semantic mismatch is business failure");
                var unknown=new LinkedHashMap<String,Object>(output1);unknown.put("unknownField",true);
                check(semantic.classifyRequest(t1,"old","ci.python","echo",Map.of("message","one"),unknown)==fcp.release.observation.RequestObserver.BusinessOutcome.UNKNOWN,"extra result field never silently healthy");
                var violation=new LinkedHashMap<String,Object>(output1);violation.put("tenantId","another-tenant");
                try{semantic.classifyRequest(t1,"old","ci.python","echo",Map.of("message","one"),violation);throw new AssertionError("Host must reject crossed tenant result");}catch(PluginReleaseApplication.ResultIdentityViolation expected){checks++;}
                if(args.length>2&&args[2].equals("identity-fault")){
                    var window=new fcp.release.observation.WindowedObservation(Clock.systemUTC(),100);
                    window.bind("new",7,router.revision());
                    var faultVerifier=new IdentityEchoClassifier(versions,List.of(new IdentityEchoClassifier.Binding("ci.python","echo",two.sha256(),"identity-echo/1")));
                    var guarded=new PluginReleaseApplication(host,runtime,packages,versions,policy,c->Set.of("fcp:plugin:view","fcp:plugin:echo"),window,faultVerifier);
                    rejected("HOST_RESULT_IDENTITY_VIOLATION",()->guarded.invoke(t2,(String)c2.get("affinity"),export,Map.of("message","synthetic-identity-echo-violation"),null,g2));
                    check(window.confirmedSecurityViolation("new",7),"real fault subprocess is rejected and actual route generation receives security incident");
                    check(!window.confirmedSecurityViolation("new",8),"identity fault does not contaminate another generation");
                }
                rejected("RELEASE_AFFINITY_REQUIRED",()->app.invoke(t1,null,export,Map.of(),null,g1));
                rejected("CAPABILITY_MISSING",()->app.invoke(t1,(String)c1.get("affinity"),"bogus",Map.of(),null,g1));
                policy.allowed.remove(two.sha256());rejected("PERMISSION_DENIED",()->app.invoke(t2,(String)c2.get("affinity"),export,Map.of(),null,g2));
                var revokedProjection=app.catalog(t2,(String)c2.get("affinity"));
                check(!revokedProjection.get("projectionFingerprint").equals(grantedProjection.get("projectionFingerprint"))&&Schema.number(Schema.map(revokedProjection.get("catalog")).get("revision"))==g2&&Schema.list(Schema.map(revokedProjection.get("catalog")).get("pages")).isEmpty(),"same-identity digest revoke produces new empty UI projection at original generation");
                var options=app.grantOptions(admin,new VersionPluginPolicy.Version("ci.python","1.1.0",two.sha256()),"t2","tenant:t2",TrustedContext.Kind.USER,"*");
                check(options.get("digest").equals(two.sha256())&&!Schema.list(options.get("options")).isEmpty(),"approval options bind verified package digest");
                host.denied=true;rejected("PERMISSION_DENIED",()->app.state(admin));host.denied=false;
                policy.allowed.add(two.sha256()); // Explicit reapproval for independent drain scenario.
                try(var drainJournal=new FileStateJournal(root.resolve("drain-tasks"))){
                var drainTasks=new ReleaseTaskApplication(host,runtime,drainJournal,List.of(new ReleaseTaskApplication.Definition("drain","t2","draining-principal","ci.python","echo")));
                drainTasks.pin(ctx("000000"),"drain","one",Map.of("message","drain"));
                var draining=app.beginDrain(admin,"new",Schema.number(app.state(admin).get("revision")));
                rejected("TASKS_PENDING",()->app.stop(admin,"new"));
                var completedDrain=drainTasks.run(ctx("000000"),"drain","one");
                check(Schema.map(completedDrain.get("result")).get("packageVersion").equals("1.1.0"),"already pinned TASK executes during admission drain");

                check(Boolean.TRUE.equals(draining.get("draining"))&&Boolean.FALSE.equals(draining.get("admission")),"drain closes new admission without deleting release");
                check(Schema.number(app.status(admin,"new").get("pendingTasks"))==0,"native status exposes pinned task drain count");
                check(Boolean.TRUE.equals(app.stop(admin,"new").get("stopped")),"drained release stops independently");
                check(Boolean.TRUE.equals(app.status(admin,"old").get("ready")),"other version remains ready");
                }
                router.put(new ReleasePolicy("product","old","new",0,Set.of("t2"),Set.of(),false,1),router.revision());
                rejected("RELEASE_DISABLED",()->app.catalog(t1,(String)c1.get("affinity")));
            }
        }
        check(Schema.number(ProcessProvider.metrics().get("activeProcesses"))==0,"no actual process leak");
        System.out.println("PASS "+checks+" application assertions. Native RuoYi/MySQL/browser NOT_RUN.");
    }
}
