package com.databuff.apm.ingest.component;

import com.databuff.apm.common.cluster.aggregate.ClusterPartialForwarder;
import com.databuff.apm.common.cluster.coordination.ClusterPartitionMembership;
import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.common.serde.DCSpanJsonDecoder;
import com.databuff.apm.common.serde.DCSpanJsonEncoder;
import com.databuff.apm.ingest.doris.DorisFlushScheduler;
import com.databuff.apm.ingest.event.TraceBatchEvent;
import com.databuff.apm.ingest.event.TraceEvent;
import com.databuff.apm.ingest.meta.IngestMetaCache;
import com.databuff.apm.ingest.meta.MetaServiceCollector;
import com.databuff.apm.ingest.meta.ServiceInstanceRegistry;
import com.databuff.apm.ingest.pipeline.component.AbstractComponent;
import com.databuff.apm.ingest.pipeline.pool.TaskPool;
import com.databuff.apm.ingest.pipeline.shard.HashShardingStrategy;
import com.databuff.apm.ingest.pipeline.task.AsyncTask;
import com.databuff.apm.ingest.trace.SpanResourceIgnoreFilter;
import com.databuff.apm.ingest.trace.TraceAssemblyBuffer;
import com.databuff.apm.ingest.trace.TraceEnrichProcessor;
import com.databuff.apm.ingest.trace.TraceFillProcessor;
import com.databuff.apm.ingest.support.LogRateLimiter;
import com.databuff.apm.ingest.trace.VirtualServiceExtractor;
import com.databuff.apm.ingest.trace.remote.RemoteCallProcessor;
import com.databuff.apm.common.storage.DorisBatchWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Trace 处理组件，核心流水线：
 * <pre>
 * Step 1  DcSpan（OtlpIngestService 已转换，内存对象）
 * Step 1b resource 忽略过滤（命中则后续 enrich/assemble/fill/metric/trace 均跳过）
 * Step 2  enrich（DTS 字段补全，内存原地修改）
 * Step 3  按 traceId 聚合（默认定时检测 2s，root 到达后连续 2 次无新 span 或连续 4 次无新 span 则 flush）
 *         · 单机 / owner：入 TraceAssemblyBuffer 等待收齐
 *         · 集群非 owner：序列化 → gRPC ForwardPartial → owner 反序列化再聚合
 * Step 4  fill 上下游 / serviceflow 关系
 * Step 5  提取 OptimizedMetric → AggregateComponent 聚合 → 指标存储
 * Step 6  fill 后 span 序列化 → dc_span trace 存储
 * </pre>
 */
public final class TraceComponent extends AbstractComponent<TraceComponent.TraceTask> {

    private static final Logger log = LoggerFactory.getLogger(TraceComponent.class);
    private static final LogRateLimiter TASK_FAILURE_LIMITER = new LogRateLimiter(10_000L);
    private static final LogRateLimiter IGNORE_LIMITER = new LogRateLimiter(10_000L);

    /** 集群 trace 转发 stream，partition key = traceId。 */
    public static final String TRACE_STREAM = "ingest.trace.span";

    private final AggregateComponent aggregateComponent;
    private final DorisBatchWriter traceWriter;
    private final IngestMetaCache metaCache;
    private final MetaServiceCollector metaServiceCollector;
    private final ServiceInstanceRegistry serviceInstanceRegistry;
    private final VirtualServiceExtractor virtualServiceExtractor;
    private final RemoteCallProcessor remoteCallProcessor;
    private final ClusterPartitionMembership partitionMembership;
    private final ClusterPartialForwarder partialForwarder;
    private final DorisFlushScheduler metricFlushScheduler;
    private final SpanResourceIgnoreFilter resourceIgnoreFilter;
    private final long assemblyCheckIntervalMs;
    private final int bufferSize;
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong ignored = new AtomicLong();

