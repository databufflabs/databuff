package com.databuff.apm.ingest.component;

import com.databuff.apm.common.cluster.aggregate.ClusterAggregator;
import com.databuff.apm.common.cluster.aggregate.RecordingClusterPartialForwarder;
import com.databuff.apm.ingest.event.AggregateEvent;
import com.databuff.apm.ingest.event.MetricEvent;
import com.databuff.apm.ingest.metric.MetricWriteRouter;
import com.databuff.apm.ingest.support.IngestTestComponents;
import com.databuff.apm.ingest.support.TestClusterMembership;
import com.databuff.apm.common.metric.TraceMetricMinuteBucket;
import com.databuff.apm.common.model.OptimizedMetric;
import com.databuff.apm.common.serde.OptimizedMetricUtil;
import com.databuff.apm.common.storage.DorisBatchWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class AggregateComponentTest {

    private AggregateComponent component;

    @AfterEach
    void tearDown() {
        if (component != null) {
            component.close();
        }
    }

    @Test
    void mergeBytesCombinesOptimizedMetrics() {
        ClusterAggregator aggregator = new ClusterAggregator("n1");
        DorisBatchWriter writer = new DorisBatchWriter();
        component = IngestTestComponents.aggregate(aggregator, writer);
        component.start(1);

        byte[] left = OptimizedMetricUtil.serialize(sampleMetric(1).withFieldValues(1, 0, 10));
        byte[] right = OptimizedMetricUtil.serialize(sampleMetric(1).withFieldValues(2, 1, 20));
        assertThat(component.emit("svc", new AggregateEvent(left))).isTrue();
        assertThat(component.emit("svc", new AggregateEvent(right))).isTrue();

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(component.processedCount()).isEqualTo(2));
    }

    @Test
    void ignoresUnknownPayload() {
        ClusterAggregator aggregator = new ClusterAggregator("n1");
        DorisBatchWriter writer = new DorisBatchWriter();
        component = IngestTestComponents.aggregate(aggregator, writer);
        component.start(1);
        assertThat(component.emit("svc", "not-an-event")).isTrue();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(component.processedCount()).isZero());
    }

    @Test
    void extractedServiceMetricsUseMinuteAggregationBeforeFlush() {
        ClusterAggregator aggregator = new ClusterAggregator("n1");
        DorisBatchWriter writer = new DorisBatchWriter();
        component = IngestTestComponents.aggregate(aggregator, writer);
        component.start(1);

        OptimizedMetric service = sampleMetric(1).withFieldValues(1, 0, 10);
        OptimizedMetric trace = sampleMetric(2).withMeasurement("service.trace").withFieldValues(1, 0, 20);
        component.acceptExtractedMetrics("svc", List.of(service, trace));

        assertThat(component.processedCount()).isEqualTo(1);
        assertThat(writer.pendingCount()).isZero();
        long windowMs = TraceMetricMinuteBucket.minuteBucketEpochMs(service.timestamp());
        component.flushTraceMinuteWindowForTest(windowMs);
        assertThat(writer.pendingCount()).isEqualTo(2);
    }

    @Test
    void acceptsTraceAndMetricSources() {
        ClusterAggregator aggregator = new ClusterAggregator("n1");
        DorisBatchWriter writer = new DorisBatchWriter();
        component = IngestTestComponents.aggregate(aggregator, writer);
        component.start(1);

        component.acceptExtractedMetrics("svc", List.of(sampleMetric(1)));
        component.acceptFromMetric("svc", MetricEvent.fromOptimized(
                sampleMetric(2)));

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(component.processedCount()).isEqualTo(2));
    }

    @Test
    void forwardsWhenNotOwner() throws InterruptedException {
        ClusterAggregator aggregator = new ClusterAggregator("n2");
        TestClusterMembership.Mutable membership = TestClusterMembership.mutable("n2");
        membership.setClusterEnabled(true);
        membership.setMembers(java.util.List.of("n1", "n2"));
        membership.setEndpoint("n1", "127.0.0.1:18112");
        membership.setEndpoint("n2", "127.0.0.1:18113");
        RecordingClusterPartialForwarder forwarder = new RecordingClusterPartialForwarder();
        DorisBatchWriter writer = new DorisBatchWriter();
        component = new AggregateComponent(
                aggregator, membership, MetricWriteRouter.singleTable(writer), forwarder);
        component.start(1);

        String partitionKey = java.util.stream.IntStream.range(0, 200)
                .mapToObj(i -> "svc-" + i)
                .filter(k -> !membership.owns(k))
                .findFirst()
                .orElseThrow();

        assertThat(membership.forwardPartialTarget(partitionKey)).isPresent();
        assertThat(component.emit(partitionKey, new AggregateEvent(
                OptimizedMetricUtil.serialize(sampleMetric(3))))).isTrue();
        Thread.sleep(200);
        assertThat(writer.pendingCount()).isZero();
        assertThat(forwarder.forwarded()).hasSize(1);
    }

    @Test
    void acceptsJsonPassthroughMetrics() {
        ClusterAggregator aggregator = new ClusterAggregator("n1");
        DorisBatchWriter writer = new DorisBatchWriter();
        component = IngestTestComponents.aggregate(aggregator, writer);
        component.start(1);

        assertThat(component.emit("svc", new AggregateEvent("{\"cnt\":1}".getBytes()))).isTrue();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(writer.pendingCount()).isEqualTo(1));
    }

    @Test
    void acceptForwardedPartialUsesSamePipeline() {
        ClusterAggregator aggregator = new ClusterAggregator("n1");
        DorisBatchWriter writer = new DorisBatchWriter();
        component = IngestTestComponents.aggregate(aggregator, writer);
        component.start(1);

        byte[] partial = OptimizedMetricUtil.serialize(sampleMetric(4));
        assertThat(component.acceptForwardedPartial("svc", new AggregateEvent(partial))).isTrue();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(component.processedCount()).isEqualTo(1));
    }

    @Test
    void flushPendingMetricsDrainsMergedAgentMetricsBeforeBatchThreshold() throws Exception {
        ClusterAggregator aggregator = new ClusterAggregator("n1");
        DorisBatchWriter writer = new DorisBatchWriter();
        component = IngestTestComponents.aggregate(aggregator, writer);
        component.start(1);

        byte[] left = OptimizedMetricUtil.serialize(sampleMetric(1).withFieldValues(1, 0, 10));
        byte[] right = OptimizedMetricUtil.serialize(sampleMetric(1).withFieldValues(2, 1, 20));
        assertThat(component.emit("svc", new AggregateEvent(left))).isTrue();
        assertThat(component.emit("svc", new AggregateEvent(right))).isTrue();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() ->
                assertThat(component.processedCount()).isEqualTo(2));
        assertThat(writer.pendingCount()).isZero();

        component.flushPendingMetrics();
        assertThat(writer.pendingCount()).isEqualTo(1);
    }

    private static OptimizedMetric sampleMetric(int tsId) {
        return new OptimizedMetric()
                .withTsId(tsId)
                .withTimestamp(1_700_000_000_000_000_000L)
                .withMeasurement("service")
                .withTagValues("ok", "demo", "demo-id", "inst")
                .withFieldValues(1, 0, 100);
    }
}
