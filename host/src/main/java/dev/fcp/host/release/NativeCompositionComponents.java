package dev.fcp.host.release;

import dev.fcp.host.PluginReleaseApplication;
import dev.fcp.host.ui.UiBundleStore;
import fcp.*;
import fcp.host.TrustedContext;
import fcp.release.control.ReleasePlan;
import fcp.release.version.*;
import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;

/** Exact, precompiled UI plus isolated plugin composition. Does not hot-replace host code or schema. */
public final class NativeCompositionComponents implements CompositeReleaseDeployment.Components {
    private final VersionProvider versions; private final UiBundleStore bundles;
    private final PluginReleaseApplication plugins; private final Supplier<TrustedContext> identity;
    public NativeCompositionComponents(VersionProvider versions,UiBundleStore bundles,PluginReleaseApplication plugins,Supplier<TrustedContext> identity){this.versions=versions;this.bundles=bundles;this.plugins=plugins;this.identity=identity;}
    private SystemRelease release(String id)throws IOException{return versions.release(id).orElseThrow(()->new IOException("SYSTEM_VERSION_UNKNOWN"));}
    private SystemArtifact artifact(String id)throws IOException{return versions.artifact(id).orElseThrow(()->new IOException("SYSTEM_ARTIFACT_UNKNOWN"));}
    public void verify(ReleasePlan plan)throws IOException {
        SystemRelease stable=release(plan.stableRelease()),candidate=release(plan.candidateRelease());
        if(!VersionCodec.sha256(VersionCodec.canonical(VersionCodec.release(stable))).equals(plan.stableDigest())||!VersionCodec.sha256(VersionCodec.canonical(VersionCodec.release(candidate))).equals(plan.candidateDigest()))throw new IOException("SYSTEM_MANIFEST_DIGEST_MISMATCH");
        if(!fixed(stable).equals(fixed(candidate)))throw new IOException("NATIVE_EXECUTOR_REQUIRES_UNCHANGED_HOST_SERVICE_CONFIG_SCHEMA");
        verifyUi(stable);verifyUi(candidate);
    }
    private Map<String,String> fixed(SystemRelease r)throws IOException {
        Map<String,String> fixed=new TreeMap<>();
        for(var entry:r.components().entrySet()){
            var a=artifact(entry.getValue());
            if(a.kind()==SystemArtifact.Kind.ADMIN_UI&&!entry.getKey().equals("admin-ui")||a.kind()==SystemArtifact.Kind.TEACHER_UI&&!entry.getKey().equals("teacher-ui"))throw new IOException("UI_COMPONENT_ROLE_MISMATCH");
            if(a.kind()!=SystemArtifact.Kind.ADMIN_UI&&a.kind()!=SystemArtifact.Kind.TEACHER_UI&&a.kind()!=SystemArtifact.Kind.PLUGIN)fixed.put(entry.getKey(),a.id());
        }
        return fixed;
    }
    private void verifyUi(SystemRelease r)throws IOException {
        if(!"fcp.host/1".equals(r.compatibility().get("hostContract")))throw new IOException("HOST_CONTRACT_UNSUPPORTED");
        for(String shell:List.of("admin","teacher")){
            String id=r.components().get(shell+"-ui");if(id==null)throw new IOException("DUAL_UI_COMPONENT_REQUIRED");
            var a=artifact(id);var expected=shell.equals("admin")?SystemArtifact.Kind.ADMIN_UI:SystemArtifact.Kind.TEACHER_UI;
            if(a.kind()!=expected||!"fcp.ui-bundle/1".equals(a.metadata().get("format")))throw new IOException("UI_ARTIFACT_FORMAT");
            var bundle=bundles.bundle(a.sha256(),shell);
            if(bundle.sizeBytes()!=a.sizeBytes()||!"fcp.host/1".equals(bundle.backendContract()))throw new IOException("UI_ARTIFACT_COMPATIBILITY");
            bundles.verify(bundle);
        }
    }
    public void prepare(String id)throws IOException {
        TrustedContext c=identity.get();SystemRelease system=release(id);verifyUi(system);
        Map<String,Object> declared=new TreeMap<>();
        for(String artifactId:system.components().values()) {var a=artifact(artifactId);if(a.kind()==SystemArtifact.Kind.PLUGIN&&declared.put(a.component(),Map.of("pluginId",a.component(),"version",a.version(),"digest",a.sha256()))!=null)throw new IOException("AMBIGUOUS_PLUGIN_VERSION");}
        var state=plugins.state(c);plugins.register(c,Map.of("id",id,"plugins",declared),Schema.number(state.get("revision")));
        var status=plugins.status(c,id);if(!Boolean.TRUE.equals(status.get("ready"))||Boolean.TRUE.equals(status.get("draining")))plugins.activate(c,id,Schema.number(status.get("revision")));
    }
    public CompositeReleaseDeployment.Status status(String id)throws IOException {
        TrustedContext c=identity.get();var state=plugins.state(c);
        if(!Schema.map(state.get("releases")).containsKey(id))return new CompositeReleaseDeployment.Status(false,true,0,0);
        var record=Schema.map(Schema.map(state.get("releases")).get(id));
        var st=plugins.status(c,id);boolean stopped=!Boolean.TRUE.equals(st.get("enabled"))&&Schema.map(st.get("processes")).isEmpty()&&Schema.map(record.getOrDefault("processes",Map.of())).isEmpty()&&Set.of("REGISTERED","STOPPED","PREPARE_FAILED").contains(st.get("activationState"));
        return new CompositeReleaseDeployment.Status(Boolean.TRUE.equals(st.get("ready")),stopped,Schema.number(st.get("activeCalls")),Schema.number(st.get("pendingTasks")));
    }
    public void beginDrain(String id)throws IOException {var c=identity.get();var st=plugins.status(c,id);if(Boolean.TRUE.equals(st.get("enabled"))&&!Boolean.TRUE.equals(st.get("draining")))plugins.beginDrain(c,id,Schema.number(st.get("revision")));}
    public void stop(String id)throws IOException {plugins.stop(identity.get(),id);}
}
