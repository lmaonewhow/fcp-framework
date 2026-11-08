package org.dromara.fcp.plugin;
import dev.fcp.host.ReleaseTaskApplication;
import dev.fcp.host.config.ProtectedDeploymentConfig;
import fcp.runtime.multiversion.PluginReleaseRuntime;
import fcp.storage.JdbcStateJournal;
import java.nio.file.*;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration
@ConditionalOnProperty(name={"fcp.plugin.release.enabled","fcp.plugin.release.tasks.enabled"},havingValue="true")
public class FcpReleaseTaskConfiguration {
    @Bean public ReleaseTaskApplication fcpReleaseTasks(NativePluginHost host,PluginReleaseRuntime runtime,DataSource source,Environment env)throws Exception {
        if(!"native".equals(env.getRequiredProperty("FCP_RELEASE_EXECUTOR_MODE")))throw new IllegalArgumentException("TASK adapter requires the exclusive native executor owner");
        Path path=Path.of(env.getRequiredProperty("FCP_RELEASE_TASKS_FILE"));
        return new ReleaseTaskApplication(host,runtime,new JdbcStateJournal(source,"release_task_runs"),ReleaseTaskApplication.parse(ProtectedDeploymentConfig.read(path)));
    }
}
