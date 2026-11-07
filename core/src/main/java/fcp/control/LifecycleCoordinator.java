package fcp.control;

import fcp.*;
import fcp.host.*;
import fcp.plugin.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static java.nio.file.StandardOpenOption.*;

/** Single-instance trusted-plugin control plane. File journal is a lightweight development host
 * implementation, NOT a replacement for the production MySQL transaction/CAS adapter.
 * Global serialization deliberately favors correctness over parallel mutation throughput.
 */
public final class LifecycleCoordinator implements AutoCloseable {
    public enum Action { ENABLE, DISABLE }
    public enum DrainDecision { WAIT, CANCEL }
    public record Plan(String tenant, String plugin, Action action, long expectedRevision,
                       List<String> closure, String digest) {
        public Plan { closure=List.copyOf(closure); }
    }
    public record Status(long revision, String installation, String deployment, String desired, String effective) {}
    public record Operation(String id, String phase, String outcome, List<String> plugins) {}
    private final StateJournal journal;
    private long journalRevision;
    private final HostCapabilities host;
    private final RuntimePort runtime;
    private EnablementPolicy enablementPolicy=EnablementPolicy.failClosed();
    private Map<String,Object> state;
    private boolean closed, poisoned;
    private final Set<String> acknowledgedTenants=new HashSet<>();

