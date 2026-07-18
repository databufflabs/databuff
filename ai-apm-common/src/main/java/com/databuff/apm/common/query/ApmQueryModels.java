package com.databuff.apm.common.query;

/** Storage-neutral read models for APM queries (trace, metric, topology). */
public final class ApmQueryModels {

    private ApmQueryModels() {
    }

    public record TrafficLightPoint(String ts, String service, long errorCount, long totalCount) {
    }

    public record SpanSummary(
            String traceId,
            String spanId,
            String service,
            String serviceId,
            String name,
            String startTime,
            long duration,
            int error,
            String serviceInstance,
            String resource,
            String hostName,
            Integer metaHttpStatusCode,
            String metaErrorType,
            String parentId,
            Integer isParent,
            String metaHttpUrl) {

        public SpanSummary(
                String traceId,
                String spanId,
                String service,
                String serviceId,
                String name,
                String startTime,
                long duration,
                int error,
                String serviceInstance,
                String resource,
                String hostName,
                Integer metaHttpStatusCode,
                String metaErrorType) {
            this(
                    traceId,
                    spanId,
                    service,
                    serviceId,
                    name,
                    startTime,
                    duration,
                    error,
                    serviceInstance,
                    resource,
                    hostName,
                    metaHttpStatusCode,
                    metaErrorType,
                    null,
                    null,
                    null);
        }

        public SpanSummary(
                String traceId,
                String spanId,
                String service,
                String serviceId,
                String name,
                String startTime,
                long duration,
                int error,
                String serviceInstance,
                String resource,
                String hostName,
                Integer metaHttpStatusCode,
                String metaErrorType,
                String parentId,
                Integer isParent) {
            this(
                    traceId,
                    spanId,
                    service,
                    serviceId,
                    name,
                    startTime,
                    duration,
                    error,
                    serviceInstance,
                    resource,
                    hostName,
                    metaHttpStatusCode,
                    metaErrorType,
                    parentId,
                    isParent,
                    null);
        }
    }

