package org.dromara.fcp.plugin;

import dev.fcp.host.PluginReleaseApplication;
import dev.fcp.host.release.*;
import dev.fcp.host.ui.UiBundleStore;
import fcp.release.control.DeploymentProvider;
import fcp.release.observation.WindowedObservation;
import fcp.release.routing.DurableReleaseRouter;
import fcp.release.version.VersionProvider;
import fcp.storage.JdbcStateJournal;
import javax.sql.DataSource;
import java.nio.file.Path;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

/** One native JVM, one router, one protected shared-runtime lock. Never a distributed executor. */
@Configuration
@ConditionalOnProperty(name="FCP_RELEASE_EXECUTOR_MODE",havingValue="native")
public class NativeCompositeReleaseConfiguration {
    @Bean(destroyMethod="close") public NativeExecutorLock fcpNativeExecutorLock(Environment env)throws Exception {
        return new NativeExecutorLock(Path.of(env.getRequiredProperty("FCP_RELEASE_EXECUTOR_LOCK_FILE")));
    }
    @Bean @ConditionalOnProperty(name="fcp.release.observation.enabled",havingValue="true")
    @ConditionalOnMissingBean(WindowedObservation.class)
    public WindowedObservation fcpNativeReleaseObservation(DataSource source,NativePluginHost host){
        return new WindowedObservation(host.clock(),10000,new JdbcStateJournal(source,"release_security_incidents"));
    }
    @Bean @ConditionalOnMissingBean(DeploymentProvider.class)
    public DeploymentProvider fcpNativeReleaseDeployment(DataSource source,DurableReleaseRouter router,VersionProvider versions,UiBundleStore bundles,PluginReleaseApplication plugins,NativePluginHost host,Environment env,NativeExecutorLock owner,ObjectProvider<WindowedObservation> observations)throws java.io.IOException {
        var components=new NativeCompositionComponents(versions,bundles,plugins,host::current);
        WindowedObservation observation=observations.getIfAvailable();
        var executor=new CompositeReleaseDeployment(new JdbcStateJournal(source,"system_release_executor"),router,components,env.getRequiredProperty("FCP_RELEASE_ENVIRONMENT"),(release,generation,revision)->{if(observation!=null)observation.bind(release,generation,revision);});
        if(observation!=null)executor.restoreObservationBinding();
        return executor;
    }
}