    public TraceComponent(
            AggregateComponent aggregateComponent,
            DorisBatchWriter traceWriter,
            IngestMetaCache metaCache,
            MetaServiceCollector metaServiceCollector,
            ServiceInstanceRegistry serviceInstanceRegistry,
            VirtualServiceExtractor virtualServiceExtractor,
            RemoteCallProcessor remoteCallProcessor,
            ClusterPartitionMembership partitionMembership,
            ClusterPartialForwarder partialForwarder,
            DorisFlushScheduler metricFlushScheduler,
            long assemblyCheckIntervalMs) {
        this(
                aggregateComponent,
                traceWriter,
                metaCache,
                metaServiceCollector,
                serviceInstanceRegistry,
                virtualServiceExtractor,
                remoteCallProcessor,
                partitionMembership,
                partialForwarder,
                metricFlushScheduler,
                SpanResourceIgnoreFilter.NOOP,
                assemblyCheckIntervalMs,
                1024);
    }

    public TraceComponent(
            AggregateComponent aggregateComponent,
            DorisBatchWriter traceWriter,
            IngestMetaCache metaCache,
            MetaServiceCollector metaServiceCollector,
            ServiceInstanceRegistry serviceInstanceRegistry,
            VirtualServiceExtractor virtualServiceExtractor,
            RemoteCallProcessor remoteCallProcessor,
            ClusterPartitionMembership partitionMembership,
            ClusterPartialForwarder partialForwarder,
            DorisFlushScheduler metricFlushScheduler,
            long assemblyCheckIntervalMs,
            int bufferSize) {
        this(
                aggregateComponent,
                traceWriter,
                metaCache,
                metaServiceCollector,
                serviceInstanceRegistry,
                virtualServiceExtractor,
                remoteCallProcessor,
                partitionMembership,
                partialForwarder,
                metricFlushScheduler,
                SpanResourceIgnoreFilter.NOOP,
                assemblyCheckIntervalMs,
                bufferSize);
    }

    public TraceComponent(
            AggregateComponent aggregateComponent,
            DorisBatchWriter traceWriter,
            IngestMetaCache metaCache,
            MetaServiceCollector metaServiceCollector,
            ServiceInstanceRegistry serviceInstanceRegistry,
            VirtualServiceExtractor virtualServiceExtractor,
            RemoteCallProcessor remoteCallProcessor,
            ClusterPartitionMembership partitionMembership,
            ClusterPartialForwarder partialForwarder,
            DorisFlushScheduler metricFlushScheduler,
            SpanResourceIgnoreFilter resourceIgnoreFilter,
            long assemblyCheckIntervalMs,
            int bufferSize) {
        this.aggregateComponent = aggregateComponent;
        this.traceWriter = traceWriter;
        this.metaCache = metaCache;
        this.metaServiceCollector = metaServiceCollector;
        this.serviceInstanceRegistry = serviceInstanceRegistry;
        this.virtualServiceExtractor = virtualServiceExtractor;
        this.remoteCallProcessor = remoteCallProcessor;
        this.partitionMembership = partitionMembership;
        this.partialForwarder = partialForwarder;
        this.metricFlushScheduler = metricFlushScheduler;
        this.resourceIgnoreFilter = resourceIgnoreFilter == null
                ? SpanResourceIgnoreFilter.NOOP
                : resourceIgnoreFilter;
        this.assemblyCheckIntervalMs = assemblyCheckIntervalMs;
        this.bufferSize = Math.max(16, bufferSize);
    }

    @Override
    protected String getName() {
        return "trace";
    }

    @Override
    protected TaskPool<TraceTask> generateTaskPool(int taskSize) {
        TraceTask[] tasks = new TraceTask[taskSize];
        for (int i = 0; i < taskSize; i++) {
            tasks[i] = new TraceTask(i);
        }
        return new TaskPool<>(new HashShardingStrategy(), tasks);
    }

    public long receivedCount() {
        return received.get();
    }

    public long ignoredCount() {
        return ignored.get();
    }

