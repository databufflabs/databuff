package com.databuff.apm.ingest.pipeline;

import com.databuff.apm.common.platform.PlatformMetricNames;
import com.databuff.apm.common.platform.PlatformMetrics;
import com.databuff.apm.ingest.component.AggregateComponent;
import com.databuff.apm.ingest.component.MetricComponent;
import com.databuff.apm.ingest.component.TraceComponent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Samples Disruptor ring occupancy and trace-assembly backlog into {@link PlatformMetrics}.
 * Overflow drops are counted immediately in {@link com.databuff.apm.ingest.pipeline.task.AsyncTask}.
 * Disabled when {@code apm.platform-metrics.enabled=false} (bean not created).
 */
@Component
@ConditionalOnProperty(name = "apm.platform-metrics.enabled", havingValue = "true", matchIfMissing = true)
public class PipelineQueueSampler {

    private final TraceComponent traceComponent;
    private final MetricComponent metricComponent;
    private final AggregateComponent aggregateComponent;

    public PipelineQueueSampler(
            TraceComponent traceComponent,
            MetricComponent metricComponent,
            AggregateComponent aggregateComponent) {
        this.traceComponent = traceComponent;
        this.metricComponent = metricComponent;
        this.aggregateComponent = aggregateComponent;
    }

    @Scheduled(fixedDelayString = "${apm.platform-metrics.pipeline-sample-interval-ms:10000}")
    void sample() {
        traceComponent.samplePipelineQueueGauges();
        metricComponent.samplePipelineQueueGauges();
        aggregateComponent.samplePipelineQueueGauges();
        PlatformMetrics.gauge(PlatformMetricNames.TRACE_ASSEMBLY_PENDING)
                .set(traceComponent.pendingAssemblyTraces());
    }
}
