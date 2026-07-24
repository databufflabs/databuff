package com.databuff.apm.common.serde;

import com.databuff.apm.common.trace.TraceSpanNames;
import com.databuff.apm.common.flow.ServiceFlowSpanRules;
import com.databuff.apm.common.metric.DurationRangeUtil;
import com.databuff.apm.common.metric.MetricSchemaRegistry;
import com.databuff.apm.common.metric.TraceMetricMinuteBucket;
import com.databuff.apm.common.meta.OtelAttributeMaps;
import com.databuff.apm.common.meta.ServiceTypeClassifier;
import com.databuff.apm.common.meta.SpanDirectionUtil;
import com.databuff.apm.common.util.ServiceKeyUtil;
import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.common.model.OptimizedMetric;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 从 fill 后的 {@link DcSpan} 提取 {@link OptimizedMetric}，供指标聚合流水线消费。
 */
public final class DcSpanUtil {

    private static final Pattern SERVICE_KEY = Pattern.compile("^[0-9a-f]{16}$");

    private DcSpanUtil() {
    }

    public static List<OptimizedMetric> parseSpanData(DcSpan span) {
        List<OptimizedMetric> metrics = new ArrayList<>(8);
        OptimizedMetric service = serviceMetric(span);
        if (service != null) {
            metrics.add(service);
        }
        OptimizedMetric virtualEntry = virtualServiceEntryMetric(span);
        if (virtualEntry != null) {
            metrics.add(virtualEntry);
        }
        OptimizedMetric trace = serviceTraceMetric(span);
        if (trace != null) {
            metrics.add(trace);
        }
        OptimizedMetric http = serviceHttpMetric(span);
        if (http != null) {
            metrics.add(http);
        }
        OptimizedMetric rpc = serviceRpcMetric(span);
        if (rpc != null) {
            metrics.add(rpc);
        }
        OptimizedMetric remote = serviceRemoteMetric(span);
        if (remote != null) {
            metrics.add(remote);
        }
        OptimizedMetric db = serviceDbMetric(span);
        if (db != null) {
            metrics.add(db);
        }
        OptimizedMetric redis = serviceRedisMetric(span);
        if (redis != null) {
            metrics.add(redis);
        }
        OptimizedMetric mq = serviceMqMetric(span);
        if (mq != null) {
            metrics.add(mq);
        }
        OptimizedMetric config = serviceConfigMetric(span);
        if (config != null) {
            metrics.add(config);
        }
        OptimizedMetric exception = serviceExceptionMetric(span);
        if (exception != null) {
            metrics.add(exception);
        }
        OptimizedMetric virtualException = virtualComponentExceptionMetric(span);
        if (virtualException != null) {
            metrics.add(virtualException);
        }
        return metrics;
    }

    /**
     * Whether this span should write {@code metric_service} as a service request.
     * Only inbound entry spans ({@code isIn=1}) qualify; root / outbound-only spans do not.
     */
    public static boolean isServiceEntrySpan(DcSpan span) {
        if (span == null) {
            return false;
        }
        if (isVirtualInboundComponent(span)) {
            return false;
        }
        if (isDbSpan(span) || isRedisSpan(span) || isMqSpan(span)) {
            return false;
        }
        return span.isIn == 1;
    }

