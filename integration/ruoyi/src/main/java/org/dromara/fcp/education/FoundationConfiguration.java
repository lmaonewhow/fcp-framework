package org.dromara.fcp.education;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import fcp.education.FoundationStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import javax.sql.DataSource;

@Configuration
public class FoundationConfiguration {
    @Bean
    @ConditionalOnProperty(name="fcp.education.enabled",havingValue="true")
    public FoundationStore educationFoundationStore(DataSource source, Environment environment) {
        // Explicit deployment configuration, never a browser-provided manifest or ID.
        String digest=environment.getRequiredProperty("FCP_EDUCATION_MANIFEST_DIGEST");
        if (!digest.matches("[a-f0-9]{64}")) throw new IllegalStateException("Education manifest digest required");
        return new FoundationStore(source,IdWorker::getIdStr,digest);
    }
}
