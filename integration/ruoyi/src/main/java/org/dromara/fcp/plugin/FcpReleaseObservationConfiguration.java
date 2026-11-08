package org.dromara.fcp.plugin;

import fcp.FcpException;
import fcp.host.HostCapabilities;
import fcp.release.control.ReleaseController;
import fcp.release.observation.*;
import fcp.storage.JdbcStateJournal;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

/** Requires an explicitly configured trusted observation provider. No browser metrics or stored user tokens. */
@Configuration
@ConditionalOnProperty(name={"fcp.release.enabled","fcp.release.observation.enabled"},havingValue="true")
public class FcpReleaseObservationConfiguration {
    @Bean public DurableRolloutAutomation fcpReleaseAutomation(DataSource source,ReleaseController releases,ObservationProvider observations,NativePluginHost host){
        return new DurableRolloutAutomation(new JdbcStateJournal(source,"release_automation"),releases,observations,(context,permission)->{
            String operation=switch(permission){case "fcp:release:view"->"view";case "fcp:release:manage"->"manage";default->throw new FcpException("PERMISSION_DENIED","Unsupported release observation permission");};
            host.require(context,new HostCapabilities.Grant("control","system.release",operation,"tenant:"+context.tenantId()));
        },host.clock());
    }
}
