package dev.fcp.host;

import fcp.*;
import fcp.host.*;
import fcp.plugin.PluginManifest;
import fcp.release.version.*;
import fcp.release.observation.*;
import fcp.runtime.EngineRuntimePort;
import fcp.runtime.VerifiedPackageRepository;
import fcp.runtime.multiversion.PluginReleaseRuntime;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/** Authenticated HTTP boundary for package-version approvals and a shared UI/backend release selection. */
public final class PluginReleaseApplication {
    @FunctionalInterface public interface BusinessClassifier {
        RequestObserver.BusinessOutcome classify(TrustedContext caller,String release,String plugin,String capability,Object result);
    }
    private final RequestObserver observer;private final BusinessClassifier classifier;
    private final HostCapabilities host;private final PluginReleaseRuntime runtime;private final VerifiedPackageRepository packages;
    private final VersionProvider versions;private final VersionPluginPolicy policy;private final Function<TrustedContext,Set<String>> permissions;
    public PluginReleaseApplication(HostCapabilities host,PluginReleaseRuntime runtime,VerifiedPackageRepository packages,VersionProvider versions,VersionPluginPolicy policy,Function<TrustedContext,Set<String>> permissions){this(host,runtime,packages,versions,policy,permissions,RequestObserver.NONE,null);}
    public PluginReleaseApplication(HostCapabilities host,PluginReleaseRuntime runtime,VerifiedPackageRepository packages,VersionProvider versions,VersionPluginPolicy policy,Function<TrustedContext,Set<String>> permissions,RequestObserver observer,BusinessClassifier classifier){this.host=Objects.requireNonNull(host);this.runtime=Objects.requireNonNull(runtime);this.packages=Objects.requireNonNull(packages);this.versions=Objects.requireNonNull(versions);this.policy=Objects.requireNonNull(policy);this.permissions=Objects.requireNonNull(permissions);this.observer=Objects.requireNonNull(observer);this.classifier=classifier;}
    private void admin(TrustedContext c){if(c.principal().kind()!=TrustedContext.Kind.USER)throw new FcpException("PERMISSION_DENIED","USER release administrator required");host.require(c,new HostCapabilities.Grant("control","system.release","manage","tenant:"+c.tenantId()));}
    private void view(TrustedContext c){host.require(c,new HostCapabilities.Grant("control","plugin.lifecycle","view","tenant:"+c.tenantId()));}
    public Map<String,Object> cancelTask(TrustedContext context,String key,long expected,String reason)throws IOException { admin(context);return runtime.cancelTask(context,key,expected,reason); }
    public Map<String,Object> beginDrain(TrustedContext context,String id,long expected)throws IOException { admin(context);return runtime.beginDrain(context,id,expected); }
    public Map<String,Object> status(TrustedContext c,String release)throws IOException{admin(c);return runtime.status(c,release);}
    /** Mode switching is not a migration of active legacy intentions. Reconcile and disable them first. */
    public static Set<String> requireLegacyQuiescent(String json) {
        if(json==null)return Set.of();var state=Schema.map(Json.parse(json));
        for(Object raw:Schema.map(state.get("tenants")).values())if(!Schema.list(Schema.map(raw).get("enabled")).isEmpty())throw new FcpException("MODE_CONFLICT","Disable every legacy tenant plugin before enabling release mode");
        for(String group:List.of("operations","versionOperations"))for(Object raw:Schema.map(state.getOrDefault(group,Map.of())).values())if(!"COMPLETE".equals(Schema.map(raw).get("phase")))throw new FcpException("MODE_CONFLICT","Reconcile pending legacy lifecycle/version operations before switching mode");
        return Set.copyOf(Schema.map(state.get("installs")).keySet());
    }
    public Map<String,Object> state(TrustedContext c)throws IOException{admin(c);return runtime.state(c);}
    public Map<String,Object> register(TrustedContext c,Map<String,Object> raw,long expected)throws IOException{
        admin(c);var release=PluginReleaseRuntime.parseRelease(raw);validateSystemRelease(release);
        return Map.of("revision",runtime.register(c,release,expected),"releaseId",release.id());
    }
    /** System release metadata is not a hint: every declared plugin artifact must match verified package bytes. */
    private void validateSystemRelease(PluginReleaseRuntime.Release release)throws IOException{
        var system=versions.release(release.id()).orElseThrow(()->new FcpException("SYSTEM_RELEASE_NOT_FOUND","Register the system release composition first"));
        Map<String,PluginReleaseRuntime.Version> declared=new TreeMap<>();
        for(String artifactId:system.components().values()){
            var a=versions.artifact(artifactId).orElseThrow(()->new FcpException("ARTIFACT_NOT_FOUND","System composition artifact missing"));
            if(a.kind()!=SystemArtifact.Kind.PLUGIN)continue;
            var v=new PluginReleaseRuntime.Version(a.component(),a.version(),a.sha256());
            if(declared.put(a.component(),v)!=null)throw new FcpException("AMBIGUOUS_PLUGIN_VERSION","System release contains duplicate plugin components");
            var pkg=packages.resolve(v.pluginId(),v.version(),v.digest());
            if(!pkg.sha256().equals(a.sha256()))throw new FcpException("PACKAGE_INTEGRITY","System release package digest mismatch");
        }
        if(!declared.equals(release.plugins()))throw new FcpException("SYSTEM_RELEASE_PLUGIN_MISMATCH","Runtime plugins differ from immutable system composition");
    }
    public Map<String,Object> activate(TrustedContext c,String id,long expected)throws IOException{admin(c);return Map.of("revision",runtime.activate(c,id,expected),"releaseId",id);}
    public Map<String,Object> disable(TrustedContext c,String id,long expected)throws IOException{admin(c);return runtime.disable(c,id,expected);}
    public Map<String,Object> stop(TrustedContext c,String id)throws IOException{admin(c);return runtime.stop(c,id);}
    public Map<String,Object> catalog(TrustedContext c,String affinity)throws IOException{
        view(c);var selected=runtime.catalog(c,affinity);
        Map<String,Object> result=new LinkedHashMap<>(PluginCatalog.project(selected.host(),c,selected.snapshot().enabledManifests(),selected.snapshot().generation(),permissions.apply(c)));
        result.put("releaseId",selected.decision().releaseId());result.put("affinity",selected.decision().affinity());result.put("releasePolicyRevision",selected.decision().revision());return result;
    }
    public Object invoke(TrustedContext c,String affinity,String exported,Map<String,Object> input,String idempotencyKey,long expected)throws IOException{
        view(c);if(affinity==null||affinity.isBlank())throw new FcpException("RELEASE_AFFINITY_REQUIRED","Load an authenticated release catalog first");
        var selected=runtime.catalog(c,affinity);
        String plugin=null,capability=null;
        for(var e:selected.snapshot().enabledManifests().entrySet())for(var cap:e.getValue().provides())if(EngineRuntimePort.export(e.getKey(),cap.id()).equals(exported)){if(plugin!=null)throw new FcpException("CAPABILITY_AMBIGUOUS","Duplicate exported capability");plugin=e.getKey();capability=cap.id();}
        if(plugin==null)throw new FcpException("CAPABILITY_MISSING","Capability not in the selected release");
        final String selectedPlugin=plugin,selectedCapability=capability;
        boolean mayWrite=selected.snapshot().enabledManifests().get(plugin).provides().stream().filter(spec->spec.id().equals(selectedCapability)).findFirst().orElseThrow().sideEffects();
        return ObservedInvocation.invoke(observer,selected.decision(),mayWrite,
            ()->runtime.invoke(c,affinity,selectedPlugin,selectedCapability,input,idempotencyKey,expected),
            result->classifier==null?RequestObserver.BusinessOutcome.UNKNOWN:classifier.classify(c,selected.decision().releaseId(),selectedPlugin,selectedCapability,result));
    }
    public Map<String,Object> grantOptions(TrustedContext c,VersionPluginPolicy.Version version,String targetTenant,String scope,TrustedContext.Kind kind,String principal)throws IOException{
        admin(c);PluginManifest manifest=packages.resolve(version.pluginId(),version.version(),version.digest()).manifest();
        Map<String,PluginManifest.Capability> capabilities=new TreeMap<>();
        for(var cap:manifest.provides())capabilities.put(cap.id(),cap);
        for(var cap:manifest.requires()){var old=capabilities.putIfAbsent(cap.id(),cap);if(old!=null&&!old.permission().equals(cap.permission()))throw new FcpException("AMBIGUOUS_PERMISSION","Package capability permission differs");}
        List<Object> options=new ArrayList<>();
        for(var cap:capabilities.values()){
            var key=new VersionPluginPolicy.GrantKey(targetTenant,version,cap.id(),"invoke",scope,kind,principal);var approval=policy.read(c,key);
            options.add(Json.obj("capability",cap.id(),"operation","invoke","permission","fcp:plugin:"+cap.permission(),"allowed",approval!=null&&approval.allowed(),"revision",approval==null?0:approval.revision(),"expiresAt",approval==null||approval.expiresAt()==null?null:approval.expiresAt().toString()));
        }
        return Map.of("pluginId",version.pluginId(),"version",version.version(),"digest",version.digest(),"targetTenant",targetTenant,"dataScope",scope,"principalKind",kind.name(),"principalId",principal,"options",options);
    }
    public Map<String,Object> changeGrant(TrustedContext c,VersionPluginPolicy.GrantKey key,boolean allow,Instant expires,long expected){
        admin(c);var a=policy.change(c,key,allow,expires,expected);
        return Json.obj("pluginId",a.key().version().pluginId(),"version",a.key().version().version(),"digest",a.key().version().digest(),"capability",a.key().capability(),"revision",a.revision(),"allowed",a.allowed(),"expiresAt",a.expiresAt()==null?null:a.expiresAt().toString());
    }
}
