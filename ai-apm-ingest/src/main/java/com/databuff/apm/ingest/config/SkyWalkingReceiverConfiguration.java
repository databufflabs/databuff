package com.databuff.apm.ingest.config;

import com.databuff.apm.ingest.gateway.PipelineGateway;
import com.databuff.apm.ingest.log.OtlpLogDirectWriter;
import com.databuff.apm.ingest.metric.OtlpMetricDirectWriter;
import com.databuff.apm.ingest.skywalking.SkyWalkingConverter;
import com.databuff.apm.ingest.skywalking.SkyWalkingIngestService;
import com.databuff.apm.ingest.skywalking.SkyWalkingJvmConverter;
import com.databuff.apm.ingest.skywalking.SkyWalkingLogConverter;
import com.databuff.apm.ingest.skywalking.SkyWalkingMetaNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SkyWalkingReceiverConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SkyWalkingReceiverConfiguration.class);

    @Bean
    SkyWalkingConverter skyWalkingConverter(
            @Value("${ingest.skywalking.sql-normalized-type:1}") int sqlNormalizedType) {
        int applied = SkyWalkingMetaNormalizer.setSqlNormalizedType(sqlNormalizedType);
        if (applied != sqlNormalizedType) {
            log.warn(
                    "Invalid ingest.skywalking.sql-normalized-type={} (expected -1|0|1); using {}",
                    sqlNormalizedType,
                    applied);
        } else {
            log.info("SkyWalking SQL normalize mode={}", applied);
        }
        return new SkyWalkingConverter();
    }

    @Bean
    SkyWalkingJvmConverter skyWalkingJvmConverter() {
        return new SkyWalkingJvmConverter();
    }

    @Bean
    SkyWalkingLogConverter skyWalkingLogConverter() {
        return new SkyWalkingLogConverter();
    }

    @Bean
    SkyWalkingIngestService skyWalkingIngestService(
            SkyWalkingConverter traceConverter,
            SkyWalkingJvmConverter jvmConverter,
            SkyWalkingLogConverter logConverter,
            PipelineGateway gateway,
            OtlpMetricDirectWriter metricDirectWriter,
            OtlpLogDirectWriter logDirectWriter) {
        return new SkyWalkingIngestService(
                traceConverter,
                jvmConverter,
                logConverter,
                gateway,
                metricDirectWriter,
                logDirectWriter);
    }
}
