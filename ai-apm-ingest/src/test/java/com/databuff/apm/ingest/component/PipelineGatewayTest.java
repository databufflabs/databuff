package com.databuff.apm.ingest.component;

import com.databuff.apm.common.cluster.aggregate.ClusterAggregator;
import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.common.model.OptimizedMetric;
import com.databuff.apm.ingest.event.MetricEvent;
import com.databuff.apm.ingest.event.TraceEvent;
import com.databuff.apm.ingest.gateway.PipelineGateway;
import com.databuff.apm.ingest.support.IngestTestComponents;
import com.databuff.apm.common.storage.DorisBatchWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class PipelineGatewayTest {

    private AggregateComponent aggregateComponent;
    private MetricComponent metricComponent;
    private TraceComponent traceComponent;
    private PipelineGateway gateway;

    @AfterEach
    void tearDown() {
        if (traceComponent != null) {
            traceComponent.close();
        }
        if (metricComponent != null) {
            metricComponent.close();
        }
        if (aggregateComponent != null) {
            aggregateComponent.close();
        }
    }

    @Test
    void routesTraceAndMetricThroughPipeline() throws Exception {
        ClusterAggregator aggregator = new ClusterAggregator("n1");
        DorisBatchWriter writer = new DorisBatchWriter();
        aggregateComponent = IngestTestComponents.aggregate(aggregator, writer);
        metricComponent = new MetricComponent(aggregateComponent);
        traceComponent = IngestTestComponents.trace(aggregateComponent, new DorisBatchWriter(), 100L);
        aggregateComponent.start(1);
        metricComponent.start(1);
        traceComponent.start(1);
        gateway = new PipelineGateway(traceComponent, metricComponent);

        DcSpan span = new DcSpan();
        span.trace_id = "trace-1";
        span.span_id = "span-1";
        span.parent_id = "";
        span.service = "demo";
        span.serviceId = "demo-id";
        span.resource = "ping";
        span.name = "ping";
        span.hostName = "host";
        span.error = 0;
        span.duration = 1;
        span.start = 1_700_000_000_000_000_000L;
        span.end = span.start + 1;

        OptimizedMetric metric = new OptimizedMetric()
                .withTsId(9)
                .withTimestamp(span.start)
                .withMeasurement("service")
                .withTagValues("ok", "demo", "demo-id", "inst")
                .withFieldValues(1, 0, 1);

        assertThat(gateway.emit("svc1", new TraceEvent(span))).isTrue();
        assertThat(gateway.emit("svc1", MetricEvent.fromOptimized(metric))).isTrue();
        assertThat(gateway.emit("svc1", null)).isFalse();

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
            assertThat(traceComponent.receivedCount()).isEqualTo(1);
            assertThat(metricComponent.receivedCount()).isEqualTo(1);
            assertThat(aggregateComponent.processedCount()).isGreaterThanOrEqualTo(2);
        });
    }

    @Test
    void componentRejectsWhenDisabled() {
        aggregateComponent = IngestTestComponents.aggregate(new DorisBatchWriter());
        traceComponent = IngestTestComponents.trace(aggregateComponent, new DorisBatchWriter());
        traceComponent.start(1);
        traceComponent.setEnabled(false);
        assertThat(traceComponent.emit("k", new TraceEvent(null))).isFalse();
    }
}
