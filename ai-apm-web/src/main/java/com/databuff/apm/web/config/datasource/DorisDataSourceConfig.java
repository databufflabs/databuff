package com.databuff.apm.web.config.datasource;

import com.alibaba.druid.pool.DruidDataSource;
import com.databuff.apm.common.storage.DorisConnectionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * Doris OLAP JDBC pool — aligned with webapp {@code StarRocksDataSourceConfig}.
 */
@Configuration
public class DorisDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DorisDataSourceConfig.class);

    @Bean(destroyMethod = "close")
    public DataSource dorisDataSource(
            DorisConnectionConfig config,
            @Value("${apm.doris.username:root}") String username,
            @Value("${apm.doris.password:}") String password) {
        DruidDataSource dataSource = new DruidDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(config.feJdbcUrl());
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setQueryTimeout(300);
        dataSource.setValidationQuery("show status;");
        dataSource.setMaxActive(20);
        dataSource.setInitialSize(0);
        dataSource.setMaxWait(60_000);
        dataSource.setMinIdle(1);
        dataSource.setTestWhileIdle(true);
        dataSource.setTestOnBorrow(false);
        dataSource.setTestOnReturn(false);
        try {
            dataSource.init();
            log.info("Doris JDBC pool ready for {}:{}", config.feHost(), config.queryPort());
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Doris JDBC pool init failed for " + config.feHost() + ":" + config.queryPort()
                            + " — if on macOS with Clash/V2Ray, disable JVM system proxy for JDBC",
                    e);
        }
        return dataSource;
    }
}