    /** 虚拟组件入口指标：历史 portal 出站处理器将 span 归属到虚拟服务并标记 isIn=1。 */
    static OptimizedMetric virtualServiceEntryMetric(DcSpan span) {
        if (!isVirtualInboundComponent(span)) {
            return null;
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("errorType", span.error > 0 ? "error" : "ok");
        tags.put("service", nullToEmpty(span.dstService));
        tags.put("service_id", normalizeMetricServiceId(span.dstServiceId, span.dstService));
        tags.put("service_instance", nullToEmpty(span.dstServiceInstance));
        long error = span.error > 0 ? 1L : 0L;
        return minuteAggregatedMetric("service", span, tags, 1L, error, span.duration, span.duration);
    }

    static boolean isVirtualInboundComponent(DcSpan span) {
        if (span == null || span.isOut != 1 || span.isIn != 1) {
            return false;
        }
        return hasVirtualDestination(span);
    }

    static boolean hasVirtualDestination(DcSpan span) {
        return span.dstService != null
                && !span.dstService.isBlank()
                && span.dstService.startsWith("[");
    }

    static boolean isServiceHttpEntrySpan(DcSpan span) {
        return isServiceEntrySpan(span) || (span.isOut == 1 && isHttpSpan(span));
    }

    static OptimizedMetric serviceMetric(DcSpan span) {
        if (!isServiceEntrySpan(span)) {
            return null;
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("errorType", span.error > 0 ? "error" : "ok");
        tags.put("service", nullToEmpty(span.service));
        tags.put("service_id", nullToEmpty(span.serviceId));
        tags.put("service_instance", nullToEmpty(span.serviceInstance));
        return minuteAggregatedMetric("service", span, tags);
    }

    /** 构建 {@code service.instance} 指标 tag 列（由 {@code ServiceInstanceRegistry} 定时刷写）。 */
    public static Map<String, String> serviceInstanceTags(
            DcSpan span, String serviceInstance, Map<String, String> meta) {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("biz_pid_id", nullToEmpty(OtelAttributeMaps.firstNonBlank(meta, "biz.pid.id")));
        tags.put("containerId", nullToEmpty(OtelAttributeMaps.firstNonBlank(meta, "container.id")));
        tags.put("containerName", nullToEmpty(OtelAttributeMaps.firstNonBlank(meta, "container.name")));
        tags.put("hostIp", nullToEmpty(OtelAttributeMaps.firstNonBlank(meta, "host.ip", "net.host.ip")));
        tags.put("hostname", nullToEmpty(firstNonBlank(span.hostName,
                OtelAttributeMaps.firstNonBlank(meta, "host.name"))));
        tags.put("javaVendor", nullToEmpty(OtelAttributeMaps.firstNonBlank(meta, "process.runtime.vendor")));
        tags.put("javaVersion", nullToEmpty(OtelAttributeMaps.firstNonBlank(meta, "process.runtime.version")));
        tags.put("k8sClusterId", nullToEmpty(OtelAttributeMaps.firstNonBlank(meta, "k8s.cluster.name", "k8s.cluster.uid")));
        tags.put("k8sContainerId", nullToEmpty(OtelAttributeMaps.firstNonBlank(meta, "k8s.container.id")));
        tags.put("k8sNamespace", nullToEmpty(OtelAttributeMaps.firstNonBlank(meta, "k8s.namespace.name")));
        tags.put("k8sPodName", nullToEmpty(OtelAttributeMaps.firstNonBlank(meta, "k8s.pod.name")));
        tags.put("pid", nullToEmpty(OtelAttributeMaps.firstNonBlank(meta, "process.pid")));
        tags.put("pname", nullToEmpty(OtelAttributeMaps.firstNonBlank(meta, "process.executable.name")));
        tags.put("ports", nullToEmpty(OtelAttributeMaps.firstNonBlank(meta, "server.port", "net.host.port")));
        tags.put("service", nullToEmpty(span.service));
        tags.put("service_id", normalizeMetricServiceId(span.serviceId, span.service));
        tags.put("service_instance", nullToEmpty(serviceInstance));
        tags.put("service_type", ServiceTypeClassifier.classify(span.serviceId).serviceType());
        tags.put("virtualService", "0");
        return tags;
    }

    /** Trace overview ({@code parentId=0}) aggregates spans with blank/zero {@code parent_id} ({@code is_parent=1}). */
    static OptimizedMetric serviceTraceMetric(DcSpan span) {
        if (span.is_parent != 1) {
            return null;
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("errorType", span.error > 0 ? "error" : "ok");
        tags.put("hostName", nullToEmpty(span.hostName));
        tags.put("httpMethod", nullToEmpty(span.metaHttpMethod));
        tags.put("httpStatusCode", span.metaHttpStatusCode == null ? "" : String.valueOf(span.metaHttpStatusCode));
        tags.put("resource", nullToEmpty(span.resource));
        tags.put("service", nullToEmpty(span.service));
        tags.put("service_id", nullToEmpty(span.serviceId));
        tags.put("service_instance", nullToEmpty(span.serviceInstance));
        return minuteAggregatedMetric("service.trace", span, tags);
    }

    static OptimizedMetric serviceHttpMetric(DcSpan span) {
        if (!isHttpSpan(span)) {
            return null;
        }
        SpanDirectionUtil.Direction direction = SpanDirectionUtil.resolve(span);
        if (direction.isIn() == 0 && direction.isOut() == 0) {
            return null;
        }
        Map<String, String> tags = componentBaseTags(span);
        tags.put("httpCode", span.metaHttpStatusCode == null ? "" : String.valueOf(span.metaHttpStatusCode));
        tags.put("httpMethod", nullToEmpty(span.metaHttpMethod));
        tags.put("url", normalizeHttpUrl(span.metaHttpUrl != null && !span.metaHttpUrl.isBlank()
                ? span.metaHttpUrl
                : nullToEmpty(span.resource)));
        return minuteAggregatedMetric("service.http", span, tags);
    }

    static OptimizedMetric serviceRpcMetric(DcSpan span) {
        if (!isRpcSpan(span)) {
            return null;
        }
        Map<String, String> meta = OtelAttributeMaps.parse(span);
        Map<String, String> tags = componentBaseTags(span);
        tags.put("statusCode", nullToEmpty(OtelAttributeMaps.firstNonBlank(
                meta, "rpc.grpc.status_code", "grpc.status_code", "status.code")));
        tags.put("type", nullToEmpty(resolveRpcSystem(meta, span)));
        return minuteAggregatedMetric("service.rpc", span, tags);
    }

    /** External HTTP/RPC virtual calls; legacy portal compatibility ({@code RemoteCallComponentMatcher}). */
    static OptimizedMetric serviceRemoteMetric(DcSpan span) {
        if (!isRemoteOutboundSpan(span)) {
            return null;
        }
        if (isInternalRemoteLinked(span)) {
            return null;
        }
        Map<String, String> meta = OtelAttributeMaps.parse(span);
        Map<String, String> tags = componentBaseTags(span);
        tags.put("remoteType", resolveRemoteType(span, meta));
        return minuteAggregatedMetric("service.remote", span, tags);
    }

    static boolean isRemoteOutboundSpan(DcSpan span) {
        if (span == null || !hasVirtualDestination(span)) {
            return false;
        }
        if (isDbSpan(span) || isRedisSpan(span) || isMqSpan(span) || isConfigSpan(span)) {
            return false;
        }
        Map<String, String> meta = OtelAttributeMaps.parse(span);
        if (!"true".equalsIgnoreCase(meta.get("remote"))) {
            return false;
        }
        return isHttpSpan(span) || isRpcSpan(span);
    }

    /** Drop remote metric once span is linked to a real internal peer (legacy portal: both src and dst set). */
    static boolean isInternalRemoteLinked(DcSpan span) {
        if (span.srcServiceId == null || span.srcServiceId.isBlank()) {
            return false;
        }
        if (span.dstServiceId == null || span.dstServiceId.isBlank()) {
            return false;
        }
        return !hasVirtualDestination(span);
    }

    static String resolveRemoteType(DcSpan span, Map<String, String> meta) {
        if (isRpcSpan(span)) {
            String rpcSystem = resolveRpcSystem(meta, span);
            return rpcSystem != null ? rpcSystem.trim().toLowerCase(Locale.ROOT) : "unknown";
        }
        if (isHttpSpan(span)) {
            return "http";
        }
        return "unknown";
    }

    static OptimizedMetric serviceDbMetric(DcSpan span) {
        if (!isDbSpan(span)) {
            return null;
        }
        Map<String, String> meta = OtelAttributeMaps.parse(span);
        Map<String, String> tags = dbComponentTags(span, meta);
        if (isEsSpan(span)) {
            tags.put("dbType", "elasticsearch");
            tags.put("sqlDatabase", nullToEmpty(OtelAttributeMaps.firstNonBlank(
                    meta, "db.elasticsearch.index", "elasticsearch.index", "db.name")));
            tags.put("sqlOperation", normalizeSqlOperation(OtelAttributeMaps.firstNonBlank(
                    meta, "http.method", "db.operation")));
            String url = OtelAttributeMaps.firstNonBlank(meta, "http.url", "url.full");
            String normalizedUrl = url != null ? normalizeHttpUrl(url) : nullToEmpty(span.resource);
            tags.put("sqlContent", normalizedUrl);
            tags.put("resource", normalizedUrl);
        } else {
            tags.put("dbType", nullToEmpty(OtelAttributeMaps.firstNonBlank(meta, "db.system", "db.type")));
            tags.put("sqlDatabase", nullToEmpty(OtelAttributeMaps.firstNonBlank(meta, "db.name")));
            tags.put("sqlOperation", normalizeSqlOperation(OtelAttributeMaps.firstNonBlank(meta, "db.operation")));
            tags.put("sqlContent", dbResource(span, meta));
        }
        tags.put("isSlow", span.slow > 0 ? "1" : "0");
        return minuteAggregatedMetric("service.db", span, tags);
    }

    static OptimizedMetric serviceRedisMetric(DcSpan span) {
        if (!isRedisSpan(span)) {
            return null;
        }
        Map<String, String> meta = OtelAttributeMaps.parse(span);
        Map<String, String> tags = componentBaseTags(span);
        tags.put("command", nullToEmpty(OtelAttributeMaps.firstNonBlank(meta, "db.statement", "redis.command")));
        return minuteAggregatedMetric("service.redis", span, tags);
    }

    static OptimizedMetric serviceMqMetric(DcSpan span) {
        if (!isMqSpan(span)) {
            return null;
        }
        Map<String, String> meta = OtelAttributeMaps.parse(span);
        Map<String, String> tags = componentBaseTags(span);
        tags.put("broker", nullToEmpty(OtelAttributeMaps.firstNonBlank(
                meta, "net.peer.name", "server.address", "messaging.kafka.broker")));
        tags.put("group", nullToEmpty(OtelAttributeMaps.firstNonBlank(
                meta, "messaging.kafka.consumer.group", "messaging.consumer.group")));
        tags.put("topic", nullToEmpty(OtelAttributeMaps.firstNonBlank(
                meta, "messaging.destination.name", "messaging.kafka.destination")));
        tags.put("partition", nullToEmpty(OtelAttributeMaps.firstNonBlank(meta, "messaging.kafka.partition")));
        tags.put("type", nullToEmpty(OtelAttributeMaps.firstNonBlank(meta, "messaging.system")));
        tags.put("isConsume", isMqConsume(meta) ? "1" : "0");
        return minuteAggregatedMetric("service.mq", span, tags);
    }

    static OptimizedMetric serviceConfigMetric(DcSpan span) {
        if (!isConfigSpan(span)) {
            return null;
        }
        Map<String, String> meta = OtelAttributeMaps.parse(span);
        Map<String, String> tags = componentBaseTags(span);
        tags.put("config.type", resolveConfigType(meta));
        tags.put("operation", nullToEmpty(OtelAttributeMaps.firstNonBlank(
                meta, "config.operation", "db.operation", "operation.name")));
        return minuteAggregatedMetric("service.config", span, tags);
    }

    static OptimizedMetric serviceExceptionMetric(DcSpan span) {
        if (span.error <= 0 || !isServiceEntrySpan(span)) {
            return null;
        }
        Map<String, String> tags = exceptionMetricTags(span);
        return minuteAggregatedMetric("service.exception", span, tags, 1L);
    }

    /** 虚拟组件入口异常：归属组件服务本身，与 {@link #serviceExceptionMetric} 独立，不污染 Web 入口口径。 */
    static OptimizedMetric virtualComponentExceptionMetric(DcSpan span) {
        if (span.error <= 0 || !isVirtualInboundComponent(span)) {
            return null;
        }
        return minuteAggregatedMetric(
                "service.exception", span, virtualInboundComponentExceptionTags(span), 1L);
    }

    static OptimizedMetric serviceFlowMetric(DcSpan span) {
        if (isDbSpan(span) || isRedisSpan(span) || isMqSpan(span)) {
            return null;
        }
        if (span.srcService == null || span.dstService == null
                || span.srcService.isBlank() || span.dstService.isBlank()
                || span.srcService.equals(span.dstService)) {
            return null;
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("entryInterfacePathId", "");
        tags.put("entryPathId", "");
        tags.put("interfacePathId", "");
        tags.put("isIn", String.valueOf(span.isIn));
        tags.put("parentInterfacePathId", "");
        tags.put("parentPathId", "");
        tags.put("parentResource", "");
        tags.put("parentService", nullToEmpty(span.srcService));
        tags.put("parentServiceId", nullToEmpty(span.srcServiceId));
        tags.put("pathId", "");
        tags.put("resource", nullToEmpty(span.resource));
        tags.put("service", nullToEmpty(span.dstService));
        tags.put("service_id", nullToEmpty(span.dstServiceId));
        return minuteAggregatedMetric("service.flow", span, tags);
    }

    public static boolean isHttpSpan(DcSpan span) {
        return analyze(span).http;
    }

    public static boolean isRpcSpan(DcSpan span) {
        return analyze(span).rpc;
    }

    public static boolean isDbSpan(DcSpan span) {
        return analyze(span).db;
    }

    public static boolean isRedisSpan(DcSpan span) {
        return analyze(span).redis;
    }

    public static boolean isMqSpan(DcSpan span) {
        return analyze(span).mq;
    }

    public static boolean isEsSpan(DcSpan span) {
        return analyze(span).es;
    }

    public static boolean isConfigSpan(DcSpan span) {
        return analyze(span).config;
    }

    public static boolean hasDbSystem(DcSpan span) {
        return analyze(span).hasDbSystem;
    }

    /** Portal trace tree legend categories: web, db, cache, mq, custom. */
    public record PortalSpanDisplay(String serviceType, String typeIcon) {
    }

    public static PortalSpanDisplay resolvePortalSpanDisplay(DcSpan span) {
        String serviceType = resolvePortalServiceType(span);
        return new PortalSpanDisplay(serviceType, resolvePortalTypeIcon(span, serviceType));
    }

    public static String resolvePortalServiceType(DcSpan span) {
        if (isRedisSpan(span)) {
            return "cache";
        }
        if (isDbSpan(span) || isEsSpan(span)) {
            return "db";
        }
        if (isMqSpan(span)) {
            return "mq";
        }
        if (isRpcSpan(span) || isConfigSpan(span)) {
            return "custom";
        }
        return "web";
    }

    private static String resolvePortalTypeIcon(DcSpan span, String serviceType) {
        Map<String, String> meta = OtelAttributeMaps.parse(span);
        return switch (serviceType) {
            case "cache" -> "redis";
            case "db" -> resolveDbTypeIcon(meta);
            case "mq" -> resolveMqTypeIcon(meta);
            case "custom" -> resolveCustomTypeIcon(meta, span);
            default -> "web";
        };
    }

    private static String resolveDbTypeIcon(Map<String, String> meta) {
        String dbSystem = OtelAttributeMaps.firstNonBlank(meta, "db.system", "db.type");
        if (dbSystem == null || dbSystem.isBlank()) {
            return "mysql";
        }
        String lower = dbSystem.toLowerCase(Locale.ROOT);
        if (lower.contains("elastic")) {
            return "elasticsearch";
        }
        if (lower.contains("postgres")) {
            return "postgres";
        }
        if (lower.contains("mongo")) {
            return "mongo";
        }
        if (lower.contains("redis")) {
            return "redis";
        }
        if (lower.contains("mysql") || lower.contains("mariadb")) {
            return "mysql";
        }
        return lower;
    }

    private static String resolveMqTypeIcon(Map<String, String> meta) {
        String messagingSystem = OtelAttributeMaps.firstNonBlank(meta, "messaging.system");
        if (messagingSystem == null || messagingSystem.isBlank()) {
            return "kafka";
        }
        String lower = messagingSystem.toLowerCase(Locale.ROOT);
        if (lower.contains("kafka")) {
            return "kafka";
        }
        if (lower.contains("rabbit")) {
            return "rabbitmq";
        }
        if (lower.contains("rocket")) {
            return "rocketmq";
        }
        return lower;
    }

    private static String resolveCustomTypeIcon(Map<String, String> meta, DcSpan span) {
        String rpcSystem = resolveRpcSystem(meta, span);
        if (rpcSystem != null && !rpcSystem.isBlank()) {
            return rpcSystem.toLowerCase(Locale.ROOT);
        }
        String configType = resolveConfigType(meta);
        if (configType != null && !configType.isBlank()) {
            return configType.toLowerCase(Locale.ROOT);
        }
        String serviceId = firstNonBlank(span.dstServiceId, span.serviceId);
        if (serviceId != null && !serviceId.isBlank()) {
            return ServiceTypeClassifier.classify(serviceId).type();
        }
        return "custom";
    }

    private static boolean isMqConsume(Map<String, String> meta) {
        String operation = OtelAttributeMaps.firstNonBlank(meta, "messaging.operation");
        if (operation == null) {
            return false;
        }
        String lower = operation.toLowerCase();
        return lower.contains("receive") || lower.contains("process");
    }

    private static String resolveConfigType(Map<String, String> meta) {
        String explicit = OtelAttributeMaps.firstNonBlank(meta, "config.type");
        if (explicit != null) {
            return explicit;
        }
        String db = OtelAttributeMaps.firstNonBlank(meta, "db.system", "db.type");
        return nullToEmpty(db);
    }

    private static boolean isRedisDbSystem(String system) {
        return system.toLowerCase().contains("redis");
    }

    private static boolean isConfigDbSystem(String lower) {
        return lower.contains("nacos")
                || lower.contains("apollo")
                || lower.contains("zookeeper")
                || lower.contains("consul")
                || lower.contains("etcd")
                || lower.contains("config");
    }

    private static SpanAnalysis analyze(DcSpan span) {
        if (span == null) {
            return SpanAnalysis.EMPTY;
        }
        if (span.analysisCache instanceof SpanAnalysis analysis && analysis.matches(span)) {
            return analysis;
        }
        SpanAnalysis analysis = SpanAnalysis.from(span, OtelAttributeMaps.parse(span));
        span.analysisCache = analysis;
        return analysis;
    }

    public static String resolveRpcSystem(Map<String, String> meta, DcSpan span) {
        String operation = span != null ? firstNonBlank(span.name, span.resource) : null;
        return resolveRpcSystem(meta, operation);
    }

    /**
     * Resolve RPC framework from OTel semconv or DataBuff Java agent tags
     * ({@code component}, {@code dubbo-version}, operation names like {@code dubbo.call}).
     */
    public static String resolveRpcSystem(Map<String, String> meta, String operationName) {
        String rpcSystem = OtelAttributeMaps.firstNonBlank(meta, "rpc.system");
        if (rpcSystem != null && !rpcSystem.isBlank()) {
            return rpcSystem.trim();
        }
        if (OtelAttributeMaps.firstNonBlank(meta, "dubbo-version") != null) {
            return "dubbo";
        }
        String component = OtelAttributeMaps.firstNonBlank(
                meta, "component", "spanComponent", "newComponentName");
        String componentRpc = rpcSystemFromComponent(component);
        if (componentRpc != null) {
            return componentRpc;
        }
        String urlRpc = rpcSystemFromUrl(OtelAttributeMaps.firstNonBlank(meta, "url", "short_url"));
        if (urlRpc != null) {
            return urlRpc;
        }
        String skyWalkingRpc = rpcSystemFromSkyWalkingMeta(meta);
        if (skyWalkingRpc != null) {
            return skyWalkingRpc;
        }
        return rpcSystemFromOperation(operationName);
    }

    /** {@code dubbo://}, {@code grpc://} and other RPC scheme URLs must not count as HTTP. */
    public static boolean isRpcProtocolUrl(String url) {
        return rpcSystemFromUrl(url) != null;
    }

    private static String rpcSystemFromUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        int schemeEnd = url.indexOf("://");
        if (schemeEnd <= 0) {
            return null;
        }
        String protocol = url.substring(0, schemeEnd).trim().toLowerCase(Locale.ROOT);
        return switch (protocol) {
            case "dubbo" -> "dubbo";
            case "grpc" -> "grpc";
            case "motan" -> "motan";
            case "thrift" -> "thrift";
            case "brpc" -> "brpc";
            default -> null;
        };
    }

    private static String rpcSystemFromSkyWalkingMeta(Map<String, String> meta) {
        String componentId = OtelAttributeMaps.firstNonBlank(meta, "skywalking.componentId");
        if (componentId != null) {
            try {
                String fromComponentId = rpcSystemFromSkyWalkingComponentId(Integer.parseInt(componentId.trim()));
                if (fromComponentId != null) {
                    return fromComponentId;
                }
            } catch (NumberFormatException ignored) {
                // ignore malformed component id
            }
        }
        String spanLayer = OtelAttributeMaps.firstNonBlank(meta, "skywalking.spanLayer");
        if (spanLayer != null && "RPCFramework".equalsIgnoreCase(spanLayer.trim())) {
            return "rpc";
        }
        return null;
    }

    public static String rpcSystemFromSkyWalkingComponentId(int componentId) {
        return switch (componentId) {
            case 3 -> "dubbo";
            case 8 -> "motan";
            case 11 -> "feign";
            case 23 -> "grpc";
            case 28 -> "servicecomb";
            case 43 -> "sofarpc";
            case 85 -> "finagle";
            case 91 -> "brpc";
            case 100, 101 -> "thrift";
            case 107 -> "jsonrpc";
            default -> null;
        };
    }

    private static String rpcSystemFromComponent(String component) {
        if (component == null || component.isBlank()) {
            return null;
        }
        String lower = component.toLowerCase(Locale.ROOT);
        if (lower.contains("dubbo")) {
            return "dubbo";
        }
        if (lower.contains("grpc")) {
            return "grpc";
        }
        if (lower.contains("sofarpc") || lower.contains("sofa-rpc")) {
            return "sofarpc";
        }
        if (lower.contains("feign")) {
            return "feign";
        }
        if (lower.contains("thrift")) {
            return "thrift";
        }
        return null;
    }

    private static String rpcSystemFromOperation(String operation) {
        if (operation == null || operation.isBlank()) {
            return null;
        }
        String lower = operation.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith("dubbo.") || lower.startsWith("dubbo ")) {
            return "dubbo";
        }
        if (lower.startsWith("grpc.") || lower.startsWith("grpc ")) {
            return "grpc";
        }
        if (lower.startsWith("sofarpc.") || lower.startsWith("sofarpc ")) {
            return "sofarpc";
        }
        return null;
    }

    private static final class SpanAnalysis {
        private static final SpanAnalysis EMPTY = new SpanAnalysis(
                0, null, null, null, false, false, false, false, false, false, false, false);

        private final int metaRevision;
        private final String httpMethod;
        private final String httpUrl;
        private final Integer httpStatusCode;
        private final boolean http;
        private final boolean rpc;
        private final boolean db;
        private final boolean redis;
        private final boolean mq;
        private final boolean es;
        private final boolean config;
        private final boolean hasDbSystem;

        private SpanAnalysis(
                int metaRevision,
                String httpMethod,
                String httpUrl,
                Integer httpStatusCode,
                boolean http,
                boolean rpc,
                boolean db,
                boolean redis,
                boolean mq,
                boolean es,
                boolean config,
                boolean hasDbSystem) {
            this.metaRevision = metaRevision;
            this.httpMethod = httpMethod;
            this.httpUrl = httpUrl;
            this.httpStatusCode = httpStatusCode;
            this.http = http;
            this.rpc = rpc;
            this.db = db;
            this.redis = redis;
            this.mq = mq;
            this.es = es;
            this.config = config;
            this.hasDbSystem = hasDbSystem;
        }

        private static SpanAnalysis from(DcSpan span, Map<String, String> meta) {
            String rpcSystem = resolveRpcSystem(meta, span);
            String dbSystem = OtelAttributeMaps.firstNonBlank(meta, "db.system", "db.type");
            boolean hasDbSystem = dbSystem != null && !dbSystem.isBlank();
            // Align with OtelConverter: elasticsearch db.system takes priority over HTTP semconv.
            boolean elasticsearch = TraceSpanNames.isElasticsearchDbSystem(dbSystem)
                    || containsIgnoreCase(rpcSystem, "elastic");
            boolean http = !elasticsearch && (
                    isPresent(span.metaHttpMethod)
                            || (isPresent(span.metaHttpUrl) && !isRpcProtocolUrl(span.metaHttpUrl))
                            || span.metaHttpStatusCode != null);
            boolean rpc = !http && rpcSystem != null;
            String messagingSystem = OtelAttributeMaps.firstNonBlank(meta, "messaging.system");
            boolean mq = !http && !rpc && messagingSystem != null;
            boolean es = !http && !rpc && !mq && elasticsearch;
            String configType = OtelAttributeMaps.firstNonBlank(meta, "config.type");
            boolean config = !http && !rpc && !mq && !es && (
                    configType != null || (dbSystem != null && isConfigDbSystem(dbSystem.toLowerCase())));
            boolean redis = !http && !rpc && !mq && !es && !config
                    && dbSystem != null
                    && isRedisDbSystem(dbSystem);
            boolean db = !http && !rpc && !mq && !config
                    && dbSystem != null
                    && !isRedisDbSystem(dbSystem);
            return new SpanAnalysis(
                    span.metaRevision,
                    span.metaHttpMethod,
                    span.metaHttpUrl,
                    span.metaHttpStatusCode,
                    http,
                    rpc,
                    db,
                    redis,
                    mq,
                    es,
                    config,
                    hasDbSystem);
        }

        private boolean matches(DcSpan span) {
            return metaRevision == span.metaRevision
                    && Objects.equals(httpMethod, span.metaHttpMethod)
                    && Objects.equals(httpUrl, span.metaHttpUrl)
                    && Objects.equals(httpStatusCode, span.metaHttpStatusCode);
        }

        private static boolean isPresent(String value) {
            return value != null && !value.isBlank();
        }

        private static boolean containsIgnoreCase(String value, String needle) {
            return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
        }
    }

    static String resolveErrorType(DcSpan span) {
        if (span.metaErrorType != null && !span.metaErrorType.isBlank()) {
            return span.metaErrorType;
        }
        if (span.metaHttpStatusCode != null && span.metaHttpStatusCode >= 400) {
            return "HTTP " + span.metaHttpStatusCode;
        }
        if (span.resource != null && !span.resource.isBlank()) {
            return span.resource;
        }
        return "Unknown Error";
    }

    static String dbResource(DcSpan span, Map<String, String> meta) {
        String statement = OtelAttributeMaps.firstNonBlank(meta, "db.statement");
        if (statement != null) {
            return statement;
        }
        return nullToEmpty(span.resource);
    }

    static Map<String, String> dbComponentTags(DcSpan span, Map<String, String> meta) {
        Map<String, String> tags = virtualComponentTags(span);
        tags.put("resource", dbResource(span, meta));
        tags.put("dbType", nullToEmpty(OtelAttributeMaps.firstNonBlank(meta, "db.system", "db.type")));
        tags.put("sqlDatabase", nullToEmpty(OtelAttributeMaps.firstNonBlank(meta, "db.name")));
        tags.put("sqlOperation", normalizeSqlOperation(OtelAttributeMaps.firstNonBlank(meta, "db.operation")));
        tags.put("sqlContent", dbResource(span, meta));
        tags.put("isSlow", span.slow > 0 ? "1" : "0");
        return tags;
    }

    static String normalizeMetricServiceId(String serviceId, String serviceName) {
        if (serviceId != null && SERVICE_KEY.matcher(serviceId.trim()).matches()) {
            return serviceId.trim();
        }
        String name = firstNonBlank(serviceName, serviceId);
        if (name != null && !name.isBlank()) {
            return ServiceKeyUtil.of(name.trim());
        }
        return "";
    }

    public static String resolveDbPeer(DcSpan span, Map<String, String> meta) {
        if (span.metaPeerHostname != null && !span.metaPeerHostname.isBlank()) {
            return span.metaPeerHostname.trim();
        }
        String peer = OtelAttributeMaps.firstNonBlank(meta, "server.address", "net.peer.name");
        if (peer != null && !peer.isBlank()) {
            return peer.trim();
        }
        String dbName = OtelAttributeMaps.firstNonBlank(meta, "db.name");
        if (dbName != null && !dbName.isBlank()) {
            return dbName.trim();
        }
        return "unknown-db";
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback != null ? fallback : "";
    }

    public static String normalizeSqlOperation(String operation) {
        if (operation == null || operation.isBlank()) {
            return "";
        }
        return operation.trim().toUpperCase();
    }

    public static String normalizeHttpUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return url;
        }
        int pathStart = url.indexOf('/', scheme + 3);
        if (pathStart < 0) {
            return "/";
        }
        return url.substring(pathStart);
    }

