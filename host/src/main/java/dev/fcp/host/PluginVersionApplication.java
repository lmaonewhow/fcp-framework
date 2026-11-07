package dev.fcp.host;

import fcp.*;
import fcp.control.*;
import fcp.host.*;
import fcp.runtime.*;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Explicit reviewed stop/start version selection. Staging never changes the active version or grants. */
public final class PluginVersionApplication {
    private record Pending(LifecycleCoordinator.VersionPlan plan,String owner,Instant expires) {}
    private final HostCapabilities host;
    private final VerifiedPackageRepository repository;
    private final LifecycleCoordinator lifecycle;
    private final PackageStaging staging;
    private final Map<String,Pending> plans=new LinkedHashMap<>();
    public PluginVersionApplication(HostCapabilities host,VerifiedPackageRepository repository,LifecycleCoordinator lifecycle,Path uploads)throws IOException {
        this.host=Objects.requireNonNull(host);this.repository=Objects.requireNonNull(repository);this.lifecycle=Objects.requireNonNull(lifecycle);this.staging=new PackageStaging(host,repository,uploads);
    }
    private void require(TrustedContext c,String plugin){host.require(c,new HostCapabilities.Grant(plugin,"plugin.lifecycle","install","tenant:"+c.tenantId()));}
    public Map<String,Object> stage(TrustedContext context,InputStream body,String digest,String key)throws IOException {
        return staging.stage(context,body,digest,key);
    }
    public Map<String,Object> versions(TrustedContext context,String plugin)throws IOException {
        require(context,plugin);List<Object> versions=new ArrayList<>();for(var version:repository.verifiedVersions(plugin))versions.add(Json.obj("version",version.version(),"digest",version.digest(),"state","VERIFIED"));
        return Json.obj("pluginId",plugin,"revision",lifecycle.revision(),"current",lifecycle.installedMetadata(context,plugin),"versions",versions);
    }
    public synchronized Map<String,Object> plan(TrustedContext context,String plugin,String action,String targetVersion,String targetDigest,long expected)throws IOException {
        require(context,plugin);LifecycleCoordinator.VersionAction type;
        try{type=LifecycleCoordinator.VersionAction.valueOf(action);}catch(IllegalArgumentException failure){throw new FcpException("INVALID_ARGUMENT","Choose UPGRADE or ROLLBACK");}
        var target=repository.resolve(plugin,targetVersion,targetDigest);var plan=lifecycle.planVersion(context,target,type,expected);
        plans.entrySet().removeIf(e->!host.clock().instant().isBefore(e.getValue().expires()));if(plans.size()>=1024)throw new FcpException("BUSY","Version plan limit reached");
        String id=UUID.randomUUID().toString();plans.put(id,new Pending(plan,owner(context),host.clock().instant().plusSeconds(300)));
        return Json.obj("planId",id,"digest",plan.digest(),"pluginId",plan.pluginId(),"action",plan.action().name(),"fromVersion",plan.fromVersion(),"fromDigest",plan.fromDigest(),"targetVersion",plan.targetVersion(),"targetDigest",plan.targetDigest(),"expectedRevision",plan.expectedRevision(),"affectedTenants",plan.affectedTenants(),"dependentPlugins",plan.dependentPlugins());
    }
    public synchronized Map<String,Object> confirm(TrustedContext context,String id,String digest,String key,boolean confirmedDowntime)throws IOException {
        require(context,"control");if(!confirmedDowntime)throw new FcpException("CONFIRMATION_REQUIRED","Explicit disabled/drained downtime confirmation required");
        var previous=lifecycle.replayVersion(context,digest,key);if(previous!=null)return receipt(previous);
        Pending pending=plans.get(id);if(pending==null||!pending.owner().equals(owner(context))||!host.clock().instant().isBefore(pending.expires()))throw new FcpException("PLAN_EXPIRED","Review a fresh version plan");
        if(!pending.plan().digest().equals(digest))throw new FcpException("CONFIRMATION_REQUIRED","Reviewed version digest differs");
        var plan=pending.plan();return receipt(lifecycle.confirmVersion(context,plan,repository.resolve(plan.pluginId(),plan.targetVersion(),plan.targetDigest()),key,true));
    }
    public Map<String,Object> operation(TrustedContext context,String id){require(context,"control");return receipt(lifecycle.versionOperation(context,id));}
    private static Map<String,Object> receipt(LifecycleCoordinator.VersionOperation op){return Json.obj("operationId",op.operationId(),"pluginId",op.pluginId(),"action",op.action(),"fromVersion",op.fromVersion(),"fromDigest",op.fromDigest(),"targetVersion",op.targetVersion(),"targetDigest",op.targetDigest(),"phase",op.phase(),"outcome",op.outcome(),"revision",op.revision());}
    private static String owner(TrustedContext c){return Json.write(List.of(c.principal().kind().name(),c.principal().id(),c.tenantId()));}
}