    public LifecycleCoordinator(Path dir,HostCapabilities host,RuntimePort runtime) throws IOException {
        this(new FileStateJournal(dir),host,runtime);
    }
    public LifecycleCoordinator(StateJournal journal,HostCapabilities host,RuntimePort runtime) throws IOException {
        this.journal=Objects.requireNonNull(journal);this.host=Objects.requireNonNull(host);this.runtime=Objects.requireNonNull(runtime);
        try {
            runtime.resetAdmissions();StateJournal.Snapshot snapshot=journal.load();journalRevision=snapshot.revision();
            state=snapshot.json()!=null?obj(Json.parse(snapshot.json())):new LinkedHashMap<>(Map.of("revision",0,"installs",new LinkedHashMap<>(),"tenants",new LinkedHashMap<>(),"operations",new LinkedHashMap<>(),"deployments",new LinkedHashMap<>()));
            validateState();for(String p:new ArrayList<>(map("deployments").keySet()))map("deployments").put(p,"UNKNOWN");persist();
        }catch(IOException|RuntimeException e){journal.close();throw e;}
    }
    /** Startup-only read-only approval provider. Never installs or enlarges a grant. */
    public synchronized void setEnablementPolicy(EnablementPolicy policy){live();if(!acknowledgedTenants.isEmpty())throw fail("RUNTIME_IN_USE","Configure approval policy before publishing tenants");enablementPolicy=Objects.requireNonNull(policy);}
    /** Internal host recovery discovery only; never accepts a caller-supplied recovery identity. */
    public synchronized Set<String> knownTenants(){live();return Set.copyOf(map("tenants").keySet());}
    public synchronized long revision(){live();return number(state.get("revision"));}
    public synchronized Map<String,Map<String,Object>> installedPlugins(TrustedContext c) {
        live();Map<String,Map<String,Object>> result=new TreeMap<>();
        for(String plugin:map("installs").keySet())result.put(plugin,installedMetadata(c,plugin));
        return Map.copyOf(result);
    }
    public synchronized Map<String,Object> installedMetadata(TrustedContext c,String plugin){
        live();require(c,c.tenantId(),plugin,"status");Object raw=map("installs").get(plugin);if(raw==null)return Map.of("state","ABSENT");Map<String,Object> i=obj(raw);return Map.of("state",i.get("state"),"version",i.get("version"),"digest",i.get("digest"));
    }
    /** Registers only a package already verified by PackageInstaller. No migrations execute here. */
    public synchronized void register(TrustedContext c,PackageInstaller.InstalledPackage pkg,long expected) throws IOException {
        live();String p=pkg.manifest().pluginId();require(c,c.tenantId(),p,"install");cas(expected);
        Object previous=map("installs").get(p);if(previous!=null&&"INSTALLED".equals(obj(previous).get("state"))&&pkg.sha256().equals(obj(previous).get("digest"))&&pkg.manifest().version().equals(obj(previous).get("version"))){runtime.verifyInstalledPackage(pkg);return;}
        for(Object v:map("tenants").values())if(strings(obj(v).get("enabled")).contains(p))throw fail("PLUGIN_IN_USE","Disable every tenant before version replacement");
        if(map("operations").values().stream().anyMatch(v->!terminal(obj(v)) && strings(obj(v).get("plugins")).contains(p)))throw fail("OPERATION_PENDING","Plugin has unfinished operation");
        runtime.verifyInstalledPackage(pkg);
        runtime.verifyStorageCompatibility(p,pkg.manifest().version(),pkg.sha256());
        String raw=Files.readString(pkg.directory().resolve("plugin.json"));
        if(!PluginManifest.parse(raw).equals(pkg.manifest()))throw fail("PACKAGE_MISMATCH","Package manifest differs");
        map("installs").put(p,new LinkedHashMap<>(Map.of("manifest",raw,"version",pkg.manifest().version(),"digest",pkg.sha256(),"state","INSTALLED")));
        map("deployments").put(p,"STOPPED");bump();persist();audit(c,p,"install","INSTALLED");
    }
    public synchronized Plan plan(TrustedContext c,String tenant,String plugin,Action action,long expected) {
        live();require(c,tenant,plugin,"plan");cas(expected);TreeSet<String> closure=new TreeSet<>();
        if(action==Action.ENABLE)dependencies(plugin,closure,new HashSet<>());
        else {
            installed(plugin);closure.add(plugin);boolean changed;
            do {changed=false;for(String p:enabled(tenant))if(!closure.contains(p))for(var d:manifest(p).dependencies())if(closure.contains(d.pluginId())){closure.add(p);changed=true;break;}}while(changed);
        }
        List<Object> authorization=new ArrayList<>();
        for(String p:closure){require(c,tenant,p,action.name().toLowerCase(Locale.ROOT));HostCapabilities.Decision decision=host.authorize(c,new HostCapabilities.Grant(p,"plugin.lifecycle",action.name().toLowerCase(Locale.ROOT),"tenant:"+tenant));if(decision==null||!decision.allowed())throw fail("PERMISSION_DENIED","Authorization changed");authorization.add(List.of(p,decision.currentRevision()));}
        if(action==Action.ENABLE)authorization.add(verifyApprovals(c,closure));
        String material=Json.write(authorization)+"\n"+tenant+"\n"+plugin+"\n"+action+"\n"+expected+"\n"+Json.write(closure.stream().map(p->List.of(p,installed(p).get("digest"))).toList());
        return new Plan(tenant,plugin,action,expected,new ArrayList<>(closure),hash(material));
    }
    /** Explicit closure confirmation cannot add grants: each plugin is authorized again. */
    public synchronized Operation confirm(TrustedContext c,Plan supplied,Set<String> confirmed,String key) {
        live();require(c,supplied.tenant(),supplied.plugin(),"confirm");if(key==null||key.isBlank()||key.length()>256)throw new IllegalArgumentException("idempotency key");
        for(String p:supplied.closure())require(c,supplied.tenant(),p,supplied.action().name().toLowerCase(Locale.ROOT));
        String id=hash(c.principal().kind()+"\n"+c.principal().id()+"\n"+supplied.tenant()+"\n"+key);Object previous=map("operations").get(id);
        if(previous!=null){Map<String,Object> op=obj(previous);for(String p:strings(op.get("plugins")))require(c,(String)op.get("tenant"),p,((String)op.get("action")).toLowerCase(Locale.ROOT));if(!supplied.digest().equals(op.get("planDigest")))throw fail("IDEMPOTENCY_CONFLICT","Different plan for key");audit(c,supplied.plugin(),"lifecycle.replay",(String)op.get("outcome"));return operation(id,op);}
        Plan current=plan(c,supplied.tenant(),supplied.plugin(),supplied.action(),supplied.expectedRevision());
        if(!current.equals(supplied)||!new HashSet<>(current.closure()).equals(confirmed))throw fail("CONFIRMATION_REQUIRED","Exact dependency/cascade closure must be confirmed");
        for(Object v:map("operations").values())if(!terminal(obj(v)) && supplied.tenant().equals(obj(v).get("tenant")))throw fail("OPERATION_PENDING","Tenant operation requires reconciliation");
        Map<String,Object> op=new LinkedHashMap<>();op.put("tenant",supplied.tenant());op.put("plugins",supplied.closure());op.put("action",supplied.action().name());op.put("planDigest",supplied.digest());op.put("phase","INTENT");op.put("outcome","PENDING");op.put("decisions",new ArrayList<>());op.put("drainDeadline",host.clock().instant().plusSeconds(30).toString());
        map("operations").put(id,op);Set<String> desired=enabled(supplied.tenant());if(supplied.action()==Action.ENABLE)desired.addAll(confirmed);else desired.removeAll(confirmed);
        map("tenants").put(supplied.tenant(),new LinkedHashMap<>(Map.of("enabled",new ArrayList<>(desired))));bump();persist();
        execute(c,id,op,false);return operation(id,op);
    }
    public synchronized Operation decideDrain(TrustedContext c,String id,DrainDecision decision) {
        live();Map<String,Object> op=op(id);String tenant=(String)op.get("tenant");
        for(String p:strings(op.get("plugins")))require(c,tenant,p,"drain."+decision.name().toLowerCase(Locale.ROOT));
        if(!"DRAINING".equals(op.get("phase")))throw fail("NOT_DRAINING","Operation is not awaiting drain");
        @SuppressWarnings("unchecked") List<Object> decisions=(List<Object>)op.get("decisions");
        decisions.add(Map.of("decision",decision.name(),"principal",c.principal().id(),"at",host.clock().instant().toString()));persist();
        if(decision==DrainDecision.WAIT)op.put("drainDeadline",host.clock().instant().plusSeconds(30).toString());
        persist();
        if(decision==DrainDecision.CANCEL)for(String p:strings(op.get("plugins")))runtime.cancel(tenant,p);
        audit(c,String.join(",",strings(op.get("plugins"))),"drain."+decision,"REQUESTED");execute(c,id,op,true);return operation(id,op);
    }
    /** Explicit host recovery uses fresh authority. Never trusts historical READY or blindly replays business calls. */
    public synchronized void reconcile(TrustedContext c,String tenant) {
        live();require(c,tenant,"control","reconcile");
        try {
            for(var entry:map("operations").entrySet())if(tenant.equals(obj(entry.getValue()).get("tenant"))&&!terminal(obj(entry.getValue())))execute(c,entry.getKey(),obj(entry.getValue()),false);
            publishDesired(c,tenant);persist();
        }catch(RuntimeException failure){acknowledgedTenants.remove(tenant);try{runtime.publish(tenant,Set.of(),revision());}catch(RuntimeException cutoff){runtime.resetAdmissions();failure.addSuppressed(cutoff);}throw failure;}
    }
    public synchronized Operation operation(TrustedContext c,String id){live();Map<String,Object> op=op(id);for(String p:strings(op.get("plugins")))require(c,(String)op.get("tenant"),p,"status");return operation(id,op);}
    public synchronized Status status(TrustedContext c,String tenant,String plugin) {
        live();require(c,tenant,plugin,"status");String desired=enabled(tenant).contains(plugin)?"ENABLED":"DISABLED";
        String actual=!acknowledgedTenants.contains(tenant)?"UNKNOWN":desired.equals("ENABLED")?"READY":"DISABLED";
        if("READY".equals(actual)){Map<String,Object> i=installed(plugin);if(!runtime.ready(plugin,(String)i.get("version"),(String)i.get("digest"))){actual="UNKNOWN";map("deployments").put(plugin,"UNKNOWN");persist();}}
        for(Object v:map("operations").values()){Map<String,Object> op=obj(v);if(tenant.equals(op.get("tenant"))&&strings(op.get("plugins")).contains(plugin)&&!terminal(op))actual=(String)op.get("phase");}
        Object install=map("installs").get(plugin);return new Status(revision(),install==null?"ABSENT":(String)obj(install).get("state"),(String)map("deployments").getOrDefault(plugin,"UNKNOWN"),desired,actual);
    }
    public synchronized void uninstall(TrustedContext c,String plugin,long expected) {
        live();require(c,c.tenantId(),plugin,"uninstall");cas(expected);installed(plugin);
        for(String tenant:map("tenants").keySet())if(enabled(tenant).contains(plugin)||runtime.activeCalls(tenant,plugin)>0)throw fail("PLUGIN_IN_USE","Disable and drain every tenant first");
        for(Object v:map("operations").values())if(!terminal(obj(v))&&strings(obj(v).get("plugins")).contains(plugin))throw fail("OPERATION_PENDING","Pending operation");
        installed(plugin).put("state","UNINSTALLED");bump();persist();runtime.stop(plugin);map("deployments").put(plugin,"STOPPED");persist();audit(c,plugin,"uninstall","DATA_RETAINED");
    }
    private void execute(TrustedContext c,String id,Map<String,Object> op,boolean draining) {
        String tenant=(String)op.get("tenant");List<String> plugins=strings(op.get("plugins"));
        try {
            for(String p:plugins)require(c,tenant,p,((String)op.get("action")).toLowerCase(Locale.ROOT));
            op.put("phase","PREPARING");persist();publishDesired(c,tenant);
            if("DISABLE".equals(op.get("action"))){
                op.put("phase","DRAINING");persist();
                for(String p:plugins)if(runtime.activeCalls(tenant,p)>0){boolean timeout=!host.clock().instant().isBefore(java.time.Instant.parse((String)op.get("drainDeadline")));op.put("outcome",timeout?"DRAIN_TIMEOUT_DECISION_REQUIRED":"DRAIN_PENDING");persist();audit(c,p,"drain",(String)op.get("outcome"));return;}
                for(String p:plugins)if(map("tenants").keySet().stream().noneMatch(t->enabled(t).contains(p))){runtime.stop(p);map("deployments").put(p,"STOPPED");}
            }
            op.put("phase","COMPLETE");op.put("outcome","APPLIED");persist();audit(c,String.join(",",plugins),"lifecycle","APPLIED");
        }catch(RuntimeException ex){op.put("phase","UNKNOWN");op.put("outcome","OUTCOME_UNKNOWN");acknowledgedTenants.remove(tenant);try{runtime.publish(tenant,Set.of(),revision());}catch(RuntimeException cutoff){runtime.resetAdmissions();ex.addSuppressed(cutoff);}persist();throw ex;}
    }
    private void publishDesired(TrustedContext c,String tenant) {
        Set<String> desired=enabled(tenant);
        Map<String,String> approvals=verifyApprovals(c,desired);
        for(String p:desired){require(c,tenant,p,"enable");Set<String> closure=new TreeSet<>();dependencies(p,closure,new HashSet<>());if(!desired.containsAll(closure))throw fail("DEPENDENCY_BLOCKED","Dependency not enabled");}
        for(String p:desired){if(!approvals.equals(verifyApprovals(c,desired)))throw fail("GRANT_REVISION_CONFLICT","Platform approvals changed before runner preparation");Map<String,Object> i=installed(p);String version=(String)i.get("version"),digest=(String)i.get("digest");runtime.verifyStorageCompatibility(p,version,digest);map("deployments").put(p,"STARTING");persist();runtime.prepare(p,version,digest);if(!runtime.ready(p,version,digest))throw fail("NOT_READY","Runner not ready: "+p);map("deployments").put(p,"READY");persist();}
        if(!approvals.equals(verifyApprovals(c,desired)))throw fail("GRANT_REVISION_CONFLICT","Platform approvals changed during preparation");
        // Authorization is checked a second time immediately before publication.
        for(String p:desired)require(c,tenant,p,"enable");runtime.publish(tenant,Set.copyOf(desired),revision());acknowledgedTenants.add(tenant);
    }
    private Map<String,String> verifyApprovals(TrustedContext context,Set<String> plugins){
        Map<String,PluginManifest> closure=new TreeMap<>();for(String p:plugins)closure.put(p,manifest(p));
        Map<String,PluginManifest> immutable=Map.copyOf(closure);Map<String,String> fingerprints=new TreeMap<>();
        for(var entry:closure.entrySet()){
            String fingerprint=enablementPolicy.verify(context,entry.getValue(),(String)installed(entry.getKey()).get("digest"),immutable);
            if(fingerprint==null||fingerprint.isBlank()||fingerprint.length()>4096)throw fail("CAPABILITY_NOT_GRANTED","Platform approval fingerprint unavailable");
            fingerprints.put(entry.getKey(),fingerprint);
        }
        return fingerprints;
    }
    private void dependencies(String p,Set<String> out,Set<String> visiting){if(visiting.contains(p))throw fail("DEPENDENCY_CYCLE","Dependency cycle: "+p);if(out.contains(p))return;visiting.add(p);for(var dep:manifest(p).dependencies()){if(!PluginManifest.matchesVersion(manifest(dep.pluginId()).version(),dep.versionRange()))throw fail("DEPENDENCY_VERSION","Version conflict: "+dep.pluginId());dependencies(dep.pluginId(),out,visiting);}visiting.remove(p);out.add(p);}
    private PluginManifest manifest(String p){return PluginManifest.parse((String)installed(p).get("manifest"));}
    private Map<String,Object> installed(String p){Object v=map("installs").get(p);if(v==null||!"INSTALLED".equals(obj(v).get("state")))throw fail("NOT_INSTALLED","Missing installed plugin: "+p);return obj(v);}
    private Set<String> enabled(String t){Object v=map("tenants").get(t);return v==null?new TreeSet<>():new TreeSet<>(strings(obj(v).get("enabled")));}
    private void require(TrustedContext c,String tenant,String plugin,String action){if(!c.tenantId().equals(tenant))throw fail("TENANT_MISMATCH","Context tenant differs");host.require(c,new HostCapabilities.Grant(plugin,"plugin.lifecycle",action,"tenant:"+tenant));}
    private void audit(TrustedContext c,String p,String action,String outcome){host.audit(new HostCapabilities.Audit(c.requestId(),c.traceId(),c.tenantId(),c.principal().id(),p,action,outcome,host.clock().instant()));}
    private void cas(long expected){if(revision()!=expected)throw fail("REVISION_CONFLICT","Plan revision changed");}
    private void bump(){state.put("revision",revision()+1);}
    private Map<String,Object> op(String id){Object v=map("operations").get(id);if(v==null)throw fail("NOT_FOUND","Unknown operation");return obj(v);}
    private static boolean terminal(Map<String,Object> op){return "COMPLETE".equals(op.get("phase"));}
    private static Operation operation(String id,Map<String,Object> op){return new Operation(id,(String)op.get("phase"),(String)op.get("outcome"),List.copyOf(strings(op.get("plugins"))));}
    private void live(){if(closed||poisoned)throw fail("JOURNAL_UNAVAILABLE","Coordinator closed or journal requires recovery");}
    private Map<String,Object> map(String key){return obj(state.get(key));}
    @SuppressWarnings("unchecked") private static Map<String,Object> obj(Object o){return (Map<String,Object>)o;}
    @SuppressWarnings("unchecked") private static List<String> strings(Object o){return (List<String>)o;}
    private static long number(Object o){return ((Number)o).longValue();}
    private static FcpException fail(String code,String message){return new FcpException(code,message);}
    private static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private void validateState()throws IOException {
        try {
            if(!state.keySet().equals(Set.of("revision","installs","tenants","operations","deployments"))||number(state.get("revision"))<0)throw new IllegalArgumentException();
            for(Object raw:map("installs").values()){Map<String,Object> i=obj(raw);if(!i.keySet().equals(Set.of("manifest","version","digest","state"))||!Set.of("INSTALLED","UNINSTALLED").contains(i.get("state"))||!((String)i.get("digest")).matches("[0-9a-f]{64}"))throw new IllegalArgumentException();PluginManifest.parse((String)i.get("manifest"));}
            for(Object raw:map("tenants").values()){Map<String,Object> t=obj(raw);if(!t.keySet().equals(Set.of("enabled")))throw new IllegalArgumentException();for(String p:strings(t.get("enabled")))installed(p);}
            for(Object raw:map("operations").values()){Map<String,Object> o=obj(raw);if(!o.keySet().equals(Set.of("tenant","plugins","action","planDigest","phase","outcome","decisions","drainDeadline")))throw new IllegalArgumentException();Action.valueOf((String)o.get("action"));java.time.Instant.parse((String)o.get("drainDeadline"));if(!(o.get("decisions") instanceof List<?>))throw new IllegalArgumentException();for(String p:strings(o.get("plugins")))if(!map("installs").containsKey(p))throw new IllegalArgumentException();}
            for(Object d:map("deployments").values())if(!Set.of("UNKNOWN","STARTING","READY","STOPPED").contains(d))throw new IllegalArgumentException();
        }catch(RuntimeException invalid){throw new IOException("Invalid lifecycle journal schema",invalid);}
    }
    private void persist(){
        try{StateJournal.Snapshot next=journal.compareAndSet(journalRevision,Json.write(state));journalRevision=next.revision();}
        catch(IOException e){poisoned=true;runtime.resetAdmissions();throw fail("JOURNAL_UNAVAILABLE","Durable journal CAS/write failed; restart required");}
    }
    @Override public synchronized void close()throws IOException{if(closed)return;closed=true;try{runtime.resetAdmissions();}finally{journal.close();}}
}
