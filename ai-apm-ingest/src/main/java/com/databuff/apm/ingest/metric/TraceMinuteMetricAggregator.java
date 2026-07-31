package com.databuff.apm.ingest.metric;

import com.databuff.apm.common.cluster.aggregate.ClusterAggregator;
import com.databuff.apm.common.cluster.aggregate.ClusterPartialForwarder;
import com.databuff.apm.common.cluster.coordination.ClusterPartitionMembership;
import com.databuff.apm.common.metric.TraceMetricMinuteBucket;
import com.databuff.apm.common.model.OptimizedMetric;
import com.databuff.apm.common.serde.OptimizedMetricUtil;
import com.databuff.apm.ingest.cluster.ClusterAggregationLog;
import com.databuff.apm.ingest.support.LogRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Trace 抽取指标分钟窗口聚合（{@link TraceMetricMinuteBucket}）：
 * <ul>
 *   <li>唯一 key = tags + span end 所在分钟</li>
 *   <li>分钟窗口首次关闭后立即 flush；已 flush 窗口因迟到数据重开时，等待 {@link #DEFAULT_LATE_FLUSH_GRACE_MS} 再 flush</li>
 *   <li>多次写入同一维度由 Doris AGGREGATE 表合并；查询侧 SUM 汇总</li>
 *   <li>单机本地 merge；集群非 owner 转发 partial，owner 二次 merge 后写 Doris</li>
 * </ul>
 */
public final class TraceMinuteMetricAggregator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TraceMinuteMetricAggregator.class);
    private static final LogRateLimiter DROP_LIMITER = new LogRateLimiter(10_000L);

    public static final String STREAM = "ingest.metric.trace-minute";

    /** 已 flush 窗口因迟到数据重开后的等待时间（ms），默认 20s。 */
    public static final long DEFAULT_LATE_FLUSH_GRACE_MS = 20_000L;

    private final ClusterAggregator clusterAggregator;
    private final ClusterPartitionMembership partitionMembership;
    private final ClusterPartialForwarder partialForwarder;
    private final MetricWriteRouter metricWriteRouter;
    private final Map<String, OptimizedMetric> localBuckets = new LinkedHashMap<>();
    private final Set<Long> flushedWindows = new HashSet<>();
    private final Map<Long, Long> lateFlushDeadlineMs = new LinkedHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final long lateFlushGraceMs;

    public TraceMinuteMetricAggregator(
            ClusterAggregator clusterAggregator,
            ClusterPartitionMembership partitionMembership,
            ClusterPartialForwarder partialForwarder,
            MetricWriteRouter metricWriteRouter) {
        this(clusterAggregator, partitionMembership, partialForwarder, metricWriteRouter, DEFAULT_LATE_FLUSH_GRACE_MS);
    }

    public TraceMinuteMetricAggregator(
            ClusterAggregator clusterAggregator,
            ClusterPartitionMembership partitionMembership,
            ClusterPartialForwarder partialForwarder,
            MetricWriteRouter metricWriteRouter,
            long lateFlushGraceMs) {
        this.clusterAggregator = Objects.requireNonNull(clusterAggregator);
        this.partitionMembership = Objects.requireNonNull(partitionMembership);
        this.partialForwarder = Objects.requireNonNull(partialForwarder);
        this.metricWriteRouter = Objects.requireNonNull(metricWriteRouter);
        this.lateFlushGraceMs = Math.max(0L, lateFlushGraceMs);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "trace-minute-metric");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.scheduleAtFixedRate(this::tickMinuteFlushSafe, 1L, 1L, TimeUnit.SECONDS);
        log.info(
                "Trace minute metric aggregator started stream={} lateFlushGraceMs={}",
                STREAM,
                this.lateFlushGraceMs);
    }

    public void accept(OptimizedMetric metric) {
        if (metric == null || !TraceMetricMinuteBucket.requiresMinuteAggregation(metric.measurement())) {
            return;
        }
        long windowStartMs = TraceMetricMinuteBucket.minuteBucketEpochMs(metric.timestamp());
        long windowEndMs = windowStartMs + TraceMetricMinuteBucket.BUCKET_MS;
        String partitionKey = TraceMetricMinuteBucket.aggregationPartitionKey(metric);
        OptimizedMetric bucketed = metric
                .withTimestamp(windowStartMs * 1_000_000L)
                .withTsId(TraceMetricMinuteBucket.aggregationTsId(metric, windowStartMs));

        Optional<String> forwardTarget = partitionMembership.forwardPartialTarget(partitionKey);
        if (forwardTarget.isPresent()) {
            byte[] serialized = OptimizedMetricUtil.serialize(bucketed);
            partialForwarder.forward(
                    forwardTarget.get(), STREAM, partitionKey, windowStartMs, windowEndMs, serialized);
            return;
        }
        if (!partitionMembership.owns(partitionKey)) {
            warnDropped(
                    "Cluster metric accept dropped stream={} reason=not-owner-no-forward windowMs={} partition={} owner={} metric={} {}",
                    STREAM,
                    windowStartMs,
                    ClusterAggregationLog.partitionKeyBrief(partitionKey),
                    ClusterAggregationLog.ownerBrief(partitionMembership, partitionKey),
                    ClusterAggregationLog.metricBrief(metric),
                    ClusterAggregationLog.membershipBrief(partitionMembership));
            return;
        }
        mergeLocal(windowStartMs, partitionKey, bucketed);
    }

    public void acceptForwarded(String partitionKey, long windowStartMs, long windowEndMs, byte[] partial) {
        if (partial == null || partial.length == 0) {
            warnDropped(
                    "Cluster metric forward-in dropped stream={} reason=empty-partial windowMs={} partition={} {}",
                    STREAM,
                    windowStartMs,
                    ClusterAggregationLog.partitionKeyBrief(partitionKey),
                    ClusterAggregationLog.membershipBrief(partitionMembership));
            return;
        }
        if (!partitionMembership.owns(partitionKey)) {
            OptimizedMetric preview = OptimizedMetricUtil.deserialize(partial);
            warnDropped(
                    "Cluster metric forward-in dropped stream={} reason=not-owner windowMs={} partition={} owner={} metric={} {}",
                    STREAM,
                    windowStartMs,
                    ClusterAggregationLog.partitionKeyBrief(partitionKey),
                    ClusterAggregationLog.ownerBrief(partitionMembership, partitionKey),
                    ClusterAggregationLog.metricBrief(preview),
                    ClusterAggregationLog.membershipBrief(partitionMembership));
            return;
        }
        OptimizedMetric incoming = OptimizedMetricUtil.deserialize(partial);
        mergeLocal(windowStartMs, partitionKey, incoming);
    }

    private static void warnDropped(String template, Object... args) {
        if (log.isDebugEnabled()) {
            log.debug(template, args);
        }
        long count = DROP_LIMITER.record();
        if (count > 0) {
            log.warn("Trace minute metric drops: {} in last 10s", count);
        }
    }

    /** Test / shutdown hook: flush a specific minute window immediately. */
    public synchronized int flushWindow(long windowStartMs) {
        return drainWindow(windowStartMs, true);
    }

    public synchronized void flushAllWindows() {
        List<Long> windows = localBuckets.keySet().stream()
                .map(this::windowStartFromStateKey)
                .distinct()
                .sorted()
                .toList();
        for (Long window : windows) {
            drainWindow(window, true);
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        flushAllWindows();
    }

    void runFlushTick() {
        tickMinuteFlushSafe();
    }

    private void tickMinuteFlushSafe() {
        try {
            tickMinuteFlush();
        } catch (Exception e) {
            log.warn("Trace minute metric flush tick failed: {}", e.getMessage(), e);
        }
    }

    private void tickMinuteFlush() {
        long nowMs = System.currentTimeMillis();
        long currentMinuteMs = TraceMetricMinuteBucket.currentMinuteEpochMs();
        List<Long> windowsToFlush;
        synchronized (this) {
            windowsToFlush = localBuckets.keySet().stream()
                    .map(this::windowStartFromStateKey)
                    .filter(window -> window < currentMinuteMs)
                    .filter(window -> shouldFlushClosedWindow(window, nowMs))
                    .distinct()
                    .sorted()
                    .toList();
        }
        for (long windowStartMs : windowsToFlush) {
            flushWindow(windowStartMs);
        }
    }

    private boolean shouldFlushClosedWindow(long windowStartMs, long nowMs) {
        if (!flushedWindows.contains(windowStartMs)) {
            return true;
        }
        Long deadline = lateFlushDeadlineMs.get(windowStartMs);
        return deadline != null && nowMs >= deadline;
    }

    private synchronized void mergeLocal(long windowStartMs, String partitionKey, OptimizedMetric metric) {
        localBuckets.merge(stateKey(windowStartMs, partitionKey), metric, OptimizedMetric::merge);
        scheduleLateFlushIfNeeded(windowStartMs);
    }

    private void scheduleLateFlushIfNeeded(long windowStartMs) {
        long currentMinuteMs = TraceMetricMinuteBucket.currentMinuteEpochMs();
        if (windowStartMs >= currentMinuteMs || !flushedWindows.contains(windowStartMs)) {
            return;
        }
        lateFlushDeadlineMs.put(windowStartMs, System.currentTimeMillis() + lateFlushGraceMs);
    }

    private synchronized int drainWindow(long windowStartMs, boolean force) {
        if (!force && !hasPendingBuckets(windowStartMs)) {
            return 0;
        }
        List<String> done = new ArrayList<>();
        int rows = 0;
        for (Map.Entry<String, OptimizedMetric> entry : localBuckets.entrySet()) {
            if (windowStartFromStateKey(entry.getKey()) != windowStartMs) {
                continue;
            }
            try {
                OptimizedMetric flushed = entry.getValue();
                metricWriteRouter.offer(flushed);
                rows++;
            } catch (Exception e) {
                log.warn("Failed to offer trace minute metric window={}: {}", windowStartMs, e.getMessage());
            }
            done.add(entry.getKey());
            String partitionKey = partitionKeyFromStateKey(entry.getKey());
        }
        done.forEach(localBuckets::remove);
        if (rows > 0) {
            flushedWindows.add(windowStartMs);
            lateFlushDeadlineMs.remove(windowStartMs);
        }
        return rows;
    }

    private boolean hasPendingBuckets(long windowStartMs) {
        for (String stateKey : localBuckets.keySet()) {
            if (windowStartFromStateKey(stateKey) == windowStartMs) {
                return true;
            }
        }
        return false;
    }

    private static String stateKey(long windowStartMs, String partitionKey) {
        return windowStartMs + "\u0002" + partitionKey;
    }

    private long windowStartFromStateKey(String stateKey) {
        int idx = stateKey.indexOf('\u0002');
        if (idx <= 0) {
            return 0L;
        }
        return Long.parseLong(stateKey.substring(0, idx));
    }

    private String partitionKeyFromStateKey(String stateKey) {
        int idx = stateKey.indexOf('\u0002');
        return idx < 0 ? stateKey : stateKey.substring(idx + 1);
    }
}
