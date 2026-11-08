package org.dromara.fcp.release;
import dev.fcp.host.ui.*;
import fcp.FcpException;
import fcp.release.routing.DurableReleaseRouter;
import fcp.release.version.VersionProvider;
import org.dromara.fcp.school.FcpSchoolIdentity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import java.nio.file.Path;
@Configuration
@ConditionalOnProperty(name="fcp.ui.release.enabled",havingValue="true")
public class NativeUiReleaseConfiguration {
    @Bean public UiBundleStore fcpUiBundles(Environment env)throws Exception{return new UiBundleStore(Path.of(env.getRequiredProperty("FCP_UI_BUNDLE_ROOT")));}
    @Bean public UiReleaseApplication fcpUiReleaseApplication(DurableReleaseRouter router,VersionProvider versions,UiBundleStore bundles,FcpSchoolIdentity identity){
        return new UiReleaseApplication(router,versions,bundles,"fcp.host/1",(context,shell)->{
            var id=identity.current();
            if(!id.subject().equals(context.principal().id())||!id.scope().equals("tenant-"+context.tenantId())||(!id.can("list")&&!id.permissions().contains("fcp:plugin:view")&&!id.permissions().contains("fcp:ingest:view")&&!(id.scope().equals("tenant-000000")&&id.permissions().contains("fcp:release:view")))||("admin".equals(shell)&&!id.adminShell()))throw new FcpException("PERMISSION_DENIED","Current native shell permission required");
        });
    }
}