    /**
     * 组件指标公共标签。虚拟服务入口（{@code dstService=[type]name}）使用下游虚拟服务维度，
     * 历史 portal 兼容：{@code OutProcessor#initComponentService}。
     */
    static Map<String, String> virtualComponentTags(DcSpan span) {
        Map<String, String> meta = OtelAttributeMaps.parse(span);
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("durationRange", DurationRangeUtil.bucket(span.duration));
        applyComponentDirectionTags(span, tags);
        tags.put("rootComponentType", rootComponentType(meta, span));
        tags.put("rootResource", rootResource(meta, span));
        if (shouldUseDestinationService(span)) {
            tags.put("service", nullToEmpty(span.dstService));
            tags.put("service_id", normalizeMetricServiceId(span.dstServiceId, span.dstService));
            tags.put("service_instance", nullToEmpty(span.dstServiceInstance));
        } else {
            tags.put("service", nullToEmpty(span.service));
            tags.put("service_id", nullToEmpty(span.serviceId));
            tags.put("service_instance", nullToEmpty(span.serviceInstance));
        }
        // Outbound caller: fill sets src*=self; inbound: src*=peer when known.
        tags.put("srcService", nullToEmpty(span.srcService));
        tags.put("srcServiceId", normalizeMetricServiceId(span.srcServiceId, span.srcService));
        tags.put("srcServiceInstance", nullToEmpty(span.srcServiceInstance));
        return tags;
    }

