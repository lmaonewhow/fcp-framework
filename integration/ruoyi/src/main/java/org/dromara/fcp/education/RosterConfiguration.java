package org.dromara.fcp.education;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import fcp.education.RosterStore;
import fcp.education.RosterArtifactIdentity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import javax.sql.DataSource;

@Configuration
public class RosterConfiguration {
    @Bean
    @ConditionalOnProperty(name="fcp.education.roster.enabled",havingValue="true")
    public RosterStore educationRosterStore(DataSource source,Environment environment) {
        String digest=RosterArtifactIdentity.verifyCurrentArchive(environment.getRequiredProperty("FCP_EDUCATION_ROSTER_MANIFEST_DIGEST"),RosterConfiguration.class);
        return new RosterStore(source,IdWorker::getIdStr,digest);
    }
}
