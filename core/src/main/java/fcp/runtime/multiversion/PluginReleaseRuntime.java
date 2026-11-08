package fcp.runtime.multiversion;

import fcp.*;
import fcp.control.StateJournal;
import fcp.host.*;
import fcp.plugin.*;
import fcp.runtime.*;
import fcp.release.routing.DurableReleaseRouter;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static fcp.Schema.*;

/** Multiple immutable plugin compositions over isolated instances of the existing Engine runtime.
 * The router is the SAME trusted release decision used by the host UI, not a second bucketing policy.
 */
public final class PluginReleaseRuntime implements AutoCloseable {
    public record Version(String pluginId,String version,String digest) {
        public Version {if(pluginId==null||!pluginId.matches("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*")||version==null||version.isBlank()||digest==null||!digest.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid plugin version");}
        public Map<String,Object> json(){return Map.of("pluginId",pluginId,"version",version,"digest",digest);}
    }
    public record Release(String id,Map<String,Version> plugins) {
        public Release {if(id==null||!id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")||plugins==null||plugins.size()>64)throw new IllegalArgumentException("Invalid plugin release");plugins=Map.copyOf(plugins);for(var e:plugins.entrySet())if(!e.getKey().equals(e.getValue().pluginId()))throw new IllegalArgumentException("Plugin key mismatch");}
        public Map<String,Object> json(){Map<String,Object> p=new TreeMap<>();plugins.forEach((k,v)->p.put(k,v.json()));return Map.of("id",id,"plugins",p);}
    }
    @FunctionalInterface public interface RuntimeFactory { EngineRuntimePort open(String releaseId,HostCapabilities scopedHost)throws IOException; }
    @FunctionalInterface public interface VersionAuthorization { HostCapabilities.Decision authorize(TrustedContext context,Version version,HostCapabilities.Grant grant); }
    @FunctionalInterface public interface PackageResolver { PackageInstaller.InstalledPackage resolve(Version version)throws IOException; }
    public record Selection(DurableReleaseRouter.Decision decision,EngineRuntimePort.CatalogSnapshot snapshot,HostCapabilities host) {}
    private final HostCapabilities host;private final StateJournal journal;private final DurableReleaseRouter router;
    private final RuntimeFactory factory;private final VersionAuthorization authorization;private final PackageResolver packages;
    private final Map<String,Instance> instances=new ConcurrentHashMap<>();
    private final ThreadLocal<Lease> lease=new ThreadLocal<>();private volatile boolean closed;
    private record Lease(String release,String affinity,boolean task) {}
    private static final class Instance { final Release release;final EngineRuntimePort runtime;final HostCapabilities host;final Set<String> tenants=new HashSet<>();volatile boolean enabled;Instance(Release r,EngineRuntimePort p,HostCapabilities h){release=r;runtime=p;host=h;} }
    private record State(long revision,Map<String,Object> value){}
    public PluginReleaseRuntime(HostCapabilities host,StateJournal journal,DurableReleaseRouter router,RuntimeFactory factory,VersionAuthorization authorization,PackageResolver packages){this.host=Objects.requireNonNull(host);this.journal=Objects.requireNonNull(journal);this.router=Objects.requireNonNull(router);this.factory=Objects.requireNonNull(factory);this.authorization=Objects.requireNonNull(authorization);this.packages=Objects.requireNonNull(packages);}
    private State load()throws IOException{var s=journal.load();Map<String,Object> v=s.json()==null?new LinkedHashMap<>(Map.of("format",1,"releases",new LinkedHashMap<>(),"tasks",new LinkedHashMap<>(),"audit",new ArrayList<>())):map(Json.parse(s.json()));if(number(v.get("format"))!=1)throw new IOException("PLUGIN_RELEASE_STATE_FORMAT");return new State(s.revision(),v);}
    private State save(State s)throws IOException{return new State(journal.compareAndSet(s.revision(),Json.write(s.value())).revision(),s.value());}
    private void admin(TrustedContext c){host.require(c,new HostCapabilities.Grant("control","system.release","manage","tenant:"+c.tenantId()));}
    private void live(){if(closed)throw new FcpException("CLOSED","Plugin release runtime closed");}
    public synchronized long register(TrustedContext actor,Release release,long expected)throws IOException {
        live();admin(actor);State s=load();Map<String,Object> all=map(s.value().get("releases"));Object old=all.get(release.id());
        if(old!=null){if(!map(old).get("release").equals(Json.copy(release.json())))throw new FcpException("RELEASE_IMMUTABLE","Release composition already registered");return s.revision();}
        if(s.revision()!=expected)throw new FcpException("RELEASE_REVISION_CONFLICT","Plugin releases changed");
        validate(release);all.put(release.id(),new LinkedHashMap<>(Map.of("release",release.json(),"enabled",false)));audit(s,actor,"REGISTER",release.id());return save(s).revision();
    }
    private void validate(Release release)throws IOException {
        Map<String,PluginManifest> manifests=new HashMap<>();for(Version v:release.plugins().values()){var p=packages.resolve(v);if(!p.manifest().pluginId().equals(v.pluginId())||!p.manifest().version().equals(v.version())||!p.sha256().equals(v.digest()))throw new IOException("PACKAGE_IDENTITY_MISMATCH");manifests.put(v.pluginId(),p.manifest());}
        for(var manifest:manifests.values())for(var dep:manifest.dependencies()){PluginManifest target=manifests.get(dep.pluginId());if(target==null||!PluginManifest.matchesVersion(target.version(),dep.versionRange()))throw new FcpException("DEPENDENCY_VERSION_MISMATCH","Release dependency absent or incompatible");}
    }
    /** Prepare all actual processes before opening any tenant. Each release owns its own Engine/Registry/journal. */
    public synchronized long activate(TrustedContext actor,String releaseId,long expected)throws IOException {
        live();admin(actor);State s=load();if(s.revision()!=expected)throw new FcpException("RELEASE_REVISION_CONFLICT","Plugin releases changed");var record=record(s,releaseId);Release release=parseRelease(map(record.get("release")));validate(release);
        if(!instances.containsKey(releaseId)){verifyPriorProcesses(record);record.put("activationState","PREPARING");s=save(s);record=record(s,releaseId);}
        Instance instance;try{instance=prepare(release);}catch(ActivationFailure failure){record.put("enabled",false);record.put("activationState","PREPARE_FAILED");record.put("processes",Map.of());audit(s,actor,"PREPARE_FAILED",releaseId);save(s);if(failure.getCause() instanceof RuntimeException runtime)throw runtime;throw failure;}record.put("enabled",true);record.put("draining",false);record.put("activationState","ACTIVE");record.put("processes",processes(instance));audit(s,actor,"ACTIVATE",releaseId);long revision=save(s).revision();instance.enabled=true;return revision;
    }
    private static final class ActivationFailure extends IOException { ActivationFailure(Exception cause){super("Preparation failed and all opened runtimes were closed",cause);} }
    private Instance prepare(Release release)throws IOException {
        Instance existing=instances.get(release.id());if(existing!=null){for(Version version:release.plugins().values())if(!existing.runtime.ready(version.pluginId(),version.version(),version.digest()))throw new FcpException("RELEASE_NOT_READY","Existing release process is unhealthy; drain and reconcile before restarting");return existing;}
        HostCapabilities scoped=scopedHost(release);EngineRuntimePort runtime=factory.open(release.id(),scoped);Instance instance=new Instance(release,runtime,scoped);
        try{for(Version v:release.plugins().values()){var pkg=packages.resolve(v);runtime.verifyInstalledPackage(pkg);runtime.verifyStorageCompatibility(v.pluginId(),v.version(),v.digest());runtime.prepare(v.pluginId(),v.version(),v.digest());if(!runtime.ready(v.pluginId(),v.version(),v.digest()))throw new IOException("PLUGIN_RELEASE_NOT_READY");}instances.put(release.id(),instance);return instance;}
        catch(IOException|RuntimeException failure){runtime.close();throw new ActivationFailure(failure);}
    }
    private interface FencedHost extends HostCapabilities,HostCommitFence {}
    private HostCapabilities scopedHost(Release release){return new FencedHost(){
        public Descriptor descriptor(){return host.descriptor();}public java.time.Clock clock(){return host.clock();}public void audit(Audit event){host.audit(event);}
        public Object commit(TrustedContext context,java.util.function.Supplier<Object> operation){
            synchronized(router){try{
                var policy=router.policy();if(policy.isEmpty()||!policy.get().enabled())throw new FcpException("RELEASE_DISABLED","Release commit gate closed");
                Lease current=lease.get();if(current==null||!current.release().equals(release.id()))throw new FcpException("TRUSTED_CONTEXT_REQUIRED","Release invocation lease required");
                if(!current.task())router.resume(context,current.affinity());
                return operation.get();
            }catch(IOException e){throw new FcpException("RELEASE_UNAVAILABLE","Cannot verify release commit fence",e);}}
        }
        public Decision authorize(TrustedContext context,Grant grant){
            Instance i=instances.get(release.id());Lease current=lease.get();
            if(i==null||(!i.enabled&&(current==null||!current.release().equals(release.id()))))return new Decision(false,0);
            try{if(current!=null&&current.release().equals(release.id())){if(current.task()){if(router.policy().isEmpty()||!router.policy().get().enabled())return new Decision(false,0);}else if(!router.resume(context,current.affinity()).releaseId().equals(release.id()))return new Decision(false,0);}}
            catch(IOException|RuntimeException failure){return new Decision(false,0);}
            if(grant.capability().equals("plugin.lifecycle"))return host.authorize(context,grant);
            Version v=release.plugins().get(grant.pluginId());return v==null?new Decision(false,0):authorization.authorize(context,v,grant);
        }
    };}
    /** Recover only previously approved enabled compositions. Call during host startup before serving requests. */
    public synchronized void recover()throws IOException {
        live();State s=load();for(Object raw:map(s.value().get("releases")).values()){var r=map(raw);
            if("PREPARING".equals(r.get("activationState")))throw new IOException("PLUGIN_ACTIVATION_OUTCOME_UNKNOWN");
            if(Boolean.TRUE.equals(r.get("enabled"))){Release release=parseRelease(map(r.get("release")));validate(release);if(!instances.containsKey(release.id()))verifyPriorProcesses(r);Instance i=prepare(release);i.enabled=true;r.put("processes",processes(i));}}
        save(s);
    }
    private Map<String,Object> processes(Instance instance){Map<String,Object> out=new TreeMap<>();for(String plugin:instance.release.plugins().keySet()){var h=instance.runtime.health(plugin);if(!Boolean.TRUE.equals(h.get("ready"))||h.get("pid")==null||h.get("startedAt")==null)throw new FcpException("RUNNER_IDENTITY","Live process identity required");out.put(plugin,Map.of("pid",h.get("pid"),"startedAt",h.get("startedAt")));}return out;}
    private void verifyPriorProcesses(Map<String,Object> record)throws IOException {
        if("PREPARING".equals(record.get("activationState")))throw new IOException("PLUGIN_ACTIVATION_OUTCOME_UNKNOWN");
        for(Object raw:map(record.getOrDefault("processes",Map.of())).values()){var identity=map(raw);long pid=number(identity.get("pid"));Instant started=Instant.parse(str(identity.get("startedAt")));
            Optional<ProcessHandle> process=ProcessHandle.of(pid);if(process.isPresent()&&process.get().isAlive()){
                Optional<Instant> actual=process.get().info().startInstant();if(actual.isEmpty())throw new IOException("PLUGIN_PROCESS_IDENTITY_UNKNOWN");
                if(actual.get().equals(started))throw new IOException("PLUGIN_ORPHAN_RUNNING_REQUIRES_RECONCILIATION");
            }
        }
    }
    private Instance selected(TrustedContext c,DurableReleaseRouter.Decision decision)throws IOException {
        live();State s=load();var r=record(s,decision.releaseId());if(!Boolean.TRUE.equals(r.get("enabled"))||Boolean.TRUE.equals(r.get("draining")))throw new FcpException("RELEASE_DISABLED","Plugin release admissions closed");
        Instance i=instances.get(decision.releaseId());if(i==null||!i.enabled)throw new FcpException("RELEASE_NOT_READY","Plugin release recovery/activation required");
        synchronized(this){i.runtime.publish(c.tenantId(),i.release.plugins().keySet(),1);i.tenants.add(c.tenantId());}
        return i;
    }
    public Selection catalog(TrustedContext c,String affinity)throws IOException {
        var decision=affinity==null?router.decide(c):router.renew(c,affinity);Instance i=selected(c,decision);lease.set(new Lease(i.release.id(),decision.affinity(),false));
        try{return new Selection(decision,i.runtime.snapshot(c),selectionHost(i.host,decision.affinity()));}finally{lease.remove();}
    }
    public Object invoke(TrustedContext c,String affinity,String plugin,String capability,Map<String,Object> input,String key,long expectedGeneration)throws IOException {
        if(affinity==null)throw new FcpException("RELEASE_AFFINITY_REQUIRED","Use authenticated release catalog first");
        var decision=router.resume(c,affinity);Instance i=selected(c,decision);lease.set(new Lease(i.release.id(),affinity,false));
        try{return i.runtime.invoke(c,plugin,capability,input,key,expectedGeneration);}finally{lease.remove();}
    }
    private HostCapabilities selectionHost(HostCapabilities scoped,String affinity){return new HostCapabilities(){
        public Descriptor descriptor(){return scoped.descriptor();}public java.time.Clock clock(){return scoped.clock();}public void audit(Audit event){scoped.audit(event);}
        public Decision authorize(TrustedContext context,Grant grant){try{router.resume(context,affinity);return scoped.authorize(context,grant);}catch(IOException|RuntimeException failure){return new Decision(false,0);}}
    };}
    private static String taskKey(TrustedContext c,String id){if(id==null||!id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}"))throw new IllegalArgumentException("Invalid task ID");return VerifiedPackageRepository.hash(Json.write(List.of(c.tenantId(),c.principal().id(),id)).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private static String fingerprint(Object input){return fcp.release.version.VersionCodec.sha256(fcp.release.version.VersionCodec.canonical(input));}
    /** Persist task affinity before scheduling. TASK identity and approval are distinct from USER grants. */
    public synchronized Map<String,Object> pinTask(TrustedContext context,String affinity,String taskId,String plugin,String capability,Map<String,Object> input)throws IOException {
        if(context.principal().kind()!=TrustedContext.Kind.TASK)throw new FcpException("TASK_IDENTITY_REQUIRED","Authenticated task principal required");
        String key=taskKey(context,taskId),hash=fingerprint(input);State s=load();Object previous=map(s.value().get("tasks")).get(key);
        if(previous!=null){var p=map(previous);if("CANCELLED".equals(p.get("state")))throw new FcpException("TASK_CANCELLED","Task was cancelled before execution");if(!p.get("plugin").equals(plugin)||!p.get("capability").equals(capability)||!p.get("inputHash").equals(hash))throw new FcpException("IDEMPOTENCY_CONFLICT","Task payload changed");String releaseId=str(p.get("release"));Instance prior=instances.get(releaseId);if(prior==null||!prior.enabled||router.policy().isEmpty()||!router.policy().get().enabled())throw new FcpException("RELEASE_DISABLED","Pinned release not admitted");prior.host.require(context,new HostCapabilities.Grant(plugin,capability,"invoke","tenant:"+context.tenantId()));var copy=map(Json.copy(p));copy.remove("result");return copy;}
        var decision=affinity==null?router.decide(context):router.resume(context,affinity);Instance instance=selected(context,decision);
        Version v=instance.release.plugins().get(plugin);if(v==null)throw new FcpException("PLUGIN_NOT_IN_RELEASE","Plugin not part of selected release");
        lease.set(new Lease(instance.release.id(),decision.affinity(),true));try{instance.host.require(context,new HostCapabilities.Grant(plugin,capability,"invoke","tenant:"+context.tenantId()));}finally{lease.remove();}
        var record=new LinkedHashMap<String,Object>(Map.of("release",instance.release.id(),"plugin",plugin,"capability",capability,"version",v.version(),"digest",v.digest(),"inputHash",hash,"state","PINNED","taskId",taskId));
        record.put("executionTracking",1);record.put("tenant",context.tenantId());record.put("principal",context.principal().id());map(s.value().get("tasks")).put(key,record);save(s);return map(Json.copy(record));
    }
    /** A retry remains on its original package. UNKNOWN writes are reconciled by the same invocation journal, never rerouted. */
    public Object invokeTask(TrustedContext context,String taskId,Map<String,Object> input)throws IOException {
        if(context.principal().kind()!=TrustedContext.Kind.TASK)throw new FcpException("TASK_IDENTITY_REQUIRED","Authenticated task principal required");
        String key=taskKey(context,taskId);Map<String,Object> task;
        synchronized(this){Object value=map(load().value().get("tasks")).get(key);if(value==null)throw new FcpException("TASK_NOT_FOUND","Pin task before execution");task=map(Json.copy(value));}
        if("CANCELLED".equals(task.get("state")))throw new FcpException("TASK_CANCELLED","Task was cancelled before execution");
        if(!fingerprint(input).equals(task.get("inputHash")))throw new FcpException("IDEMPOTENCY_CONFLICT","Task payload changed");
        if(router.policy().isEmpty()||!router.policy().get().enabled())throw new FcpException("RELEASE_DISABLED","Emergency release ingress is closed");
        String releaseId=str(task.get("release")),plugin=str(task.get("plugin")),cap=str(task.get("capability"));Instance instance;
        synchronized(this){if(!Boolean.TRUE.equals(record(load(),releaseId).get("enabled")))throw new FcpException("RELEASE_DISABLED","Pinned task release disabled");instance=instances.get(releaseId);if(instance==null||!instance.enabled)throw new FcpException("RELEASE_NOT_READY","Pinned runtime requires recovery");instance.runtime.publish(context.tenantId(),instance.release.plugins().keySet(),1);instance.tenants.add(context.tenantId());}
        Version version=instance.release.plugins().get(plugin);if(version==null||!version.digest().equals(task.get("digest")))throw new FcpException("TASK_VERSION_MISMATCH","Pinned package identity mismatch");
        lease.set(new Lease(releaseId,null,true));
        try{
            instance.host.require(context,new HostCapabilities.Grant(plugin,cap,"invoke","tenant:"+context.tenantId()));
            synchronized(this){State current=load();var saved=map(map(current.value().get("tasks")).get(key));
                if("CANCELLED".equals(saved.get("state")))throw new FcpException("TASK_CANCELLED","Task cancelled before admission");
                if("COMPLETE".equals(saved.get("state")))return saved.get("result");
                saved.put("state","EXECUTING");audit(current,context,"TASK_EXECUTING:"+key,releaseId);save(current);
            }
            Object result;
            try{result=instance.runtime.invoke(context,plugin,cap,input,"release-task:"+key,null);}catch(RuntimeException failure){synchronized(this){State current=load();var saved=map(map(current.value().get("tasks")).get(key));if(!"COMPLETE".equals(saved.get("state"))){saved.put("state","UNKNOWN");audit(current,context,"TASK_UNKNOWN:"+key,releaseId);save(current);}}throw failure;}
            synchronized(this){State s=load();var saved=map(map(s.value().get("tasks")).get(key));saved.put("state","COMPLETE");saved.put("result",Json.copy(result));audit(s,context,"TASK_COMPLETE:"+key,releaseId);save(s);}return result;
        }finally{lease.remove();}
    }
    /** Cancel only a provably never-started tracked task. No cancellation of legacy/uncertain effects. */
    public synchronized Map<String,Object> cancelTask(TrustedContext actor,String key,long expected,String reason)throws IOException {
        admin(actor);if(actor.principal().kind()!=TrustedContext.Kind.USER)throw new FcpException("PERMISSION_DENIED","User release operator required");
        if(key==null||!key.matches("[a-f0-9]{64}")||reason==null||reason.isBlank()||reason.length()>500)throw new IllegalArgumentException("Task key and bounded reason required");
        State state=load();if(state.revision()!=expected)throw new FcpException("RELEASE_REVISION_CONFLICT","Task state changed");
        Object raw=map(state.value().get("tasks")).get(key);if(raw==null)throw new FcpException("TASK_NOT_FOUND","Unknown task");var task=map(raw);
        if(!"PINNED".equals(task.get("state"))||!(task.get("executionTracking") instanceof Number tracking&&tracking.doubleValue()==1.0))throw new FcpException("TASK_RECONCILIATION_REQUIRED","Only a tracked task that never began execution can be cancelled");
        task.put("state","CANCELLED");task.put("cancellation",Map.of("actor",actor.principal().id(),"reason",reason,"at",host.clock().instant().toString()));audit(state,actor,"TASK_CANCELLED:"+key,str(task.get("release")));state=save(state);
        return Map.of("taskKey",key,"state","CANCELLED","revision",state.revision());
    }
    /** Close only new work. Already pinned tasks retain their original version and live authorization. */
    public synchronized Map<String,Object> beginDrain(TrustedContext actor,String releaseId,long expected)throws IOException {
        admin(actor);State s=load();if(s.revision()!=expected)throw new FcpException("RELEASE_REVISION_CONFLICT","Plugin releases changed");
        var r=record(s,releaseId);if(!Boolean.TRUE.equals(r.get("enabled")))throw new FcpException("RELEASE_DISABLED","Cannot drain a disabled release");
        r.put("draining",true);audit(s,actor,"BEGIN_DRAIN",releaseId);save(s);return status(actor,releaseId);
    }
    private long pendingTasks(State state,String releaseId){return map(state.value().get("tasks")).values().stream().map(fcp.Schema::map).filter(t->releaseId.equals(t.get("release"))&&!Set.of("COMPLETE","CANCELLED").contains(t.get("state"))).count();}
    /** Close new admissions before drain. Existing unknown effects and plugin data are retained. */
    public synchronized Map<String,Object> disable(TrustedContext actor,String releaseId,long expected)throws IOException {
        admin(actor);State s=load();if(s.revision()!=expected)throw new FcpException("RELEASE_REVISION_CONFLICT","Plugin releases changed");var r=record(s,releaseId);r.put("enabled",false);audit(s,actor,"DISABLE",releaseId);s=save(s);Instance i=instances.get(releaseId);if(i!=null){i.enabled=false;if(active(i)==0)for(String tenant:i.tenants)i.runtime.publish(tenant,Set.of(),2);}
        return Map.of("revision",s.revision(),"releaseId",releaseId,"activeCalls",active(i),"stopped",false);
    }
    public synchronized Map<String,Object> stop(TrustedContext actor,String releaseId)throws IOException {
        admin(actor);State s=load();if(Boolean.TRUE.equals(record(s,releaseId).get("enabled"))&&!Boolean.TRUE.equals(record(s,releaseId).get("draining")))throw new FcpException("RELEASE_IN_USE","Close release admissions before stop");if(pendingTasks(s,releaseId)>0)throw new FcpException("TASKS_PENDING","Pinned tasks must complete before stop");Instance i=instances.get(releaseId);if(active(i)>0)throw new FcpException("RUNNER_BUSY","Release still has in-flight calls");
        if(i==null)verifyPriorProcesses(record(s,releaseId));
        if(i!=null){for(String tenant:i.tenants)i.runtime.publish(tenant,Set.of(),2);for(String p:i.release.plugins().keySet())i.runtime.stop(p);i.runtime.close();instances.remove(releaseId);}
        State current=load();var stopped=record(current,releaseId);stopped.put("enabled",false);stopped.put("draining",false);stopped.put("activationState","STOPPED");stopped.put("processes",Map.of());audit(current,actor,"STOP",releaseId);save(current);
        return Map.of("releaseId",releaseId,"stopped",true);
    }
    private long active(Instance i){if(i==null)return 0;long count=0;for(String tenant:i.tenants)for(String plugin:i.release.plugins().keySet())count+=i.runtime.activeCalls(tenant,plugin);return count;}
    public synchronized Map<String,Object> status(TrustedContext actor,String releaseId)throws IOException {
        admin(actor);State state=load();var record=record(state,releaseId);Instance instance=instances.get(releaseId);boolean ready=instance!=null&&instance.enabled;Map<String,Object> health=new TreeMap<>();
        if(instance!=null)for(Version v:instance.release.plugins().values()){boolean ok=instance.runtime.ready(v.pluginId(),v.version(),v.digest());ready&=ok;health.put(v.pluginId(),instance.runtime.health(v.pluginId()));}
        return Map.of("releaseId",releaseId,"revision",state.revision(),"enabled",Boolean.TRUE.equals(record.get("enabled")),"ready",ready,"activeCalls",active(instance),"processes",health,"activationState",record.getOrDefault("activationState","REGISTERED"),"draining",Boolean.TRUE.equals(record.get("draining")),"admission",Boolean.TRUE.equals(record.get("enabled"))&&!Boolean.TRUE.equals(record.get("draining")),"pendingTasks",pendingTasks(state,releaseId));
    }
    public synchronized Map<String,Object> state(TrustedContext actor)throws IOException{admin(actor);State s=load();Map<String,Object> out=map(Json.copy(s.value()));for(Object raw:map(out.get("tasks")).values())map(raw).remove("result");out.put("revision",s.revision());return out;}
    private static Map<String,Object> record(State s,String id){Object r=map(s.value().get("releases")).get(id);if(r==null)throw new FcpException("RELEASE_NOT_FOUND","Plugin release not registered");return map(r);}
    public static Release parseRelease(Map<String,Object> raw){if(!raw.keySet().equals(Set.of("id","plugins")))throw new IllegalArgumentException("Release fields");Map<String,Version> versions=new TreeMap<>();for(var e:map(raw.get("plugins")).entrySet()){var v=map(e.getValue());if(!v.keySet().equals(Set.of("pluginId","version","digest")))throw new IllegalArgumentException("Version fields");versions.put(e.getKey(),new Version(str(v.get("pluginId")),str(v.get("version")),str(v.get("digest"))));}return new Release(str(raw.get("id")),versions);}
    private void audit(State s,TrustedContext c,String action,String release){list(s.value().get("audit")).add(Map.of("actor",c.principal().id(),"tenant",c.tenantId(),"traceId",c.traceId(),"action",action,"release",release,"at",host.clock().instant().toString()));}
    @Override public synchronized void close()throws IOException{if(closed)return;closed=true;IOException failed=null;for(Instance i:instances.values())try{i.runtime.close();}catch(IOException e){failed=e;}instances.clear();journal.close();if(failed!=null)throw failed;}
}