    private static void applyComponentDirectionTags(DcSpan span, Map<String, String> tags) {
        if (hasVirtualDestination(span)) {
            tags.put("isIn", "1");
            tags.put("isOut", "1");
            return;
        }
        SpanDirectionUtil.applyDirectionTags(span, tags);
    }

    private static boolean shouldUseDestinationService(DcSpan span) {
        if (span.isOut != 1 || span.dstService == null || span.dstService.isBlank()) {
            return false;
        }
        if (hasVirtualDestination(span)) {
            return true;
        }
        if (isDbSpan(span) || isRedisSpan(span)) {
            return true;
        }
        // Internal HTTP/RPC client spans attribute metrics to the callee so portal
        // downstream in/out peer rollups merge on the same service key.
        if (span.service != null && span.service.equals(span.dstService)) {
            return false;
        }
        return isHttpSpan(span) || isRpcSpan(span);
    }

    private static Map<String, String> componentBaseTags(DcSpan span) {
        Map<String, String> tags = virtualComponentTags(span);
        tags.put("resource", nullToEmpty(span.resource));
        return tags;
    }

    static Map<String, String> exceptionMetricTags(DcSpan span) {
        Map<String, String> meta = OtelAttributeMaps.parse(span);
        Map<String, String> tags = new LinkedHashMap<>();
        applyComponentDirectionTags(span, tags);
        tags.put("rootComponentType", rootComponentType(meta, span));
        tags.put("rootResource", rootResource(meta, span));
        applyErrorServiceTags(span, tags);
        tags.put("resource", nullToEmpty(span.resource));
        tags.put("exceptionName", resolveErrorType(span));
        tags.put("exceptionCode", "");
        tags.put("componentService", "");
        tags.put("componentServiceId", "");
        tags.put("componentServiceInstance", "");
        return tags;
    }

