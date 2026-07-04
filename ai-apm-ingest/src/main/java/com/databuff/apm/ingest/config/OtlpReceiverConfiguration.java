package com.databuff.apm.ingest.config;

import com.databuff.apm.common.storage.DorisBatchWriter;
import com.databuff.apm.ingest.gateway.PipelineGateway;
import com.databuff.apm.ingest.log.OtlpLogDirectWriter;
import com.databuff.apm.ingest.metric.OtlpMetricDirectWriter;
import com.databuff.apm.ingest.meta.MetaServiceCollector;
import com.databuff.apm.ingest.metric.MetricWriteRouter;
import com.databuff.apm.ingest.otel.OtelConverter;
import com.databuff.apm.ingest.otel.OtlpIngestService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OtlpReceiverConfiguration {

    @Bean
    OtelConverter otelConverter() {
        return new OtelConverter();
    }

    @Bean
    OtlpMetricDirectWriter otlpMetricDirectWriter(
            MetricWriteRouter metricWriteRouter,
            MetaServiceCollector metaServiceCollector) {
        return new OtlpMetricDirectWriter(metricWriteRouter, metaServiceCollector);
    }

    @Bean
    OtlpLogDirectWriter otlpLogDirectWriter(
            DorisBatchWriter logBatchWriter,
            MetaServiceCollector metaServiceCollector) {
        return new OtlpLogDirectWriter(logBatchWriter, metaServiceCollector);
    }

    @Bean
    OtlpIngestService otlpIngestService(
            OtelConverter converter,
            PipelineGateway gateway,
            OtlpMetricDirectWriter otlpMetricDirectWriter,
            OtlpLogDirectWriter otlpLogDirectWriter) {
        return new OtlpIngestService(converter, gateway, otlpMetricDirectWriter, otlpLogDirectWriter);
    }
}
