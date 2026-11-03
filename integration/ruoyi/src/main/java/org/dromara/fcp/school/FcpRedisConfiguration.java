package org.dromara.fcp.school;

import org.dromara.common.core.utils.reflect.ReflectUtils;
import org.redisson.config.SingleServerConfig;
import org.redisson.spring.starter.RedissonAutoConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Empty default-user credentials mean no AUTH, consistent with an unconfigured password. */
@Configuration
@Profile("fcp")
public class FcpRedisConfiguration {
    @Bean
    public RedissonAutoConfigurationCustomizer fcpRedisPasswordCustomizer() {
        return config -> {
            SingleServerConfig server=ReflectUtils.invokeGetter(config,"singleServerConfig");
            if(server!=null && "".equals(server.getPassword())
                    && (server.getUsername()==null || server.getUsername().isEmpty()))
                server.setPassword(null);
        };
    }
}