    /**
     * 集群 owner 节点接收 ForwardPartial（stream={@link #TRACE_STREAM}）。
     * 反序列化后跳过 enrich，直接进入 traceId 聚合。
     */
    public boolean acceptForwardedSpan(String traceId, byte[] spanBytes) {
        try {
            // Full Jackson decode: fast-path (ignoreMap=true) omits fields and breaks fill/metrics.
            DcSpan span = DCSpanJsonDecoder.decode(spanBytes, false);
            return emit(traceId, TraceEvent.forwarded(span));
        } catch (Exception e) {
            log.warn("Failed to decode forwarded trace span traceId={}: {}", shortTraceId(traceId), e.getMessage());
            return false;
        }
    }

    final class TraceTask extends AsyncTask {

        private final TraceAssemblyBuffer assemblyBuffer;
        private final TraceFillProcessor fillProcessor = new TraceFillProcessor(
                TraceComponent.this.virtualServiceExtractor,
                TraceComponent.this.remoteCallProcessor);
        private final TraceEnrichProcessor enrichProcessor = new TraceEnrichProcessor(
                TraceComponent.this.metaCache,
                TraceComponent.this.metaServiceCollector,
                TraceComponent.this.serviceInstanceRegistry,
                TraceComponent.this.remoteCallProcessor);

        TraceTask(int taskIndex) {
            super(bufferSize, taskIndex);
            this.assemblyBuffer = new TraceAssemblyBuffer(
                    TraceComponent.this.assemblyCheckIntervalMs,
                    (traceId, spans) -> {
                        try {
                            enqueueFlushTrace(traceId, spans);
                        } catch (Exception e) {
                            log.warn(
                                    "Trace assembly flush failed traceId={}: {}",
                                    shortTraceId(traceId),
                                    e.getMessage(),
                                    e);
                        }
                    },
                    "trace-assembly-" + taskIndex);
        }

        @Override
        protected void processEvent(Object key, Object event) {
            try {
                if (event instanceof TraceBatchEvent traceBatchEvent) {
                    received.addAndGet(traceBatchEvent.spans().size());
                    processTraceBatch(String.valueOf(key), traceBatchEvent.spans());
                    return;
                }
                if (event instanceof FlushTraceEvent flushTraceEvent) {
                    flushTrace(String.valueOf(key), flushTraceEvent.spans());
                    return;
                }
                if (event instanceof TraceEvent traceEvent) {
                    received.incrementAndGet();
                    processSpan(String.valueOf(key), traceEvent);
                }
            } catch (Exception e) {
                if (log.isDebugEnabled()) {
                    log.debug("Trace task failed key={} eventType={}: {}", key, eventType(event), e.getMessage(), e);
                }
                long count = TASK_FAILURE_LIMITER.record();
                if (count > 0) {
                    log.warn("Trace task failures: {} in last 10s; latest key={} eventType={}: {}",
                            count,
                            key,
                            eventType(event),
                            e.getMessage());
                }
            }
        }

        @Override
        public void onShutdown() {
            flushPending("shutdown");
            assemblyBuffer.close();
            super.onShutdown();
        }

        private void processSpan(String serviceKey, TraceEvent traceEvent) throws Exception {
            DcSpan span = traceEvent.span();
            if (span == null) {
                log.warn("Trace single span event ignored because span is null key={}", serviceKey);
                return;
            }
            // Step 1b：按 resource 忽略（精确 / 正则），命中则后续一律不处理
            if (TraceComponent.this.resourceIgnoreFilter.shouldIgnore(span)) {
                recordIgnored(span.resource);
                return;
            }
            // Step 2：DTS enrich（内存，无 serde）
            if (!traceEvent.skipEnrich()) {
                enrichProcessor.enrich(span);
            }
            // Step 3 · 集群：非 traceId owner 时，此处为 trace 链路唯一序列化点
            Optional<String> forwardTarget = partitionMembership.forwardPartialTarget(span.trace_id);
            if (forwardTarget.isPresent()) {
                TraceComponent.this.partialForwarder.forward(
                        forwardTarget.get(),
                        TRACE_STREAM,
                        span.trace_id,
                        0,
                        0,
                        DCSpanJsonEncoder.encode(span));
                return;
            }
            // Step 3 · 单机 / 集群 owner：内存按 traceId 聚合，定时检测后 flush
            assembleSpans(serviceKey, List.of(span));
        }

