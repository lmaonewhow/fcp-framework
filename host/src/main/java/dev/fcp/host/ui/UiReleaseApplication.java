package dev.fcp.host.ui;

import fcp.*;
import fcp.host.TrustedContext;
import fcp.release.routing.DurableReleaseRouter;
import fcp.release.version.*;
import java.io.IOException;
import java.util.*;

/** UI and plugin callers share the exact same router/affinity. This never grants business permission. */
public final class UiReleaseApplication {
    @FunctionalInterface public interface ShellAuthorizer {void require(TrustedContext context,String shell);}
    private final DurableReleaseRouter router;private final VersionProvider versions;private final UiBundleStore assets;private final String backendContract;private final ShellAuthorizer authorization;
    public UiReleaseApplication(DurableReleaseRouter router,VersionProvider versions,UiBundleStore assets,String backendContract,ShellAuthorizer authorization){this.router=Objects.requireNonNull(router);this.versions=Objects.requireNonNull(versions);this.assets=Objects.requireNonNull(assets);this.backendContract=Objects.requireNonNull(backendContract);this.authorization=Objects.requireNonNull(authorization);}
    public static Map<String,Object> embedded(String shell,String reason){UiBundleStore.shell(shell);return Map.of("mode","EMBEDDED","shell",shell,"reason",reason);}
    public Map<String,Object> bootstrap(TrustedContext context,String shell,String affinity)throws IOException {
        UiBundleStore.shell(shell);authorization.require(context,shell);
        if(router.policy().isEmpty()){
            if(affinity!=null&&!affinity.isBlank())throw new FcpException("UI_RELEASE_UNAVAILABLE","Existing release context cannot fall back to embedded");
            return embedded(shell,"NOT_CONFIGURED");
        }
        var decision=affinity==null||affinity.isBlank()?router.decide(context):router.resume(context,affinity);
        var release=versions.release(decision.releaseId()).orElseThrow(()->new FcpException("RELEASE_NOT_FOUND","Release composition unavailable"));
        String id=release.components().get(shell+"-ui");if(id==null)throw new FcpException("UI_RELEASE_UNAVAILABLE","Release has no UI for this shell");
        var artifact=versions.artifact(id).orElseThrow(()->new FcpException("UI_RELEASE_UNAVAILABLE","UI artifact unavailable"));
        if(artifact.kind()!=(shell.equals("admin")?SystemArtifact.Kind.ADMIN_UI:SystemArtifact.Kind.TEACHER_UI)||!"fcp.ui-bundle/1".equals(artifact.metadata().get("format")))throw new FcpException("UI_ARTIFACT_KIND","Expected verified UI bundle");
        try {
        var bundle=assets.bundle(artifact.sha256(),shell);
        if(!backendContract.equals(bundle.backendContract())||!backendContract.equals(release.compatibility().get("hostContract"))||artifact.sizeBytes()!=bundle.sizeBytes())throw new FcpException("UI_CONTRACT_INCOMPATIBLE","UI and active host contract differ");
        assets.verify(bundle);var index=assets.index(bundle);
        return Map.of("mode","RELEASE","releaseId",decision.releaseId(),"affinity",decision.affinity(),"policyRevision",decision.revision(),"expiresAt",decision.expiresAt().toString(),"shell",shell,"bundleDigest",bundle.digest(),"indexDigest",index.digest(),"indexUrl","/fcp/ui/assets/"+bundle.digest()+"/"+shell+"/index.html","backendContract",backendContract);
        } catch(IOException failure){throw new FcpException("UI_ASSET_UNVERIFIED","Active release assets unavailable",failure);}
    }
}
