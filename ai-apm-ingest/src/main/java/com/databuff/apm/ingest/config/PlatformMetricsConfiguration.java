package com.databuff.apm.ingest.config;

import com.databuff.apm.common.platform.DorisPlatformMetricExporter;
import com.databuff.apm.common.platform.PlatformMetricNames;
import com.databuff.apm.common.platform.PlatformMetrics;
import com.databuff.apm.common.storage.DorisStreamLoader;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

@Configuration
public class PlatformMetricsConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PlatformMetricsConfiguration.class);

    private final DorisStreamLoader dorisStreamLoader;
    private final String database;
    private final boolean enabled;

    public PlatformMetricsConfiguration(
            DorisStreamLoader dorisStreamLoader,
            @Value("${ingest.doris.metric-database:databuff}") String database,
            @Value("${apm.platform-metrics.enabled:true}") boolean enabled) {
        this.dorisStreamLoader = dorisStreamLoader;
        this.database = database;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startPlatformMetrics() {
        if (!enabled) {
            PlatformMetrics.disable();
            log.info("Ingest platform self-metrics disabled (apm.platform-metrics.enabled=false)");
            return;
        }
        PlatformMetrics.start(
                PlatformMetricNames.COMPONENT_INGEST,
                new DorisPlatformMetricExporter(dorisStreamLoader, database));
    }

    @PreDestroy
    public void stopPlatformMetrics() {
        PlatformMetrics.stop();
    }
}
