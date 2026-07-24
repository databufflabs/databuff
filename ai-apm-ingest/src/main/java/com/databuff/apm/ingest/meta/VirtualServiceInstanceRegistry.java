package com.databuff.apm.ingest.meta;

import com.databuff.apm.common.meta.ServiceTypeClassifier;
import com.databuff.apm.common.meta.VirtualServiceResolver;
import com.databuff.apm.common.metric.MetricSchemaRegistry;
import com.databuff.apm.common.model.OptimizedMetric;
import com.databuff.apm.common.util.ServiceKeyUtil;
import com.databuff.apm.ingest.metric.MetricWriteRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 虚拟服务实例心跳，与历史 portal {@code ComponentInstanceService} 行为一致。
 * <p>
 * 从出站组件 span 提取实例维度，定时刷写 {@code service.instance}（{@code virtualService=1}）。
 */
public final class VirtualServiceInstanceRegistry {

    private static final Logger log = LoggerFactory.getLogger(VirtualServiceInstanceRegistry.class);

    private final MetricWriteRouter metricWriteRouter;
    private final long flushIntervalMs;
    private final ConcurrentHashMap<String, CachedVirtualInstance> cache = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduler;

    public VirtualServiceInstanceRegistry(MetricWriteRouter metricWriteRouter, long flushIntervalMs) {
        this.metricWriteRouter = Objects.requireNonNull(metricWriteRouter);
        this.flushIntervalMs = Math.max(10_000L, flushIntervalMs);
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "virtual-service-instance-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleAtFixedRate(
                this::flushHeartbeatsSafe,
                flushIntervalMs,
                flushIntervalMs,
                TimeUnit.MILLISECONDS);
        log.info("Virtual service instance registry started flushIntervalMs={}", flushIntervalMs);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    public void remember(VirtualServiceResolver.ResolvedVirtualService resolved) {
        if (resolved == null
                || resolved.serviceId() == null
                || resolved.serviceId().isBlank()
                || resolved.serviceInstance() == null
                || resolved.serviceInstance().isBlank()) {
            return;
        }
        String key = cacheKey(resolved.serviceId(), resolved.serviceInstance());
        cache.compute(key, (ignored, existing) -> {
            if (existing == null) {
                return CachedVirtualInstance.from(resolved);
            }
            return existing.merge(resolved);
        });
    }

    void flushHeartbeatsSafe() {
        try {
            flushHeartbeats();
        } catch (Exception e) {
            log.warn("Virtual service instance heartbeat flush failed: {}", e.getMessage());
        }
    }

    public void flushHeartbeats() {
        if (cache.isEmpty()) {
            return;
        }
        long nowNanos = System.currentTimeMillis() * 1_000_000L;
        int count = 0;
        for (CachedVirtualInstance cached : cache.values()) {
            OptimizedMetric metric = new OptimizedMetric()
                    .withTimestamp(nowNanos)
                    .withMeasurement("service.instance")
                    .withTagValues(MetricSchemaRegistry.tagValuesFromMap("service.instance", cached.tags()))
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

    private record CachedVirtualInstance(
            String serviceId,
            String service,
            String serviceInstance,
            String hostname,
            String ports) {

        static CachedVirtualInstance from(VirtualServiceResolver.ResolvedVirtualService resolved) {
            return new CachedVirtualInstance(
                    resolved.serviceId(),
                    resolved.service(),
                    resolved.serviceInstance(),
                    resolved.hostname(),
                    resolved.ports());
        }

        CachedVirtualInstance merge(VirtualServiceResolver.ResolvedVirtualService resolved) {
            String host = hostname;
            if ((host == null || host.isBlank()) && resolved.hostname() != null && !resolved.hostname().isBlank()) {
                host = resolved.hostname();
            }
            String portValue = ports;
            if ((portValue == null || portValue.isBlank()) && resolved.ports() != null && !resolved.ports().isBlank()) {
                portValue = resolved.ports();
            }
            return new CachedVirtualInstance(
                    serviceId,
                    service,
                    serviceInstance,
                    host,
                    portValue);
        }

        Map<String, String> tags() {
            ServiceTypeClassifier.Classification classified = ServiceTypeClassifier.classify(service);
            Map<String, String> tags = new LinkedHashMap<>();
            tags.put("biz_pid_id", "");
            tags.put("containerId", "");
            tags.put("containerName", "");
            tags.put("hostIp", "");
            tags.put("hostname", nullToEmpty(hostname));
            tags.put("javaVendor", "");
            tags.put("javaVersion", "");
            tags.put("k8sClusterId", "");
            tags.put("k8sContainerId", "");
            tags.put("k8sNamespace", "");
            tags.put("k8sPodName", "");
            tags.put("pid", "");
            tags.put("pname", "");
            tags.put("ports", nullToEmpty(ports));
            tags.put("service", nullToEmpty(service));
            tags.put("service_id", normalizeServiceId(serviceId, service));
            tags.put("service_instance", nullToEmpty(serviceInstance));
            tags.put("service_type", classified.serviceType());
            tags.put("virtualService", "1");
            return tags;
        }

        private static String normalizeServiceId(String serviceId, String serviceName) {
            if (serviceId != null && !serviceId.isBlank()) {
                return serviceId.trim();
            }
            return ServiceKeyUtil.of(serviceName);
        }

        private static String nullToEmpty(String value) {
            return value == null ? "" : value;
        }
    }
}
