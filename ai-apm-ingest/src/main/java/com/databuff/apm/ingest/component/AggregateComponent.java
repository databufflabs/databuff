package com.databuff.apm.ingest.component;

import com.databuff.apm.common.platform.PlatformMetricNames;
import com.databuff.apm.common.platform.PlatformMetrics;
import com.databuff.apm.common.cluster.aggregate.ClusterAggregator;
import com.databuff.apm.common.cluster.aggregate.ClusterPartialForwarder;
import com.databuff.apm.common.cluster.coordination.ClusterPartitionMembership;
import com.databuff.apm.common.model.OptimizedMetric;
import com.databuff.apm.common.serde.OptimizedMetricUtil;
import com.databuff.apm.ingest.event.AggregateEvent;
import com.databuff.apm.ingest.event.MetricEvent;
import com.databuff.apm.ingest.meta.MetaServiceCollector;
import com.databuff.apm.ingest.metric.MetricWriteRouter;
import com.databuff.apm.ingest.metric.OptimizedMetricAccumulator;
import com.databuff.apm.ingest.metric.OtlpMetricDirectWriter;
import com.databuff.apm.ingest.metric.TraceMinuteMetricAggregator;
import com.databuff.apm.ingest.otel.OtlMetricLine;
import com.databuff.apm.ingest.pipeline.component.AbstractComponent;
import com.databuff.apm.ingest.pipeline.pool.TaskPool;
import com.databuff.apm.ingest.pipeline.shard.HashShardingStrategy;
import com.databuff.apm.ingest.pipeline.task.AsyncTask;
import com.databuff.apm.common.metric.TraceMetricMinuteBucket;
import com.databuff.apm.common.storage.DorisBatchWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 指标聚合组件（Stage-1 merge），统一入口：
 * <ul>
 *   <li>trace 提取的指标（除 {@code service.instance}）→ {@link TraceMinuteMetricAggregator} 分钟窗口聚合 → Doris</li>
 *   <li>agent OptimizedMetric 格式 → stage-1 merge 后写表（OTLP 指标见 {@link OtlpMetricDirectWriter}）</li>
 *   <li>集群：非 owner 时序列化 partial → gRPC ForwardPartial</li>
 * </ul>
 */
public final class AggregateComponent extends AbstractComponent<AggregateComponent.AggregateTask> {

    private static final Logger log = LoggerFactory.getLogger(AggregateComponent.class);

    /** OptimizedMetric 聚合 stream，partition key = serviceKey。 */
    public static final String STREAM = "ingest.metric.optimized";

    private final ClusterAggregator clusterAggregator;
    private final ClusterPartitionMembership partitionMembership;
    private final ClusterPartialForwarder partialForwarder;
    private final MetricWriteRouter metricWriteRouter;
    private final MetaServiceCollector metaServiceCollector;
    private final TraceMinuteMetricAggregator traceMinuteMetricAggregator;
    private final int bufferSize;
    private final AtomicLong processed = new AtomicLong();
    private AggregateTask[] aggregateTasks;

    public AggregateComponent(
            ClusterAggregator clusterAggregator,
            ClusterPartitionMembership partitionMembership,
            DorisBatchWriter batchWriter) {
        this(
                clusterAggregator,
                partitionMembership,
                MetricWriteRouter.singleTable(batchWriter),
                ClusterPartialForwarder.NOOP);
    }

    public AggregateComponent(
            ClusterAggregator clusterAggregator,
            ClusterPartitionMembership partitionMembership,
            MetricWriteRouter metricWriteRouter) {
        this(clusterAggregator, partitionMembership, metricWriteRouter, ClusterPartialForwarder.NOOP);
    }

    public AggregateComponent(
            ClusterAggregator clusterAggregator,
            ClusterPartitionMembership partitionMembership,
            DorisBatchWriter batchWriter,
            ClusterPartialForwarder partialForwarder) {
        this(
                clusterAggregator,
                partitionMembership,
                MetricWriteRouter.singleTable(batchWriter),
                partialForwarder);
    }

    public AggregateComponent(
            ClusterAggregator clusterAggregator,
            ClusterPartitionMembership partitionMembership,
            MetricWriteRouter metricWriteRouter,
            ClusterPartialForwarder partialForwarder) {
        this(clusterAggregator, partitionMembership, metricWriteRouter, partialForwarder, null);
    }