        private void processTraceBatch(String traceKey, List<DcSpan> spans) throws Exception {
            if (spans == null || spans.isEmpty()) {
                log.warn("Trace batch ignored because spans empty traceKey={}", shortTraceId(traceKey));
                return;
            }
            List<DcSpan> kept = new java.util.ArrayList<>(spans.size());
            for (DcSpan span : spans) {
                if (span == null) {
                    continue;
                }
                if (TraceComponent.this.resourceIgnoreFilter.shouldIgnore(span)) {
                    recordIgnored(span.resource);
                    continue;
                }
                enrichProcessor.enrich(span);
                kept.add(span);
            }
            if (kept.isEmpty()) {
                return;
            }
            Optional<String> forwardTarget = partitionMembership.forwardPartialTarget(traceKey);
            if (forwardTarget.isPresent()) {
                for (DcSpan span : kept) {
                    TraceComponent.this.partialForwarder.forward(
                            forwardTarget.get(),
                            TRACE_STREAM,
                            span.trace_id,
                            0,
                            0,
                            DCSpanJsonEncoder.encode(span));
                }
                return;
            }
            assembleSpans(traceKey, kept);
        }

        private void recordIgnored(String resource) {
            ignored.incrementAndGet();
            if (log.isDebugEnabled()) {
                log.debug("Ignored span by resource filter resource={}", resource);
            }
            long count = IGNORE_LIMITER.record();
            if (count > 0) {
                log.info("Ignored {} spans by resource filter in last 10s; latest resource={}", count, resource);
            }
        }

        private void assembleSpans(String serviceKey, List<DcSpan> spans) throws Exception {
            List<DcSpan> immediate = assemblyBuffer.offerAll(spans);
            for (DcSpan span : immediate) {
                flushTrace(serviceKey, List.of(span));
            }
        }

        private void enqueueFlushTrace(String traceId, List<DcSpan> spans) throws Exception {
            if (!handleEvent(traceId, new FlushTraceEvent(spans))) {
                flushTrace(traceId, spans);
            }
        }

        private void flushPending(String serviceKey) {
            for (List<DcSpan> traceBatch : assemblyBuffer.flushAll()) {
                try {
                    String traceKey = traceBatch.isEmpty() || traceBatch.get(0).trace_id == null
                            ? serviceKey
                            : traceBatch.get(0).trace_id;
                    flushTrace(traceKey, traceBatch);
                } catch (Exception e) {
                    log.warn("Trace pending flush failed key={}: {}", serviceKey, e.getMessage());
                }
            }
        }

        /** Step 4–6：fill → 指标聚合 → trace 存储。 */
        private void flushTrace(String serviceKey, List<DcSpan> spans) throws Exception {
            TraceFillProcessor.FillResult result = fillProcessor.processTrace(spans);
            // Step 5：提取 service / service.flow / service.http 等 → 指标聚合 → Doris
            aggregateComponent.acceptExtractedMetrics(serviceKey, result.metrics());
            // Step 6：fill 后挂 lazy 行，Stream Load flush 时才序列化写入 trace_dc_span
            traceWriter.offerAll(result.filledSpanRows());
        }
    }

    private static String eventType(Object event) {
        return event == null ? "null" : event.getClass().getSimpleName();
    }

    private static String shortTraceId(String traceId) {
        if (traceId == null || traceId.length() <= 12) {
            return String.valueOf(traceId);
        }
        return traceId.substring(0, 12) + "..." + traceId.substring(traceId.length() - 6);
    }

    private record FlushTraceEvent(List<DcSpan> spans) {
    }

}