    /** 虚拟组件入口错误归属组件服务本身，调用方写入 componentService* 供下钻。 */
    static Map<String, String> virtualInboundComponentExceptionTags(DcSpan span) {
        Map<String, String> meta = OtelAttributeMaps.parse(span);
        Map<String, String> tags = new LinkedHashMap<>();
        applyComponentDirectionTags(span, tags);
        tags.put("rootComponentType", rootComponentType(meta, span));
        tags.put("rootResource", rootResource(meta, span));
        String componentService = nullToEmpty(firstNonBlank(span.dstService, span.service));
        tags.put("service", componentService);
        tags.put("service_id", normalizeMetricServiceId(
                firstNonBlank(span.dstServiceId, span.serviceId), componentService));
        tags.put("service_instance", nullToEmpty(firstNonBlank(span.dstServiceInstance, span.serviceInstance)));
        tags.put("resource", nullToEmpty(span.resource));
        tags.put("exceptionName", resolveErrorType(span));
        tags.put("exceptionCode", "");
        tags.put("componentService", nullToEmpty(span.srcService));
        tags.put("componentServiceId", normalizeMetricServiceId(span.srcServiceId, span.srcService));
        tags.put("componentServiceInstance", nullToEmpty(span.srcServiceInstance));
        return tags;
    }

