package org.dromara.fcp.plugin;

import fcp.exchange.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.slf4j.LoggerFactory;

/** Approved 24-hour generated-export retention. Automatically active when exchange is enabled.
 * This removes expired snapshot content, not a physical file or business/audit/receipt records.
 */
@Configuration
@ConditionalOnProperty(name={"fcp.runtime.enabled","fcp.exchange.enabled"},havingValue="true")
public class FcpExportRetentionConfiguration {
    @Bean(destroyMethod="close") public ExportRetentionMaintenance fcpExportRetentionMaintenance(ExchangeService service){
        return new ExportRetentionMaintenance(service,error->LoggerFactory.getLogger(FcpExportRetentionConfiguration.class)
            .warn("Expired export snapshot cleanup failed ({}); retained state will be reconciled and retried",error.getClass().getSimpleName()));
    }
}