    public AggregateComponent(
            ClusterAggregator clusterAggregator,
            ClusterPartitionMembership partitionMembership,
            MetricWriteRouter metricWriteRouter,
            ClusterPartialForwarder partialForwarder,
            MetaServiceCollector metaServiceCollector) {
        this(
                clusterAggregator,
                partitionMembership,
                metricWriteRouter,
                partialForwarder,
                metaServiceCollector,
                TraceMinuteMetricAggregator.DEFAULT_LATE_FLUSH_GRACE_MS);
    }

    public AggregateComponent(
            ClusterAggregator clusterAggregator,
            ClusterPartitionMembership partitionMembership,
            MetricWriteRouter metricWriteRouter,
            ClusterPartialForwarder partialForwarder,
            MetaServiceCollector metaServiceCollector,
            long traceMinuteLateFlushGraceMs) {
        this(
                clusterAggregator,
                partitionMembership,
                metricWriteRouter,
                partialForwarder,
                metaServiceCollector,
                traceMinuteLateFlushGraceMs,
                1024);
    }

    public AggregateComponent(
            ClusterAggregator clusterAggregator,
            ClusterPartitionMembership partitionMembership,
            MetricWriteRouter metricWriteRouter,
            ClusterPartialForwarder partialForwarder,
            MetaServiceCollector metaServiceCollector,
            long traceMinuteLateFlushGraceMs,
            int bufferSize) {
        this.clusterAggregator = clusterAggregator;
        this.partitionMembership = partitionMembership;
        this.metricWriteRouter = metricWriteRouter;
        this.partialForwarder = partialForwarder;
        this.metaServiceCollector = metaServiceCollector;
        this.bufferSize = Math.max(16, bufferSize);
        clusterAggregator.registerMerger(STREAM, OptimizedMetricUtil::mergeSerializedBytes);
        this.traceMinuteMetricAggregator = new TraceMinuteMetricAggregator(
                clusterAggregator,
                partitionMembership,
                partialForwarder,
                metricWriteRouter,
                traceMinuteLateFlushGraceMs);
    }

    @Override
    public void close() {
        traceMinuteMetricAggregator.close();
        super.close();
    }

    @Override
    protected String getName() {
        return "aggregate";
    }

    @Override
    protected TaskPool<AggregateTask> generateTaskPool(int taskSize) {
        aggregateTasks = new AggregateTask[taskSize];
        for (int i = 0; i < taskSize; i++) {
            aggregateTasks[i] = new AggregateTask(i);
        }
        return new TaskPool<>(new HashShardingStrategy(), aggregateTasks);
    }

    /** Drains in-memory OptimizedMetric merges into Doris batch writers (for low-volume traffic). */
    public void flushPendingMetrics() {
        if (aggregateTasks == null) {
            return;
        }
        for (AggregateTask task : aggregateTasks) {
            task.flushPendingMetrics();
        }
    }