    /**
     * 错误指标归属 Web 服务：当前 service 为 Web 服务则归当前服务，为虚拟服务则归 srcService。
     */
    static void applyErrorServiceTags(DcSpan span, Map<String, String> tags) {
        if (ServiceFlowSpanRules.isVirtualServiceSpan(span)) {
            tags.put("service", nullToEmpty(span.srcService));
            tags.put("service_id", normalizeMetricServiceId(span.srcServiceId, span.srcService));
            tags.put("service_instance", nullToEmpty(span.srcServiceInstance));
            return;
        }
        tags.put("service", nullToEmpty(span.service));
        tags.put("service_id", normalizeMetricServiceId(span.serviceId, span.service));
        tags.put("service_instance", nullToEmpty(span.serviceInstance));
    }

    /** Trace 抽取指标：按 span.end 所在分钟落桶，供分钟窗口聚合。 */
    private static OptimizedMetric minuteAggregatedMetric(String measurement, DcSpan span, Map<String, String> tags) {
        return minuteAggregatedMetric(
                measurement, span, tags, 1L, componentMetricError(span), span.duration, span.duration);
    }

    private static OptimizedMetric minuteAggregatedMetric(
            String measurement, DcSpan span, Map<String, String> tags, long... fieldValues) {
        long endNanos = span.end > 0 ? span.end : span.start;
        long minuteBucketNs = TraceMetricMinuteBucket.minuteBucketEpochNanosFromEndNanos(endNanos);
        long minuteBucketMs = minuteBucketNs / 1_000_000L;
        OptimizedMetric metric = new OptimizedMetric()
                .withTimestamp(minuteBucketNs)
                .withMeasurement(measurement)
                .withTagValues(MetricSchemaRegistry.tagValuesFromMap(measurement, tags))
                .withFieldValues(fieldValues);
        return metric.withTsId(TraceMetricMinuteBucket.aggregationTsId(metric, minuteBucketMs));
    }

