package org.dromara.fcp.plugin;

import dev.fcp.host.*;
import fcp.control.*;
import fcp.runtime.*;
import fcp.storage.JdbcStateJournal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.*;

/** Opt-in only after the restricted deployment migration and trusted publisher configuration. */
@Configuration
@ConditionalOnProperty(name="fcp.runtime.enabled",havingValue="true")
public class FcpPluginConfiguration {
    private static Path root(Environment env) {
        String path=env.getRequiredProperty("FCP_RUNTIME_ROOT");
        Path root=Path.of(path);if(!root.isAbsolute())throw new IllegalArgumentException("FCP_RUNTIME_ROOT must be absolute");return root;
    }
    @Bean public NativePluginHost fcpNativePluginHost(DataSource source) {return new NativePluginHost(source,new JdbcPluginPolicy(source));}
    @Bean public VerifiedPackageRepository fcpPackages(Environment env)throws Exception {
        Set<String> publishers=new HashSet<>();for(String p:env.getRequiredProperty("FCP_TRUSTED_PUBLISHERS").split(","))if(!p.isBlank())publishers.add(p.trim());
        if(publishers.isEmpty())throw new IllegalArgumentException("Explicit trusted publisher list required");
        return new VerifiedPackageRepository(root(env).resolve("packages"),publishers);
    }
    @Bean(destroyMethod="close") public EngineRuntimePort fcpPluginRuntime(NativePluginHost host,VerifiedPackageRepository packages,Environment env,DataSource source)throws Exception {
        Map<String,String> executable=new HashMap<>();for(String kind:List.of("python","node")) {
            String value=env.getProperty("FCP_RUNTIME_"+kind.toUpperCase(Locale.ROOT));
            if(value!=null&&!value.isBlank()){if(!Path.of(value).isAbsolute())throw new IllegalArgumentException("Runtime interpreter must be absolute");executable.put(kind,value);}
        }
        EngineRuntimePort runtime=new EngineRuntimePort(host,packages,executable,new JdbcStateJournal(source,"runtime-calls"),3);
        runtime.setStorageCompatibilityVerifier(new fcp.storage.JdbcStorageCompatibility(source)::verify);
        runtime.setStorageCompatibilityFingerprint(new fcp.storage.JdbcStorageCompatibility(source)::fingerprint);
        var storage=new fcp.storage.StorageCapabilities(new fcp.storage.JdbcPluginStorage(source,host));
        for(var contract:fcp.storage.StorageCapabilities.contracts()) {
            String operation=contract.id().substring("host.storage.".length());
            runtime.registerHostCapability(contract,(plugin,context,capabilityId,input)->storage.invoke(plugin,context,operation,input));
        }
        return runtime;
    }
    @Bean(destroyMethod="close") public LifecycleCoordinator fcpPluginLifecycle(NativePluginHost host,EngineRuntimePort runtime,DataSource source)throws Exception {
        LifecycleCoordinator lifecycle=new LifecycleCoordinator(new JdbcStateJournal(source,"plugins"),host,runtime);
        lifecycle.setEnablementPolicy(new JdbcEnablementPolicy(source));
        return lifecycle;
    }
    @Bean(destroyMethod="close")
    @ConditionalOnProperty(name="fcp.plugin.release.enabled",havingValue="false",matchIfMissing=true)
    public PluginRecovery fcpPluginRecovery(LifecycleCoordinator lifecycle,NativePluginHost host) {
        return new PluginRecovery(lifecycle,host::recoveryContext);
    }
    @Bean public PluginGrantAdmin fcpPluginGrantAdmin(DataSource source,NativePluginHost host,LifecycleCoordinator lifecycle,VerifiedPackageRepository packages) {
        return new PluginGrantAdmin(source,host,lifecycle,packages);
    }
    @Bean public PluginApplication fcpPluginApplication(NativePluginHost host,VerifiedPackageRepository packages,LifecycleCoordinator lifecycle,EngineRuntimePort runtime,Environment env)throws Exception {
        return new PluginApplication(host,packages,lifecycle,runtime,root(env).resolve("uploads"),host::permissions);
    }
}
