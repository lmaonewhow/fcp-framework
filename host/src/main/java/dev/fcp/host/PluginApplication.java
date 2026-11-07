package dev.fcp.host;

import fcp.*;
import fcp.control.*;
import fcp.host.*;
import fcp.plugin.*;
import fcp.runtime.*;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import static fcp.Schema.*;

/** Shared application service: native and development HTTP adapters call identical operations. */
public final class PluginApplication {
    private record PendingPlan(LifecycleCoordinator.Plan plan,String owner,Instant expires) { }
    private final HostCapabilities host;
    private final VerifiedPackageRepository packages;
    private final LifecycleCoordinator lifecycle;
    private final EngineRuntimePort runtime;
    private final Path uploads;
    private final Function<TrustedContext,Set<String>> permissions;
    private final Map<String,PendingPlan> plans=new LinkedHashMap<>();
    public PluginApplication(HostCapabilities host,VerifiedPackageRepository packages,LifecycleCoordinator lifecycle,
                             EngineRuntimePort runtime,Path uploads,Function<TrustedContext,Set<String>> permissions)throws IOException {
        this.host=host;this.packages=packages;this.lifecycle=lifecycle;this.runtime=runtime;this.permissions=permissions;
        this.uploads=uploads.toAbsolutePath().normalize();Files.createDirectories(this.uploads);
        if(Files.isSymbolicLink(this.uploads))throw new IOException("Upload directory must not be a symlink");
    }
    public Map<String,Object> install(TrustedContext context,InputStream body,String digest,String key,long expected)throws IOException {
        host.require(context,new HostCapabilities.Grant("control","plugin.lifecycle","install","tenant:"+context.tenantId()));
        Path upload=Files.createTempFile(uploads,"package-",".zip");
        try {
            try(OutputStream out=Files.newOutputStream(upload)) {
                byte[] buffer=new byte[8192];long count=0;int n;
                while((n=body.read(buffer))!=-1) {count+=n;if(count>64L*1024*1024)throw new FcpException("PACKAGE_LIMIT","Package exceeds upload limit");out.write(buffer,0,n);}
            }
            if(key==null||key.isBlank()||key.length()>256)throw new FcpException("INVALID_ARGUMENT","Invalid installation idempotency key");
            String scopedKey=VerifiedPackageRepository.hash(Json.write(List.of(context.tenantId(),context.principal().kind().name(),context.principal().id(),key)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var pkg=packages.install(upload,digest,scopedKey);
            for(Object contribution:(List<?>)pkg.manifest().ui().getOrDefault("slots",List.of())) {
                String slot=(String)((Map<?,?>)contribution).get("slotId");
                if(!host.descriptor().capabilities().containsKey("ui.slot."+slot))throw new FcpException("UI_SLOT_UNAVAILABLE","Required host slot unavailable");
            }
            Map<String,Object> existing=lifecycle.installedMetadata(context,pkg.manifest().pluginId());
            boolean replay=pkg.replayed()&&"INSTALLED".equals(existing.get("state"))&&digest.equals(existing.get("digest"));
            if(!replay)lifecycle.register(context,pkg,expected);
            return Json.obj("pluginId",pkg.manifest().pluginId(),"version",pkg.manifest().version(),"digest",digest,
                "state","INSTALLED","replayed",replay,"revision",lifecycle.revision());
        } finally { Files.deleteIfExists(upload); }
    }
    public Map<String,Object> uninstall(TrustedContext context,String plugin,String confirmedDigest,long expected) {
        host.require(context,new HostCapabilities.Grant(plugin,"plugin.lifecycle","uninstall","tenant:"+context.tenantId()));
        var installed=lifecycle.installedMetadata(context,plugin);
        if(confirmedDigest==null||!confirmedDigest.equals(installed.get("digest")))throw new FcpException("VERSION_CONFLICT","Confirm the currently installed package digest");
        lifecycle.uninstall(context,plugin,expected);
        return Json.obj("pluginId",plugin,"state","UNINSTALLED","dataRetained",true,"revision",lifecycle.revision());
    }
    public synchronized Map<String,Object> plan(TrustedContext context,String plugin,String action,long expected) {
        LifecycleCoordinator.Action type;
        try{type=LifecycleCoordinator.Action.valueOf(action.toUpperCase(Locale.ROOT));}
        catch(IllegalArgumentException e){throw new FcpException("INVALID_ARGUMENT","Unknown plan action");}
        var plan=lifecycle.plan(context,context.tenantId(),plugin,type,expected);
        plans.entrySet().removeIf(e->host.clock().instant().isAfter(e.getValue().expires()));
        if(plans.size()>=1024)throw new FcpException("BUSY","Pending plan limit");
        String id=UUID.randomUUID().toString();plans.put(id,new PendingPlan(plan,owner(context),host.clock().instant().plusSeconds(300)));
        return Json.obj("planId",id,"digest",plan.digest(),"pluginId",plan.plugin(),"action",plan.action().name(),
            "affectedPlugins",plan.closure(),"expectedRevision",plan.expectedRevision());
    }
    public synchronized Map<String,Object> confirm(TrustedContext context,String id,String digest,Set<String> affected,String key) {
        var saved=lifecycle.replayConfirmation(context,digest,affected,key);if(saved!=null)return operation(saved);
        PendingPlan pending=plans.get(id);
        if(pending==null||!pending.owner().equals(owner(context))||host.clock().instant().isAfter(pending.expires()))
            throw new FcpException("PLAN_EXPIRED","Generate a new plan before confirmation");
        if(!pending.plan().digest().equals(digest))throw new FcpException("VERSION_CONFLICT","Plan digest changed");
        return operation(lifecycle.confirm(context,pending.plan(),affected,key));
    }
    public Map<String,Object> operation(TrustedContext context,String id){return operation(lifecycle.operation(context,id));}
    public Map<String,Object> decide(TrustedContext context,String id,String choice) {
        LifecycleCoordinator.DrainDecision decision;
        try{decision=LifecycleCoordinator.DrainDecision.valueOf(choice.toUpperCase(Locale.ROOT));}
        catch(IllegalArgumentException e){throw new FcpException("INVALID_ARGUMENT","Unknown drain decision");}
        return operation(lifecycle.decideDrain(context,id,decision));
    }
    public Map<String,Object> list(TrustedContext context) {
        List<Object> plugins=new ArrayList<>();
        for(var entry:lifecycle.installedPlugins(context).entrySet()) {
            Map<String,Object> item=new LinkedHashMap<>(status(context,entry.getKey()));item.putAll(entry.getValue());item.put("pluginId",entry.getKey());plugins.add(item);
        }
        return Json.obj("revision",lifecycle.revision(),"plugins",plugins);
    }
    public Map<String,Object> status(TrustedContext context,String plugin) {
        var status=lifecycle.status(context,context.tenantId(),plugin);
        return Json.obj("revision",status.revision(),"installation",status.installation(),"deployment",status.deployment(),
            "desired",status.desired(),"effective",status.effective());
    }
    public Map<String,Object> catalog(TrustedContext context) {
        var snapshot=runtime.snapshot(context);
        return PluginCatalog.project(host,context,snapshot.enabledManifests(),snapshot.generation(),permissions.apply(context));
    }
    public Object invoke(TrustedContext context,String exported,Map<String,Object> input,String key,long expected) {
        var snapshot=runtime.snapshot(context);
        if(snapshot.generation()!=expected)throw new FcpException("VERSION_CONFLICT","Plugin catalog changed");
        for(var entry:snapshot.enabledManifests().entrySet())for(var capability:entry.getValue().provides())
            if(EngineRuntimePort.export(entry.getKey(),capability.id()).equals(exported)) {
                if(capability.id().equals("exchange.commit"))throw new FcpException("CONFIRMATION_REQUIRED","Use the exchange preview and confirmation endpoint");
                return runtime.invoke(context,entry.getKey(),capability.id(),input,key,expected);
            }
        throw new FcpException("PLUGIN_DISABLED","Capability unavailable");
    }
    private static String owner(TrustedContext c){return c.tenantId()+":"+c.principal().kind()+":"+c.principal().id();}
    private static Map<String,Object> operation(LifecycleCoordinator.Operation op){return Json.obj("operationId",op.id(),"phase",op.phase(),"outcome",op.outcome(),"plugins",op.plugins());}
}
