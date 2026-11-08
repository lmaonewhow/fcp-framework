package org.dromara.fcp.plugin;
import dev.fcp.host.*;
import dev.fcp.host.config.ProtectedDeploymentConfig;
import fcp.*;
import fcp.release.version.VersionProvider;
import java.nio.file.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
/** Deployment-owned opt-in. The HTTP API cannot add contracts or mutate these bindings. */
@Configuration
@ConditionalOnProperty(name="fcp.release.identity-echo.enabled",havingValue="true")
public class NativeIdentityEchoConfiguration {
    @Bean public PluginReleaseApplication.BusinessClassifier fcpIdentityEchoClassifier(VersionProvider versions,Environment env)throws Exception{
        Path path=Path.of(env.getRequiredProperty("FCP_IDENTITY_ECHO_BINDINGS_FILE"));
        var config=Schema.map(Json.parse(ProtectedDeploymentConfig.read(path)));if(!config.keySet().equals(Set.of("format","bindings"))||Schema.number(config.get("format"))!=1)throw new IllegalArgumentException("Binding format");
        var bindings=new ArrayList<IdentityEchoClassifier.Binding>();for(Object raw:Schema.list(config.get("bindings"))){var item=Schema.map(raw);if(!item.keySet().equals(Set.of("plugin","capability","sha256","contract")))throw new IllegalArgumentException("Binding schema");bindings.add(new IdentityEchoClassifier.Binding(Schema.str(item.get("plugin")),Schema.str(item.get("capability")),Schema.str(item.get("sha256")),Schema.str(item.get("contract"))));}
        return new IdentityEchoClassifier(versions,bindings);
    }
}