    public record SpanDetail(
            String traceId,
            String spanId,
            String parentId,
            String service,
            String serviceId,
            String name,
            String startTime,
            long start,
            long duration,
            int error,
            String hostName,
            String serviceInstance,
            String resource,
            String type,
            int isIn,
            int isOut,
            String meta,
            String metrics,
            Integer metaHttpStatusCode,
            String metaHttpMethod,
            String metaHttpUrl,
            String metaErrorType) {
        public SpanDetail(
                String traceId,
                String spanId,
                String parentId,
                String service,
                String serviceId,
                String name,
                String startTime,
                long start,
                long duration,
                int error,
                String hostName) {
            this(
                    traceId,
                    spanId,
                    parentId,
                    service,
                    serviceId,
                    name,
                    startTime,
                    start,
                    duration,
                    error,
                    hostName,
                    null,
                    null,
                    null,
                    0,
                    0,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
    }

    /** Row from {@code trace_dc_span} for portal {@code POST /trace/call_spans}. */
    public record CallSpanRow(
            String traceId,
            String spanId,
            String parentId,
            long start,
            long end,
            String resource,
            long duration,
            int error,
            int slow,
            String service,
            String serviceId,
            String serviceInstance,
            String srcService,
            String srcServiceId,
            String srcServiceInstance,
            String dstService,
            String dstServiceId,
            String dstServiceInstance,
            int isIn,
            int isOut,
            String name,
            String meta,
            String metrics,
            Integer metaHttpStatusCode,
            String metaHttpMethod,
            String metaHttpUrl,
            String metaErrorType) {
    }

    public record ServiceMetricPoint(
            String ts,
            String service,
            long requestCount,
            long errorCount,
            /** Average response time in milliseconds. */
            double avgDuration) {
    }

    /** Per-service rollup for portal /service/list. */
    public record ServiceSummaryPoint(
            String service,
            String serviceId,
            long requestCount,
            long errorCount,
            /** Total sumDuration in nanoseconds (before dividing by cnt). */
            double sumDurationNs,
            /** Max maxDuration in nanoseconds within the query window. */
            double maxDurationNs) {
    }

    /** Per-db-service rollup for portal {@code POST /service/dbList}. */
    public record DbServiceSummaryPoint(
            String service,
            String serviceId,
            String dbType,
            long requestCount,
            long errorCount,
            long slowCount,
            /** Total sumDuration in nanoseconds (before dividing by cnt). */
            double sumDurationNs,
            /** Maximum duration in nanoseconds. */
            double maxDurationNs) {
    }

    /** Time-bucket rollup for portal /service/serviceListTrendChart. */
    public record ServiceTrendBucketPoint(
            long bucketEpochSec,
            String service,
            long requestCount,
            long errorCount,
            double sumDurationNs) {
    }

    /** Extended component metric buckets (service.db / service.rpc …). */
    public record ComponentTrendBucketPoint(
            long bucketEpochSec,
            String service,
            long requestCount,
            long errorCount,
            double sumDurationNs,
            double maxDurationNs,
            double minDurationNs,
            double sumReadRows,
            double sumUpdateRows,
            long slowCount) {
        public ComponentTrendBucketPoint(
                long bucketEpochSec,
                String service,
                long requestCount,
                long errorCount,
                double sumDurationNs,
                double maxDurationNs,
                double minDurationNs,
                double sumReadRows,
                double sumUpdateRows) {
            this(
                    bucketEpochSec,
                    service,
                    requestCount,
                    errorCount,
                    sumDurationNs,
                    maxDurationNs,
                    minDurationNs,
                    sumReadRows,
                    sumUpdateRows,
                    0L);
        }
    }

    public record ErrorRateSnapshot(long errorCount, long totalCount) {
        public double errorRate() {
            if (totalCount <= 0) {
                return 0;
            }
            return (double) errorCount / totalCount;
        }
    }

    public record TopologyEdge(String srcService, String dstService, long callCount, long errorCount) {
    }

    /** DB peer rollup for portal service relation downstream (service.db). */
    public record DbDownstreamPoint(
            String serviceId,
            String service,
            long requestCount,
            long errorCount,
            double avgDuration) {
    }

    public record ServiceFlowEdge(
            String srcService,
            String dstService,
            long callCount,
            long errorCount,
            /** Average call duration in milliseconds. */
            double avgDuration,
            String srcServiceId,
            String dstServiceId) {
    }

    /** Aggregated row from {@code metric_service_flow} for portal multiple service flow. */
    public record ServiceFlowTreeRow(
            String pathId,
            String parentPathId,
            String service,
            String serviceId,
            String resource,
            int isIn,
            long callCount,
            long errorCount,
            long srcCall,
            long sumDuration) {
    }

    /** Entry service for portal {@code /trace/serviceFlowEndpoint}. */
    public record ServiceFlowEntryPoint(
            String service,
            String serviceId,
            String entrypointPathId) {
    }

    public record HttpEndpointPoint(
            String serviceId,
            String service,
            String url,
            String httpMethod,
            String httpCode,
            long requestCount,
            long errorCount,
            /** Average response time in milliseconds. */
            double avgDuration,
            /** Maximum response time in nanoseconds. */
            double maxDurationNs) {
    }

    public record DbEndpointPoint(
            String serviceId,
            String service,
            String resource,
            String sqlOperation,
            String dbType,
            String sqlDatabase,
            long requestCount,
            long errorCount,
            /** Average response time in milliseconds. */
            double avgDuration,
            /** Maximum response time in nanoseconds. */
            double maxDurationNs,
            double sumReadRows,
            double sumUpdateRows) {
    }

    /** Slow SQL row for portal {@code POST /service/slowSqlTopList}. */
    public record DbSlowSqlTopPoint(
            String resource,
            long requestCount,
            long errorCount,
            double avgTimeNs,
            double maxDurationNs,
            double minDurationNs,
            long srcServiceCnt) {
    }

    /** Directional call rollup for portal {@code /service/call_info}. */
    public record ComponentCallStatsPoint(long requestCount, long errorCount, double sumDurationNs) {
        public static ComponentCallStatsPoint empty() {
            return new ComponentCallStatsPoint(0, 0, 0);
        }

        public double avgDurationMs() {
            return requestCount > 0 ? sumDurationNs / requestCount / 1_000_000.0 : 0;
        }
    }

    /** Aggregated endpoint row for portal {@code /service/call_endpoints} (non-HTTP/DB components). */
    public record ComponentEndpointPoint(
            String serviceId,
            String service,
            String resource,
            java.util.Map<String, String> tags,
            long requestCount,
            long errorCount,
            /** Average response time in milliseconds. */
            double avgDuration,
            /** Maximum response time in nanoseconds. */
            double maxDurationNs,
            double sumReadRows,
            double sumUpdateRows,
            double sumReqBodyLength,
            double sumRespBodyLength,
            double sumDelay,
            double sumMqBodyLength) {
    }

    public record HttpLatencyBucketPoint(String durationRange, long requestCount, long errorCount) {
    }

    public record MetricSeriesPoint(long epochSeconds, Double value) {
    }

    /** Aggregated component row for portal resource relation APIs. */
    public record ComponentResourceRelationPoint(
            String serviceId,
            String service,
            String resource,
            String srcServiceId,
            String srcService,
            String rootResource,
            String rootComponentType,
            long allCnt,
            long slowCnt,
            long errCnt,
            double avgTimeNs,
            double maxTimeNs) {
    }

    /** Row from {@code metric_service_exception} for portal {@code POST /trace/exceptionList}. */
    public record ExceptionListPoint(
            long ts,
            String resource,
            String exceptionName,
            String service,
            String serviceId,
            String serviceInstance,
            String rootResource,
            long errorCount) {
    }

    /** Grouped error count for portal /service/exceptionDistMap. */
    public record ExceptionDistPoint(
            String exceptionName,
            String serviceId,
            String serviceInstance,
            String resource,
            String rootResource,
            long errorCount) {
    }

    /** Per-instance rollup for portal {@code GET /service/getServiceInstance}. */
    public record ServiceInstanceSummaryPoint(
            String serviceInstance,
            String hostName,
            String hostId,
            long callCount,
            String k8sNamespace,
            String k8sPodName,
            String k8sClusterId,
            String containerId,
            String processName) {
    }

    /** Row from Doris {@code meta_service} (legacy MySQL {@code dc_databuff_service} shape). */
    public record MetaServicePoint(
            String id,
            String name,
            String service,
            String serviceType,
            String apikey,
            String type,
            String technology,
            String language,
            String datasource,
            String source,
            String fqdn,
            String containerService,
            Boolean virtualService,
            String describe,
            String customTags,
            String processRuntimeName,
            String processRuntimeVersion) {

        public String serviceId() {
            return id;
        }

        public String serviceName() {
            return name;
        }

        public static MetaServicePoint minimal(String id, String name) {
            return new MetaServicePoint(
                    id, name, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        }
    }
}
