package org.dromara.fcp.school;

import dev.fcp.dual.JdbcRecordStore;
import dev.fcp.host.RuntimeBundle;
import dev.fcp.ingestion.IngestionService;
import dev.fcp.ingestion.PrivateConnections;
import dev.fcp.school.JdbcPluginStateStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import javax.sql.DataSource;
import java.nio.file.Path;

@Configuration
public class FcpIngestionConfiguration {
    @Bean(destroyMethod="close")
    public IngestionService fcpIngestion(RuntimeBundle bundle,DataSource dataSource,
            FcpSchoolIdentity identity,Environment environment)throws Exception {
        FcpSchoolConfiguration.verifySchema(dataSource);
        String location=environment.getProperty("FCP_INGESTION_CONNECTIONS","");
        return new IngestionService(bundle.root(),new JdbcRecordStore(dataSource),
            new JdbcPluginStateStore(dataSource),identity::assertCurrent,
            new PrivateConnections(location.isBlank()?null:Path.of(location)));
    }
}
