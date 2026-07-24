package com.databuff.apm.ingest.metric;

import com.databuff.apm.common.cluster.aggregate.ClusterAggregator;
import com.databuff.apm.common.cluster.aggregate.RecordingClusterPartialForwarder;
import com.databuff.apm.common.metric.TraceMetricMinuteBucket;
import com.databuff.apm.common.model.OptimizedMetric;
import com.databuff.apm.common.serde.OptimizedMetricUtil;
import com.databuff.apm.common.storage.DorisBatchWriter;
import com.databuff.apm.ingest.support.TestClusterMembership;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TraceMinuteMetricAggregatorTest {

    private TraceMinuteMetricAggregator aggregator;

    @AfterEach
    void tearDown() {
        if (aggregator != null) {
            aggregator.close();
        }
    }

    @Test
    void mergesSameMinuteBucketLocallyBeforeFlush() throws Exception {
        DorisBatchWriter writer = new DorisBatchWriter();
        aggregator = new TraceMinuteMetricAggregator(
                new ClusterAggregator("n1"),
                TestClusterMembership.standalone("n1"),
                RecordingClusterPartialForwarder.NOOP,
                MetricWriteRouter.singleTable(writer));

        long windowMs = 1_700_000_040_000L;
        aggregator.accept(sampleServiceMetric(windowMs, 1, 0, 10));
        aggregator.accept(sampleServiceMetric(windowMs, 2, 1, 20));

        assertThat(writer.pendingCount()).isZero();
        assertThat(aggregator.flushWindow(windowMs)).isEqualTo(1);
        assertThat(writer.pendingCount()).isEqualTo(1);
    }

    @Test
    void forwardsWhenClusterNotOwner() {
        TestClusterMembership.Mutable membership = TestClusterMembership.mutable("n2");
        membership.setClusterEnabled(true);
        membership.setMembers(java.util.List.of("n1", "n2"));
        membership.setEndpoint("n1", "127.0.0.1:18112");
        membership.setEndpoint("n2", "127.0.0.1:18113");
        RecordingClusterPartialForwarder forwarder = new RecordingClusterPartialForwarder();
        aggregator = new TraceMinuteMetricAggregator(
                new ClusterAggregator("n2"),
                membership,
                forwarder,
                MetricWriteRouter.singleTable(new DorisBatchWriter()));

        long windowMs = 1_700_000_040_000L;
        String partitionKey = TraceMetricMinuteBucket.aggregationPartitionKey(sampleServiceMetric(windowMs, 1, 0, 10));
        assertThat(membership.forwardPartialTarget(partitionKey)).isPresent();

        aggregator.accept(sampleServiceMetric(windowMs, 1, 0, 10));

        assertThat(forwarder.forwarded()).hasSize(1);
        assertThat(forwarder.forwarded().get(0).stream()).isEqualTo(TraceMinuteMetricAggregator.STREAM);
        assertThat(forwarder.forwarded().get(0).windowStart()).isEqualTo(windowMs);
        assertThat(forwarder.forwarded().get(0).windowEnd()).isEqualTo(windowMs + 60_000L);
    }

    @Test
    void flushTickWaitsLateFlushGraceBeforeReFlush() throws Exception {
        DorisBatchWriter writer = new DorisBatchWriter();
        aggregator = new TraceMinuteMetricAggregator(
                new ClusterAggregator("n1"),
                TestClusterMembership.standalone("n1"),
                RecordingClusterPartialForwarder.NOOP,
                MetricWriteRouter.singleTable(writer),
                100L);

        long windowMs = 1_700_000_040_000L;
        aggregator.accept(sampleServiceMetric(windowMs, 1, 0, 10));
        aggregator.flushWindow(windowMs);
        aggregator.accept(sampleServiceMetric(windowMs, 1, 0, 20));

        aggregator.runFlushTick();
        assertThat(writer.pendingCount()).isEqualTo(1);

        Thread.sleep(150);
        aggregator.runFlushTick();
        assertThat(writer.pendingCount()).isEqualTo(2);
    }

    @Test
    void reFlushesClosedWindowWhenLateMetricsArrive() throws Exception {
        DorisBatchWriter writer = new DorisBatchWriter();
        aggregator = new TraceMinuteMetricAggregator(
                new ClusterAggregator("n1"),
                TestClusterMembership.standalone("n1"),
                RecordingClusterPartialForwarder.NOOP,
                MetricWriteRouter.singleTable(writer));

        long windowMs = 1_700_000_040_000L;
        aggregator.accept(sampleServiceMetric(windowMs, 1, 0, 10));
        assertThat(aggregator.flushWindow(windowMs)).isEqualTo(1);

        aggregator.accept(sampleServiceMetric(windowMs, 1, 0, 20));
        assertThat(aggregator.flushWindow(windowMs)).isEqualTo(1);
        assertThat(writer.pendingCount()).isEqualTo(2);
    }

    @Test
    void ownerMergesForwardedPartial() throws Exception {
        TestClusterMembership.Mutable membership = TestClusterMembership.mutable("n1");
        membership.setClusterEnabled(true);
        membership.setMembers(java.util.List.of("n1", "n2"));
        membership.setEndpoint("n1", "127.0.0.1:18112");
        membership.setEndpoint("n2", "127.0.0.1:18113");
        DorisBatchWriter writer = new DorisBatchWriter();
        aggregator = new TraceMinuteMetricAggregator(
                new ClusterAggregator("n1"),
                membership,
                RecordingClusterPartialForwarder.NOOP,
                MetricWriteRouter.singleTable(writer));

        long windowMs = 1_700_000_040_000L;
        OptimizedMetric local = sampleServiceMetric(windowMs, 1, 0, 10);
        aggregator.accept(local);

        String partitionKey = TraceMetricMinuteBucket.aggregationPartitionKey(local);
        aggregator.acceptForwarded(
                partitionKey,
                windowMs,
                windowMs + 60_000L,
                OptimizedMetricUtil.serialize(
                        sampleServiceMetric(windowMs, 1, 0, 5)));

        assertThat(aggregator.flushWindow(windowMs)).isEqualTo(1);
        assertThat(writer.pendingCount()).isEqualTo(1);
    }

    private static OptimizedMetric sampleServiceMetric(long windowMs, int tsId, long error, long duration) {
        long minuteNs = windowMs * 1_000_000L;
        OptimizedMetric metric = new OptimizedMetric()
                .withTimestamp(minuteNs)
                .withMeasurement("service")
                .withTagValues("ok", "demo", "demo-id", "inst")
                .withFieldValues(1, error, duration);
        return metric.withTsId(TraceMetricMinuteBucket.aggregationTsId(metric, windowMs));
    }
}
