package com.databuff.apm.ingest.meta;

import com.databuff.apm.common.metric.MetricSchemaRegistry;
import com.databuff.apm.common.meta.OtelAttributeMaps;
import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.common.model.OptimizedMetric;
import com.databuff.apm.common.serde.DcSpanUtil;
import com.databuff.apm.ingest.metric.MetricWriteRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 对齐商业 {@code InstanceFromDataService}：OTLP/DataHub trace 接入时先把实例维度写入内存，
 * 再按固定周期刷出 {@code service.instance} 心跳指标（{@code metricsVal=1}），刷完清空缓存。
 */
public final class ServiceInstanceRegistry {

    private static final Logger log = LoggerFactory.getLogger(ServiceInstanceRegistry.class);

    private final MetricWriteRouter metricWriteRouter;
    private final long flushIntervalMs;
    private final ConcurrentHashMap<String, CachedInstance> cache = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduler;

    public ServiceInstanceRegistry(MetricWriteRouter metricWriteRouter, long flushIntervalMs) {
        this.metricWriteRouter = Objects.requireNonNull(metricWriteRouter);
        this.flushIntervalMs = Math.max(10_000L, flushIntervalMs);
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "service-instance-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleAtFixedRate(
                this::flushHeartbeatsSafe,
                flushIntervalMs,
                flushIntervalMs,
                TimeUnit.MILLISECONDS);
        log.info("Service instance registry started flushIntervalMs={}", flushIntervalMs);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /** 从 trace span 登记实例（对应商业 {@code initServiceInstanceEntity}）。 */
    public void remember(DcSpan span) {
        if (span == null || span.serviceId == null || span.serviceId.isBlank()) {
            return;
        }
        String instance = firstNonBlank(span.serviceInstance, span.hostName);
        if (instance == null || instance.isBlank()) {
            return;
        }
        String key = cacheKey(span.serviceId, instance);
        cache.compute(key, (ignored, existing) -> {
            if (existing == null) {
                return CachedInstance.from(span, instance);
            }
            return existing.merge(span, instance);
        });
    }

    void flushHeartbeatsSafe() {
        try {
            flushHeartbeats();
        } catch (Exception e) {
            log.warn("Service instance heartbeat flush failed: {}", e.getMessage());
        }
    }

    public void flushHeartbeats() {
        if (cache.isEmpty()) {
            return;
        }
        long nowNanos = System.currentTimeMillis() * 1_000_000L;
        int count = 0;
        for (CachedInstance cached : cache.values()) {
            DcSpan span = cached.toSpan();
            Map<String, String> meta = OtelAttributeMaps.parse(span);
            Map<String, String> tags = DcSpanUtil.serviceInstanceTags(span, cached.serviceInstance(), meta);
            OptimizedMetric metric = new OptimizedMetric()
                    .withTimestamp(nowNanos)
                    .withMeasurement("service.instance")
                    .withTagValues(MetricSchemaRegistry.tagValuesFromMap("service.instance", tags))
                    .withFieldValues(1L)
                    .initTsId();
            metricWriteRouter.offer(metric);
            count++;
        }
        cache.clear();
    }

    int cachedSize() {
        return cache.size();
    }

    private static String cacheKey(String serviceId, String serviceInstance) {
        return serviceId + ":" + serviceInstance;
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }

    private record CachedInstance(
            String serviceId,
            String service,
            String serviceInstance,
            String hostName,
            String metaJson) {

        static CachedInstance from(DcSpan span, String serviceInstance) {
            return new CachedInstance(
                    span.serviceId,
                    span.service,
                    serviceInstance,
                    span.hostName,
                    captureMetaJson(span));
        }

        CachedInstance merge(DcSpan span, String instance) {
            String host = hostName;
            if ((host == null || host.isBlank()) && span.hostName != null && !span.hostName.isBlank()) {
                host = span.hostName;
            }
            // Pipeline spans keep attributes in metaMap; span.meta is often null until encode.
            String meta = metaJson;
            if (meta == null || meta.isBlank()) {
                meta = captureMetaJson(span);
            }
            return new CachedInstance(
                    serviceId,
                    firstNonBlank(span.service, service),
                    instance,
                    host,
                    meta);
        }

        /** Prefer working metaMap (ingest hot path); fall back to materialized meta JSON. */
        private static String captureMetaJson(DcSpan span) {
            if (span == null) {
                return null;
            }
            if (span.metaMap != null && !span.metaMap.isEmpty()) {
                return OtelAttributeMaps.encode(span.metaMap);
            }
            if (span.meta != null && !span.meta.isBlank()) {
                return span.meta;
            }
            return null;
        }

        DcSpan toSpan() {
            DcSpan span = new DcSpan();
            span.serviceId = serviceId;
            span.service = service;
            span.serviceInstance = serviceInstance;
            span.hostName = hostName;
            span.meta = metaJson;
            return span;
        }

        private static String firstNonBlank(String primary, String fallback) {
            if (primary != null && !primary.isBlank()) {
                return primary;
            }
            return fallback != null ? fallback : "";
        }
    }
}