    /**
     * Step 5 入口：trace fill 提取的 OptimizedMetric。
     * trace 抽取指标走分钟窗口聚合（不经 Aggregate Disruptor）；agent 直报指标走 {@link #acceptFromMetric}。
     * 自监控仍记入 {@code pipeline.aggregate.*}，否则 UI「Aggregate」行在纯 OTLP Trace 场景下会一直空。
     */
    public void acceptExtractedMetrics(String serviceKey, List<OptimizedMetric> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return;
        }
        long startNs = System.nanoTime();
        try {
            for (OptimizedMetric metric : metrics) {
                if (TraceMetricMinuteBucket.requiresMinuteAggregation(metric.measurement())) {
                    traceMinuteMetricAggregator.accept(metric);
                } else {
                    metricWriteRouter.offer(metric);
                }
            }
            processed.incrementAndGet();
            PlatformMetrics.counter(
                            PlatformMetricNames.pipeline(
                                    PlatformMetricNames.PIPELINE_AGGREGATE, PlatformMetricNames.KIND_REQ))
                    .add(metrics.size());
            PlatformMetrics.timer(
                            PlatformMetricNames.pipeline(
                                    PlatformMetricNames.PIPELINE_AGGREGATE, PlatformMetricNames.KIND_COST_MS))
                    .add(Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)));
        } catch (Exception e) {
            log.warn("Failed to store extracted metrics for {}: {}", serviceKey, e.getMessage());
        }
    }

    public void acceptForwardedTraceMinutePartial(
            String partitionKey,
            long windowStartMs,
            long windowEndMs,
            byte[] partial) {
        traceMinuteMetricAggregator.acceptForwarded(partitionKey, windowStartMs, windowEndMs, partial);
        processed.incrementAndGet();
    }

    void flushTraceMinuteWindowForTest(long windowStartMs) {
        traceMinuteMetricAggregator.flushWindow(windowStartMs);
    }

    /** @deprecated use {@link #acceptExtractedMetrics} */
    public void acceptFromTrace(String serviceKey, List<OptimizedMetric> metrics) {
        acceptExtractedMetrics(serviceKey, metrics);
    }

    /** Agent 直报 metric 入口（OTLP 或 OptimizedMetric 格式）。 */
    public void acceptFromMetric(String serviceKey, MetricEvent metricEvent) {
        if (metricEvent.otlpLine() != null) {
            OtlMetricLine line = metricEvent.otlpLine();
            if (metaServiceCollector != null) {
                metaServiceCollector.remember(line);
            }
            emit(serviceKey, AggregateEvent.fromOtlp(line));
            return;
        }
        if (metricEvent.optimizedMetric() != null) {
            emit(serviceKey, AggregateEvent.fromMetric(metricEvent.optimizedMetric()));
        }
    }

    /** Ingest partial forwarded from a peer ingest node (owner path). */
    public boolean acceptForwardedPartial(String serviceKey, AggregateEvent event) {
        return emit(serviceKey, event);
    }

    public long processedCount() {
        return processed.get();
    }

    final class AggregateTask extends AsyncTask {

        private final OptimizedMetricAccumulator accumulator = new OptimizedMetricAccumulator();

        AggregateTask(int taskIndex) {
            super(bufferSize, taskIndex, PlatformMetricNames.PIPELINE_AGGREGATE);
        }

        @Override
        protected void processEvent(Object key, Object event) {
            if (!(event instanceof AggregateEvent aggregateEvent)) {
                return;
            }
            String serviceKey = String.valueOf(key);
            long windowStart = 0;
            long windowEnd = 60;
            OptimizedMetric metric = aggregateEvent.metric();
            OtlMetricLine otlpLine = aggregateEvent.otlpLine();
            byte[] partial = aggregateEvent.optimizedMetricBytes();
            if (metric != null) {
                partial = OptimizedMetricUtil.serialize(metric);
            } else if (otlpLine != null) {
                try {
                    partial = otlpLine.toJsonBytes();
                } catch (Exception ignored) {
                    processed.incrementAndGet();
                    return;
                }
            }
            Optional<String> forwardTarget = partitionMembership.forwardPartialTarget(serviceKey);
            if (forwardTarget.isPresent()) {
                // 集群非 owner：序列化后 gRPC 转发给 owner 聚合
                partialForwarder.forward(
                        forwardTarget.get(), STREAM, serviceKey, windowStart, windowEnd, partial);
                processed.incrementAndGet();
                return;
            }
            if (metric != null) {
                // 单条 OptimizedMetric：stage-1 merge
                clusterAggregator.emitStage1(STREAM, serviceKey, windowStart, windowEnd, partial);
                if (partitionMembership.owns(serviceKey)) {
                    accumulator.merge(metric);
                    if (accumulator.size() >= 32) {
                        flushAccumulator();
                    }
                }
            } else if (otlpLine != null) {
                // OTLP agent 指标（JVM/连接池）：非 OptimizedMetric，映射后直接写表
                if (partitionMembership.owns(serviceKey)) {
                    metricWriteRouter.offerOtlp(otlpLine);
                }
            } else {
                clusterAggregator.emitStage1(STREAM, serviceKey, windowStart, windowEnd, partial);
                if (partitionMembership.owns(serviceKey)) {
                    if (OptimizedMetricUtil.isOptimizedFormat(partial)) {
                        accumulator.merge(partial);
                        if (accumulator.size() >= 32) {
                            flushAccumulator();
                        }
                    } else {
                        metricWriteRouter.offerRaw(partial);
                    }
                }
            }
            processed.incrementAndGet();
        }

        @Override
        public void onShutdown() {
            try {
                flushAccumulator();
            } catch (Exception ignored) {
                // best effort on shutdown
            }
            super.onShutdown();
        }

        private void flushAccumulator() {
            try {
                for (OptimizedMetric metric : accumulator.drainMetrics()) {
                    metricWriteRouter.offer(metric);
                }
            } catch (Exception e) {
                log.warn("Failed to flush merged metrics: {}", e.getMessage());
            }
        }

        private void flushPendingMetrics() {
            if (accumulator.size() > 0) {
                flushAccumulator();
            }
        }
    }
}
