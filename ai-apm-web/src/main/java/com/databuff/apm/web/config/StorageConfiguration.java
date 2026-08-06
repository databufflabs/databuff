package com.databuff.apm.web.config;

import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.common.storage.DorisConnectionConfig;
import com.databuff.apm.web.storage.DorisAvailability;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
@EnableConfigurationProperties(ApmStorageProperties.class)
public class StorageConfiguration {

    @Bean
    DorisConnectionConfig dorisConnectionConfig(
            @Value("${apm.doris.fe-host:127.0.0.1}") String feHost,
            @Value("${apm.doris.fe-query-port:9030}") int queryPort,
            @Value("${apm.doris.fe-http-port:8030}") int httpPort,
            @Value("${apm.doris.be-http-host:}") String beHttpHost,
            @Value("${apm.doris.be-http-port:8040}") int beHttpPort) {
        String be = (beHttpHost == null || beHttpHost.isBlank()) ? null : beHttpHost;
        return new DorisConnectionConfig(feHost, queryPort, httpPort, be, beHttpPort);
    }

    @Bean
    ApmReadRepository apmReadRepository(DataSource dorisDataSource, DorisAvailability dorisAvailability) {
        return new ApmReadRepository(dorisDataSource, dorisAvailability);
    }
}
