package com.databuff.apm.ingest.component;

import com.databuff.apm.common.model.OptimizedMetric;
import com.databuff.apm.ingest.event.MetricEvent;
import com.databuff.apm.ingest.event.TraceEvent;
import com.databuff.apm.ingest.support.IngestTestComponents;
import com.databuff.apm.common.storage.DorisBatchWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class MetricComponentTest {

    private AggregateComponent aggregateComponent;
    private MetricComponent metricComponent;

    @AfterEach
    void tearDown() {
        if (metricComponent != null) {
            metricComponent.close();
        }
        if (aggregateComponent != null) {
            aggregateComponent.close();
        }
    }

    @Test
    void ignoresNonMetricEvents() {
        aggregateComponent = IngestTestComponents.aggregate(new DorisBatchWriter());
        metricComponent = new MetricComponent(aggregateComponent);
        aggregateComponent.start(1);
        metricComponent.start(1);

        assertThat(metricComponent.emit("k", new TraceEvent(null))).isTrue();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(metricComponent.receivedCount()).isZero());
    }

    @Test
    void countsMetricEvents() {
        aggregateComponent = IngestTestComponents.aggregate(new DorisBatchWriter());
        metricComponent = new MetricComponent(aggregateComponent);
        aggregateComponent.start(1);
        metricComponent.start(1);

        assertThat(metricComponent.emit("k", MetricEvent.fromOptimized(sampleMetric()))).isTrue();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(metricComponent.receivedCount()).isEqualTo(1));
    }

    private static OptimizedMetric sampleMetric() {
        return new OptimizedMetric()
                .withTsId(1)
                .withTimestamp(1_700_000_000_000_000_000L)
                .withMeasurement("service")
                .withTagValues("ok", "demo", "demo-id", "inst")
                .withFieldValues(1, 0, 100);
    }
}