    private static long componentMetricError(DcSpan span) {
        return span.error <= 0 ? 0L : span.error;
    }

    private static String nullToEmpty(String value) {
        return Objects.toString(value, "");
    }

    private static String rootResource(Map<String, String> meta, DcSpan span) {
        String fromMeta = OtelAttributeMaps.firstNonBlank(meta, "root.resource");
        if (fromMeta != null && !fromMeta.isBlank()) {
            return fromMeta;
        }
        if (span.is_parent == 1 && isHttpSpan(span)) {
            return normalizeHttpUrl(span.metaHttpUrl != null && !span.metaHttpUrl.isBlank()
                    ? span.metaHttpUrl
                    : nullToEmpty(span.resource));
        }
        if (span.is_parent == 1 && isRpcSpan(span)) {
            return nullToEmpty(span.resource);
        }
        return "";
    }

    private static String rootComponentType(Map<String, String> meta, DcSpan span) {
        String fromMeta = OtelAttributeMaps.firstNonBlank(meta, "root.type", "root.component.type");
        if (fromMeta != null) {
            return normalizeRootComponentType(fromMeta);
        }
        if (span.is_parent == 1) {
            if (isHttpSpan(span)) {
                return "service.http";
            }
            if (isRpcSpan(span)) {
                return "service.rpc";
            }
            if (isDbSpan(span)) {
                return "service.db";
            }
        }
        return "";
    }

    private static String normalizeRootComponentType(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("service.")) {
            return trimmed;
        }
        return switch (trimmed.toLowerCase(Locale.ROOT)) {
            case "web", "http" -> "service.http";
            case "rpc" -> "service.rpc";
            case "db", "database" -> "service.db";
            case "mq" -> "service.mq";
            case "redis", "cache" -> "service.redis";
            default -> trimmed;
        };
    }

}
