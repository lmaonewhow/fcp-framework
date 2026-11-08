package org.dromara.fcp.plugin;

import dev.fcp.host.*;
import dev.fcp.host.release.NativeExecutorLock;
import fcp.release.observation.RequestObserver;
import org.springframework.beans.factory.ObjectProvider;
import fcp.*;
import fcp.host.*;
import fcp.runtime.*;
import fcp.runtime.multiversion.PluginReleaseRuntime;
import fcp.release.routing.DurableReleaseRouter;
import fcp.release.version.VersionProvider;
import fcp.storage.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import javax.sql.DataSource;
import java.sql.*;
import java.nio.file.Path;
import java.util.*;

/** Explicit native opt-in. Requires the shared fcpReleaseRouter and a system VersionProvider. */
@Configuration
@ConditionalOnProperty(name="fcp.plugin.release.enabled",havingValue="true")
public class FcpPluginReleaseConfiguration {
    @Bean public VersionPluginPolicy fcpVersionPluginPolicy(DataSource source,NativePluginHost host,VerifiedPackageRepository packages) {
        return new JdbcVersionPluginPolicy(source,context->freshIdentity(source,host,context),version->{
            try{return packages.resolve(version.pluginId(),version.version(),version.digest()).manifest();}
            catch(java.io.IOException failure){throw new FcpException("PACKAGE_INTEGRITY","Cannot resolve approved package version",failure);}
        },(actor,target)->{
            host.require(actor,new HostCapabilities.Grant("control","system.release","manage","tenant:"+actor.tenantId()));
            if(!activeTenant(source,target))throw new FcpException("PERMISSION_DENIED","Target tenant is unavailable");
        },host.clock());
    }
    @Bean(destroyMethod="close") public PluginReleaseRuntime fcpMultiVersionRuntime(DataSource source,NativePluginHost host,VerifiedPackageRepository packages,VersionPluginPolicy policy,DurableReleaseRouter router,Environment environment,EngineRuntimePort legacyRuntime,ObjectProvider<NativeExecutorLock> executorOwner)throws Exception {
        executorOwner.getIfAvailable(); // Lock before any composition recovery can start child processes.
        if(Boolean.parseBoolean(environment.getProperty("fcp.exchange.enabled","false")))throw new IllegalStateException("Plugin multiversion cannot use the legacy Exchange execution port; configure a release-aware Exchange adapter before enabling both");
        try(JdbcStateJournal legacy=new JdbcStateJournal(source,"plugins")) {
            for(String plugin:PluginReleaseApplication.requireLegacyQuiescent(legacy.load().json())) {
                var health=legacyRuntime.health(plugin);
                if(Boolean.TRUE.equals(health.get("ready"))||health.get("pid")!=null||Schema.number(health.get("activeCalls"))!=0)throw new FcpException("MODE_CONFLICT","Legacy plugin runtime must be stopped before release mode");
            }
        }
        Map<String,String> executables=new HashMap<>();for(String kind:List.of("python","node")){String value=environment.getProperty("FCP_RUNTIME_"+kind.toUpperCase(Locale.ROOT));if(value!=null&&!value.isBlank()){if(!Path.of(value).isAbsolute())throw new IllegalArgumentException("Runtime executable must be absolute");executables.put(kind,value);}}
        PluginReleaseRuntime result=new PluginReleaseRuntime(host,new JdbcStateJournal(source,"plugin_release_compositions"),router,(releaseId,scopedHost)->{
            String journal="plugin_release_calls_"+VerifiedPackageRepository.hash(releaseId.getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0,40);
            EngineRuntimePort runtime=new EngineRuntimePort(scopedHost,packages,executables,new JdbcStateJournal(source,journal),3);
            runtime.setStorageCompatibilityVerifier(new JdbcStorageCompatibility(source)::verify);runtime.setStorageCompatibilityFingerprint(new JdbcStorageCompatibility(source)::fingerprint);
            StorageCapabilities storage=new StorageCapabilities(new JdbcPluginStorage(source,scopedHost));
            for(var contract:StorageCapabilities.contracts()){String operation=contract.id().substring("host.storage.".length());runtime.registerHostCapability(contract,(plugin,context,capability,input)->storage.invoke(plugin,context,operation,input));}
            return runtime;
        },(context,version,grant)->policy.authorize(context,new VersionPluginPolicy.Version(version.pluginId(),version.version(),version.digest()),grant),version->packages.resolve(version.pluginId(),version.version(),version.digest()));
        try{result.recover();return result;}catch(Exception failure){result.close();throw failure;}
    }
    @Bean public PluginReleaseApplication fcpPluginReleaseApplication(NativePluginHost host,PluginReleaseRuntime runtime,VerifiedPackageRepository packages,VersionProvider versions,VersionPluginPolicy policy,ObjectProvider<RequestObserver> observations,ObjectProvider<PluginReleaseApplication.BusinessClassifier> classifiers){return new PluginReleaseApplication(host,runtime,packages,versions,policy,host::permissions,observations.getIfAvailable(()->RequestObserver.NONE),classifiers.getIfAvailable());}
    private static VersionPluginPolicy.Identity freshIdentity(DataSource source,NativePluginHost host,TrustedContext context){
        if(!host.clock().instant().isBefore(context.deadline())||!activeTenant(source,context.tenantId()))return new VersionPluginPolicy.Identity(false,Set.of(),Set.of(),0);
        if(context.principal().kind()==TrustedContext.Kind.TASK){
            // TASK contexts are created only by trusted server services. Exact task+digest+scope+expiry is checked by policy.
            return new VersionPluginPolicy.Identity(true,Set.of(),context.dataScopes(),context.authorizationRevision());
        }
        Set<String> permissions=host.permissions(context);Set<String> scopes=new TreeSet<>();
        try(Connection c=source.getConnection();PreparedStatement q=c.prepareStatement("SELECT class_id FROM fcp_eval_assignment WHERE scope_id=? AND subject_id=? ORDER BY class_id LIMIT 257")){
            q.setQueryTimeout(5);q.setString(1,"tenant-"+context.tenantId());q.setString(2,context.principal().id());
            try(ResultSet r=q.executeQuery()){while(r.next()){scopes.add("class:"+r.getString(1));if(scopes.size()>256)throw new FcpException("PERMISSION_DENIED","Scope cardinality limit");}}
        }catch(SQLException failure){throw new FcpException("DATA_ACCESS","Cannot revalidate version user scopes",failure);}
        long revision=java.nio.ByteBuffer.wrap(java.util.HexFormat.of().parseHex(VerifiedPackageRepository.hash(Json.write(List.of(new TreeSet<>(permissions),scopes)).getBytes(java.nio.charset.StandardCharsets.UTF_8)))).getLong()&0x1fffffffffffffL;
        return new VersionPluginPolicy.Identity(true,permissions,scopes,revision);
    }
    private static boolean activeTenant(DataSource source,String tenant){
        if(tenant==null||!tenant.matches("[A-Za-z0-9_-]{1,20}"))return false;
        try(Connection c=source.getConnection();PreparedStatement q=c.prepareStatement("SELECT tenant_id FROM sys_tenant WHERE tenant_id=? AND status='0' AND del_flag='0' AND (expire_time IS NULL OR expire_time>CURRENT_TIMESTAMP)")){
            q.setQueryTimeout(5);q.setString(1,tenant);try(ResultSet r=q.executeQuery()){return r.next();}
        }catch(SQLException failure){throw new FcpException("DATA_ACCESS","Cannot revalidate version tenant",failure);}
    }
}
