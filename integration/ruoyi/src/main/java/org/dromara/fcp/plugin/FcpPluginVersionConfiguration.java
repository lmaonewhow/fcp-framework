package org.dromara.fcp.plugin;
import dev.fcp.host.PluginVersionApplication;
import fcp.control.LifecycleCoordinator;
import fcp.runtime.VerifiedPackageRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import java.nio.file.Path;

@Configuration
@ConditionalOnProperty(name="fcp.runtime.enabled",havingValue="true")
public class FcpPluginVersionConfiguration {
    @Bean public PluginVersionApplication fcpPluginVersions(NativePluginHost host,VerifiedPackageRepository packages,LifecycleCoordinator lifecycle,Environment environment)throws Exception{
        Path root=Path.of(environment.getRequiredProperty("FCP_RUNTIME_ROOT"));if(!root.isAbsolute())throw new IllegalArgumentException("Runtime root must be absolute");
        return new PluginVersionApplication(host,packages,lifecycle,root.resolve("version-uploads"));
    }
}
