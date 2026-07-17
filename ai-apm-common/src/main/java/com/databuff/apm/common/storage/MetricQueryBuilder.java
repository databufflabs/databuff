package com.databuff.apm.common.storage;

import com.databuff.apm.common.time.ApmTimeZones;
import com.databuff.apm.common.util.PortalServiceIdResolver;

import java.time.format.DateTimeParseException;
import java.util.List;

public final class MetricQueryBuilder {

    /** Doris stores span duration in nanoseconds; API exposes average latency in milliseconds. */
    private static final String AVG_DURATION_MS_EXPR =
            "SUM(`sumDuration`) / NULLIF(SUM(`cnt`), 0) / 1000000";

    private MetricQueryBuilder() {
    }

    private static long resolveSpanTimeFromMillis(long fromMillis, String fromTimeText) {
        String literal = normalizeSpanTimeText(fromTimeText);
        if (literal != null) {
            return ApmTimeZones.wallClockToEpochMilli(literal);
        }
        return fromMillis;
    }

    private static long resolveSpanTimeToMillis(long toMillis, String toTimeText) {
        String literal = normalizeSpanTimeText(toTimeText);
        if (literal != null) {
            return ApmTimeZones.wallClockToEpochMilli(literal);
        }
        return toMillis;
    }

    /** Aligns with {@link com.databuff.apm.common.metric.TraceMetricMinuteBucket} / {@code metric_service_trace}. */
    private static final String SPAN_END_MINUTE_BUCKET_MS_EXPR =
            "(FLOOR(`end` / 1000000 / 60000) * 60000)";

    /**
     * All span lists / drill-downs bucket by span end minute to stay consistent with
     * {@code metric_service_*} (whose {@code ts} is the span end-minute bucket, see
     * {@link com.databuff.apm.common.metric.TraceMetricMinuteBucket#minuteBucketEpochMsFromEndNanos}).
     * Bucketing by {@code startTime} drifts off the metric chart for long-lived spans
     * (e.g. streaming RPC {@code EventStream}).
     */
    private static String spanEndBucketTimeWhere(
            long fromMillis,
            long toMillis,
            String fromTimeText,
            String toTimeText) {
        long from = resolveSpanTimeFromMillis(fromMillis, fromTimeText);
        long to = resolveSpanTimeToMillis(toMillis, toTimeText);
        return SPAN_END_MINUTE_BUCKET_MS_EXPR + " >= " + from
                + " AND " + SPAN_END_MINUTE_BUCKET_MS_EXPR + " < " + to;
    }

    private static String spanListTimeWhere(
            long fromMillis,
            long toMillis,
            String fromTimeText,
            String toTimeText,
            Integer isParent) {
        return spanEndBucketTimeWhere(fromMillis, toMillis, fromTimeText, toTimeText);
    }

    private static String normalizeSpanTimeText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = ApmTimeZones.normalizeWallClockText(text);
        try {
            java.time.LocalDateTime.parse(trimmed, ApmTimeZones.WALL_CLOCK_LOCAL);
            return trimmed.replace("'", "''");
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /** Portal endTime is an exclusive upper bound; minute bucket [20:34, 20:35) is stored/queryable before 20:35. */
    private static String metricTsWhere(long fromMillis, long toMillis) {
        return "`ts` >= " + fromMillis + " AND `ts` < " + toMillis;
    }

    private static String metricMinuteTsSelect() {
        return "FROM_UNIXTIME(FLOOR(`ts` / 60000) * 60)";
    }

    private static String metricBucketEpochSecSelect(int bucketSec) {
        return "CAST(FLOOR(`ts` / 1000 / " + bucketSec + ") * " + bucketSec + " AS BIGINT)";
    }

    private static String metricMinuteEpochSecSelect() {
        return "CAST(FLOOR(`ts` / 60000) * 60 AS BIGINT)";
    }

    public static String trafficLightSql(String database, long fromMillis, long toMillis) {
        return """
                SELECT %s AS ts,
                       `service`,
                       SUM(`error`) AS error_cnt,
                       SUM(`cnt`) AS total_cnt
                FROM %s.`metric_service`
                WHERE %s
                GROUP BY ts, `service`
                ORDER BY ts ASC
                """.formatted(metricMinuteTsSelect(), database, metricTsWhere(fromMillis, toMillis));
    }

    public static String spanListSql(String database, String service, long fromMillis, long toMillis, int limit) {
        return spanListSql(database, service, fromMillis, toMillis, limit, null, null);
    }

    public static String spanListSql(
            String database,
            String service,
            long fromMillis,
            long toMillis,
            int limit,
            String fromTimeText,
            String toTimeText) {
        return spanListSql(
                database,
                service == null || service.isBlank() ? null : java.util.List.of(service),
                fromMillis,
                toMillis,
                limit,
                0,
                fromTimeText,
                toTimeText,
                null,
                null,
                null,
                null);
    }

    public static String spanListSql(
            String database,
            java.util.Collection<String> serviceKeys,
            long fromMillis,
            long toMillis,
            int limit,
            int offset,
            String fromTimeText,
            String toTimeText,
            Integer isParent,
            String parentId,
            String sortField,
            String sortOrder) {
        return spanListSql(
                database,
                serviceKeys,
                fromMillis,
                toMillis,
                limit,
                offset,
                fromTimeText,
                toTimeText,
                isParent,
                parentId,
                sortField,
                sortOrder,
                null,
                null,
                null);
    }

    public static String spanListSql(
            String database,
            java.util.Collection<String> serviceKeys,
            long fromMillis,
            long toMillis,
            int limit,
            int offset,
            String fromTimeText,
            String toTimeText,
            Integer isParent,
            String parentId,
            String sortField,
            String sortOrder,
            String resourceExact,
            Long minDurationNs,
            Integer error) {
        String timeWhere = spanListTimeWhere(fromMillis, toMillis, fromTimeText, toTimeText, isParent);
        String filters = buildTraceServiceKeyOrFilter(serviceKeys)
                + appendTraceSpanListScopeFilters(isParent, parentId)
                + appendSpanListDetailFilters(resourceExact, minDurationNs, error);
        int safeLimit = Math.max(1, Math.min(limit, 500));
        int safeOffset = Math.max(0, offset);
        return """
                SELECT `trace_id`, `span_id`, `parent_id`, `is_parent`, `service`,
                       COALESCE(NULLIF(`serviceId`, ''), `service`) AS service_id,
                       `name`, `startTime`, `duration`, `error`,
                       COALESCE(`serviceInstance`, '') AS serviceInstance,
                       COALESCE(`resource`, `name`) AS resource,
                       COALESCE(`hostName`, '') AS hostName,
                       `meta.http.status_code` AS meta_http_status_code,
                       `meta.error.type` AS meta_error_type,
                       COALESCE(`meta.http.url`, '') AS meta_http_url
                FROM %s.`trace_dc_span`
                WHERE %s
                %s
                ORDER BY %s %s
                LIMIT %d OFFSET %d
                """.formatted(
                database,
                timeWhere,
                filters,
                spanListOrderColumn(sortField),
                spanListOrderDirection(sortOrder),
                safeLimit,
                safeOffset);
    }

    public static String spanListCountSql(
            String database,
            java.util.Collection<String> serviceKeys,
            long fromMillis,
            long toMillis,
            String fromTimeText,
            String toTimeText,
            Integer isParent,
            String parentId) {
        return spanListCountSql(
                database,
                serviceKeys,
                fromMillis,
                toMillis,
                fromTimeText,
                toTimeText,
                isParent,
                parentId,
                null,
                null,
                null);
    }

    public static String spanListCountSql(
            String database,
            java.util.Collection<String> serviceKeys,
            long fromMillis,
            long toMillis,
            String fromTimeText,
            String toTimeText,
            Integer isParent,
            String parentId,
            String resourceExact,
            Long minDurationNs,
            Integer error) {
        String timeWhere = spanListTimeWhere(fromMillis, toMillis, fromTimeText, toTimeText, isParent);
        String filters = buildTraceServiceKeyOrFilter(serviceKeys)
                + appendTraceSpanListScopeFilters(isParent, parentId)
                + appendSpanListDetailFilters(resourceExact, minDurationNs, error);
        return """
                SELECT COUNT(*) AS total_cnt
                FROM %s.`trace_dc_span`
                WHERE %s
                %s
                """.formatted(database, timeWhere, filters);
    }

    /** Error span list ({@code POST /webapi/trace/errorSpanList}) — web apps match owned or caller service. */
    public static String errorSpanListSql(
            String database,
            java.util.Collection<String> serviceKeys,
            boolean virtualServiceFilter,
            long fromMillis,
            long toMillis,
            int limit,
            int offset,
            String fromTimeText,
            String toTimeText,
            String sortField,
            String sortOrder,
            String resourceExact,
            String exceptionContains) {
        String timeWhere = spanEndBucketTimeWhere(fromMillis, toMillis, fromTimeText, toTimeText);
        String filters = buildTraceErrorSpanListServiceFilter(serviceKeys, virtualServiceFilter)
                + appendSpanListDetailFilters(resourceExact, null, 1)
                + appendErrorSpanExceptionFilter(exceptionContains);
        int safeLimit = Math.max(1, Math.min(limit, 500));
        int safeOffset = Math.max(0, offset);
        return """
                SELECT `trace_id`, `span_id`, `parent_id`, `is_parent`, `service`,
                       COALESCE(NULLIF(`serviceId`, ''), `service`) AS service_id,
                       `name`, `startTime`, `duration`, `error`,
                       COALESCE(`serviceInstance`, '') AS serviceInstance,
                       COALESCE(`resource`, `name`) AS resource,
                       COALESCE(`hostName`, '') AS hostName,
                       `meta.http.status_code` AS meta_http_status_code,
                       `meta.error.type` AS meta_error_type,
                       COALESCE(`meta.http.url`, '') AS meta_http_url
                FROM %s.`trace_dc_span`
                WHERE %s
                %s
                ORDER BY %s %s
                LIMIT %d OFFSET %d
                """.formatted(
                database,
                timeWhere,
                filters,
                spanListOrderColumn(sortField),
                spanListOrderDirection(sortOrder),
                safeLimit,
                safeOffset);
    }

    public static String errorSpanListCountSql(
            String database,
            java.util.Collection<String> serviceKeys,
            boolean virtualServiceFilter,
            long fromMillis,
            long toMillis,
            String fromTimeText,
            String toTimeText,
            String resourceExact,
            String exceptionContains) {
        String timeWhere = spanEndBucketTimeWhere(fromMillis, toMillis, fromTimeText, toTimeText);
        String filters = buildTraceErrorSpanListServiceFilter(serviceKeys, virtualServiceFilter)
                + appendSpanListDetailFilters(resourceExact, null, 1)
                + appendErrorSpanExceptionFilter(exceptionContains);
        return """
                SELECT COUNT(*) AS total_cnt
                FROM %s.`trace_dc_span`
                WHERE %s
                %s
                """.formatted(database, timeWhere, filters);
    }

    static String buildTraceErrorSpanListServiceFilter(
            java.util.Collection<String> keys, boolean virtualServiceFilter) {
        if (virtualServiceFilter) {
            return buildTraceServiceIdsFilter(keys);
        }
        return buildTraceOwnedOrCallerServiceFilter(keys);
    }

    private static String appendErrorSpanExceptionFilter(String exceptionContains) {
        if (exceptionContains == null || exceptionContains.isBlank()) {
            return "";
        }
        return " AND " + SPAN_EXCEPTION_NAME_EXPR
                + " LIKE '%" + escapeLiteral(exceptionContains.trim()) + "%' ";
    }

    private static String appendSpanListDetailFilters(String resourceExact, Long minDurationNs, Integer error) {
        StringBuilder filters = new StringBuilder();
        filters.append(appendSpanListResourceFilter(resourceExact));
        if (minDurationNs != null && minDurationNs > 0) {
            filters.append(" AND `duration` >= ").append(minDurationNs).append(' ');
        }
        if (error != null) {
            filters.append(" AND `error` = ").append(error).append(' ');
        }
        return filters.toString();
    }

    /** Strict portal endpoint match on {@code meta.http.url}. */
    static String appendSpanListResourceFilter(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return "";
        }
        String escaped = escapeLiteral(resourcePath.trim());
        return " AND `meta.http.url` = '" + escaped + "' ";
    }

    private static String appendTraceSpanListScopeFilters(Integer isParent, String parentId) {
        StringBuilder filters = new StringBuilder();
        if (isParent != null) {
            filters.append(" AND `is_parent` = ").append(isParent).append(' ');
        }
        if (parentId != null && !parentId.isBlank() && !"0".equals(parentId)) {
            filters.append(" AND `parent_id` = '").append(escapeLiteral(parentId)).append("' ");
        }
        return filters.toString();
    }

    private static String spanListOrderColumn(String sortField) {
        if (sortField == null || sortField.isBlank()) {
            return "`startTime`";
        }
        return switch (sortField) {
            case "start", "startTime" -> "`startTime`";
            case "duration" -> "`duration`";
            case "resource" -> "`resource`";
            case "service", "serviceId" -> "`service`";
            case "error" -> "`error`";
            default -> "`startTime`";
        };
    }

    private static String spanListOrderDirection(String sortOrder) {
        return "asc".equalsIgnoreCase(sortOrder) ? "ASC" : "DESC";
    }

    static String buildTraceServiceKeyOrFilter(java.util.Collection<String> keys) {
        return buildTraceServiceIdsFilter(keys);
    }

    private static String buildTraceServiceIdsFilter(java.util.Collection<String> keys) {
        java.util.LinkedHashSet<String> normalized = normalizeTraceServiceKeys(keys);
        if (normalized.isEmpty()) {
            return "";
        }
        if (normalized.size() == 1) {
            return " AND `serviceId` = '" + escapeLiteral(normalized.iterator().next()) + "' ";
        }
        String joined = joinEscapedLiterals(normalized);
        return " AND `serviceId` IN (" + joined + ") ";
    }

    private static String buildTraceOwnedOrCallerServiceFilter(java.util.Collection<String> keys) {
        java.util.LinkedHashSet<String> normalized = normalizeTraceServiceKeys(keys);
        if (normalized.isEmpty()) {
            return "";
        }
        if (normalized.size() == 1) {
            String id = escapeLiteral(normalized.iterator().next());
            return " AND (`serviceId` = '" + id + "' OR `srcServiceId` = '" + id + "') ";
        }
        String joined = joinEscapedLiterals(normalized);
        return " AND (`serviceId` IN (" + joined + ") OR `srcServiceId` IN (" + joined + ")) ";
    }

    private static java.util.LinkedHashSet<String> normalizeTraceServiceKeys(java.util.Collection<String> keys) {
        java.util.LinkedHashSet<String> normalized = new java.util.LinkedHashSet<>();
        if (keys == null) {
            return normalized;
        }
        for (String key : keys) {
            if (key != null && !key.isBlank()) {
                String id = PortalServiceIdResolver.normalize(key.trim());
                if (!id.isBlank()) {
                    normalized.add(id);
                }
            }
        }
        return normalized;
    }

    private static String joinEscapedLiterals(java.util.Collection<String> values) {
        return values.stream()
                .map(id -> "'" + escapeLiteral(id) + "'")
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String buildTraceColumnServiceIdFilter(String column, String serviceId) {
        if (serviceId == null || serviceId.isBlank()) {
            return "";
        }
        String normalized = PortalServiceIdResolver.normalize(serviceId.trim());
        if (normalized.isBlank()) {
            return "";
        }
        return " AND `" + column + "` = '" + escapeLiteral(normalized) + "' ";
    }

    public static String serviceInstanceDistinctSql(
            String database, String service, long fromMillis, long toMillis, int limit) {
        String filters = buildServiceIdFilter(service);
        return """
                SELECT DISTINCT `service_instance` AS group_value
                FROM %s.`%s`
                WHERE %s
                %s
                  AND `service_instance` IS NOT NULL
                  AND `service_instance` != ''
                ORDER BY group_value ASC
                LIMIT %d
                """.formatted(
                database,
                DorisTableNames.METRIC_SERVICE_INSTANCE,
                metricTsWhere(fromMillis, toMillis),
                filters,
                Math.max(1, Math.min(limit, 200)));
    }

    public static String serviceInstanceSummarySql(
            String database,
            String service,
            long fromMillis,
            long toMillis,
            String serviceInstance,
            int limit) {
        StringBuilder innerFilters = new StringBuilder();
        innerFilters.append(buildServiceIdFilter(service));
        StringBuilder outerFilters = new StringBuilder();
        outerFilters.append(buildServiceIdFilter(service));
        if (serviceInstance != null && !serviceInstance.isBlank()) {
            String escapedInstance = escapeLiteral(serviceInstance);
            innerFilters.append(" AND `service_instance` = '")
                    .append(escapedInstance).append("' ");
            // Outer query joins inst + calls(subquery); qualify instance column to avoid Doris ambiguity.
            outerFilters.append(" AND inst.`service_instance` = '")
                    .append(escapedInstance).append("' ");
        }
        String tsWhere = metricTsWhere(fromMillis, toMillis);
        String innerFilterClause = innerFilters.toString();
        String outerFilterClause = outerFilters.toString();
        return """
                SELECT inst.`service_instance`,
                       MAX(inst.`hostname`) AS host_name,
                       MAX(inst.`hostIp`) AS host_id,
                       COALESCE(MAX(calls.call_cnt), 0) AS call_cnt,
                       MAX(inst.`k8sNamespace`) AS k8s_namespace,
                       MAX(inst.`k8sPodName`) AS k8s_pod_name,
                       MAX(inst.`k8sClusterId`) AS k8s_cluster_id,
                       MAX(inst.`containerId`) AS container_id,
                       MAX(inst.`pname`) AS process_name
                FROM %s.`%s` inst
                LEFT JOIN (
                    SELECT `service_instance`, SUM(`cnt`) AS call_cnt
                    FROM %s.`%s`
                    WHERE %s
                    %s
                    GROUP BY `service_instance`
                ) calls ON inst.`service_instance` = calls.`service_instance`
                WHERE %s
                %s
                  AND inst.`service_instance` IS NOT NULL
                  AND inst.`service_instance` != ''
                GROUP BY inst.`service_instance`
                ORDER BY call_cnt DESC
                LIMIT %d
                """.formatted(
                database,
                DorisTableNames.METRIC_SERVICE_INSTANCE,
                database,
                DorisTableNames.METRIC_SERVICE,
                tsWhere,
                innerFilterClause,
                tsWhere,
                outerFilterClause,
                Math.max(1, Math.min(limit, 200)));
    }

    public static String k8sNamespaceDistinctSql(String database, long fromMillis, long toMillis, int limit) {
        return """
                SELECT DISTINCT `k8sNamespace` AS group_value
                FROM %s.`%s`
                WHERE %s
                  AND `k8sNamespace` IS NOT NULL
                  AND `k8sNamespace` != ''
                ORDER BY group_value ASC
                LIMIT %d
                """.formatted(
                database,
                DorisTableNames.METRIC_SERVICE_INSTANCE,
                metricTsWhere(fromMillis, toMillis),
                Math.max(1, Math.min(limit, 200)));
    }

    public static String serviceK8sNamespaceMapSql(String database, long fromMillis, long toMillis, int limit) {
        return """
                SELECT COALESCE(NULLIF(`service_id`, ''), `service`) AS map_key,
                       MAX(`k8sNamespace`) AS map_value
                FROM %s.`%s`
                WHERE %s
                  AND `k8sNamespace` IS NOT NULL
                  AND `k8sNamespace` != ''
                GROUP BY map_key
                ORDER BY map_key ASC
                LIMIT %d
                """.formatted(
                database,
                DorisTableNames.METRIC_SERVICE_INSTANCE,
                metricTsWhere(fromMillis, toMillis),
                Math.max(1, Math.min(limit, 500)));
    }

    public static String serviceInstanceCountMapSql(String database, long fromMillis, long toMillis, int limit) {
        return """
                SELECT COALESCE(NULLIF(`service_id`, ''), `service`) AS map_key,
                       COUNT(DISTINCT `service_instance`) AS map_value
                FROM %s.`%s`
                WHERE %s
                  AND `service_instance` IS NOT NULL
                  AND `service_instance` != ''
                GROUP BY map_key
                ORDER BY map_key ASC
                LIMIT %d
                """.formatted(
                database,
                DorisTableNames.METRIC_SERVICE_INSTANCE,
                metricTsWhere(fromMillis, toMillis),
                Math.max(1, Math.min(limit, 500)));
    }

    public static String traceDetailSql(String database, String traceId) {
        String escaped = traceId.replace("'", "''");
        return """
                SELECT `trace_id`, `span_id`, `parent_id`, `service`,
                       COALESCE(NULLIF(`serviceId`, ''), `service`) AS service_id,
                       `name`, `startTime`, `start`, `duration`, `error`, `hostName`,
                       `serviceInstance`, `resource`, `type`, `isIn`, `isOut`, `meta`, `metrics`,
                       `meta.http.status_code` AS meta_http_status_code,
                       `meta.http.method` AS meta_http_method,
                       `meta.http.url` AS meta_http_url,
                       `meta.error.type` AS meta_error_type
                FROM %s.`trace_dc_span`
                WHERE `trace_id` = '%s'
                ORDER BY `startTime` ASC, `start` ASC
                """.formatted(database, escaped);
    }

    public static String serviceSeriesSql(
            String database, String service, long fromMillis, long toMillis) {
        String serviceFilter = buildServiceIdFilter(service);
        return """
                SELECT %s AS ts,
                       `service`,
                       SUM(`cnt`) AS request_cnt,
                       SUM(`error`) AS error_cnt,
                       SUM(`sumDuration`) / NULLIF(SUM(`cnt`), 0) / 1000000 AS avg_duration
                FROM %s.`metric_service`
                WHERE %s
                %s
                GROUP BY ts, `service`
                ORDER BY ts ASC
                """.formatted(metricMinuteTsSelect(), database, metricTsWhere(fromMillis, toMillis), serviceFilter);
    }

    public static String serviceTrendBucketsSql(
            String database,
            long fromMillis,
            long toMillis,
            int intervalSec,
            List<String> services) {
        return serviceTrendBucketsSql(database, fromMillis, toMillis, intervalSec, services, null);
    }

    public static String serviceTrendBucketsSql(
            String database,
            long fromMillis,
            long toMillis,
            int intervalSec,
            List<String> services,
            String serviceInstance) {
        int bucketSec = Math.max(60, intervalSec);
        String serviceFilter = buildServiceKeyOrFilter(services);
        String instanceFilter = buildServiceInstanceFilter(serviceInstance);
        return """
                SELECT %s AS bucket_epoch_sec,
                       `service`,
                       SUM(`cnt`) AS request_cnt,
                       SUM(`error`) AS error_cnt,
                       SUM(`sumDuration`) AS sum_duration_ns
                FROM %s.`metric_service`
                WHERE %s
                %s
                %s
                GROUP BY bucket_epoch_sec, `service`
                ORDER BY bucket_epoch_sec ASC, `service` ASC
                """.formatted(
                metricBucketEpochSecSelect(bucketSec),
                database,
                metricTsWhere(fromMillis, toMillis),
                serviceFilter,
                instanceFilter);
    }

    public static String httpTrendBucketsSql(
            String database,
            long fromMillis,
            long toMillis,
            int intervalSec,
            String service,
            String serviceInstance,
            String urlContains) {
        int bucketSec = Math.max(60, intervalSec);
        return httpTrendBucketsSql(
                database, fromMillis, toMillis, intervalSec, service, serviceInstance, urlContains,
                null, null, null);
    }

    public static String httpTrendBucketsSql(
            String database,
            long fromMillis,
            long toMillis,
            int intervalSec,
            String service,
            String serviceInstance,
            String urlContains,
            Integer isIn,
            Integer isOut,
            String srcServiceId) {
        java.util.List<String> serviceKeys = service == null || service.isBlank() ? null : java.util.List.of(service);
        java.util.List<String> srcKeys = srcServiceId == null || srcServiceId.isBlank() ? null : java.util.List.of(srcServiceId);
        return httpTrendBucketsSql(
                database, fromMillis, toMillis, intervalSec, serviceKeys, serviceInstance,
                urlContains, isIn, isOut, srcKeys);
    }

    public static String httpTrendBucketsSql(
            String database,
            long fromMillis,
            long toMillis,
            int intervalSec,
            java.util.Collection<String> serviceKeys,
            String serviceInstance,
            String urlContains,
            Integer isIn,
            Integer isOut,
            java.util.Collection<String> srcServiceKeys) {
        return httpTrendBucketsSql(
                database, fromMillis, toMillis, intervalSec, serviceKeys, serviceInstance,
                urlContains, isIn, isOut, srcServiceKeys, false);
    }

    public static String httpTrendBucketsSql(
            String database,
            long fromMillis,
            long toMillis,
            int intervalSec,
            java.util.Collection<String> serviceKeys,
            String serviceInstance,
            String urlContains,
            Integer isIn,
            Integer isOut,
            java.util.Collection<String> srcServiceKeys,
            boolean exactUrlMatch) {
        int bucketSec = Math.max(60, intervalSec);
        String instanceFilter = buildServiceInstanceFilter(serviceInstance);
        if (needsExpandedInboundUnion(isIn, isOut)) {
            String baseFilters = httpMetricFiltersWithKeys(
                    serviceKeys, null, null, urlContains, null, isOut, srcServiceKeys, exactUrlMatch);
            return httpTrendBucketsUnionSql(
                    database,
                    fromMillis,
                    toMillis,
                    bucketSec,
                    instanceFilter,
                    baseFilters + " AND `isIn` = '1' ",
                    baseFilters + legacyInboundEntryFilter());
        }
        String filters = httpMetricFiltersWithKeys(
                serviceKeys, null, null, urlContains, isIn, isOut, srcServiceKeys, exactUrlMatch);
        return httpTrendBucketsSingleSql(
                database, fromMillis, toMillis, bucketSec, filters, instanceFilter);
    }

    private static String httpTrendBucketsSingleSql(
            String database,
            long fromMillis,
            long toMillis,
            int bucketSec,
            String filters,
            String instanceFilter) {
        return """
                SELECT %s AS bucket_epoch_sec,
                       `service`,
                       SUM(`cnt`) AS request_cnt,
                       SUM(`error`) AS error_cnt,
                       SUM(`sumDuration`) AS sum_duration_ns
                FROM %s.`metric_service_http`
                WHERE %s
                %s
                %s
                GROUP BY bucket_epoch_sec, `service`
                ORDER BY bucket_epoch_sec ASC, `service` ASC
                """.formatted(
                metricBucketEpochSecSelect(bucketSec),
                database,
                metricTsWhere(fromMillis, toMillis),
                filters,
                instanceFilter);
    }

    /**
     * Doris aggregate tables return zero rows when OR combines predicates on key columns
     * such as {@code isIn}/{@code isOut}. Merge strict inbound and legacy root-entry branches
     * with UNION ALL instead.
     */
    private static String httpTrendBucketsUnionSql(
            String database,
            long fromMillis,
            long toMillis,
            int bucketSec,
            String instanceFilter,
            String inboundFilters,
            String legacyInboundFilters) {
        String bucketSelect = metricBucketEpochSecSelect(bucketSec);
        String tsWhere = metricTsWhere(fromMillis, toMillis);
        String branch = """
                SELECT %s AS bucket_epoch_sec,
                       `service`,
                       SUM(`cnt`) AS request_cnt,
                       SUM(`error`) AS error_cnt,
                       SUM(`sumDuration`) AS sum_duration_ns
                FROM %s.`metric_service_http`
                WHERE %s
                %s
                %s
                GROUP BY bucket_epoch_sec, `service`
                """.formatted(bucketSelect, database, tsWhere, "%s", instanceFilter);
        return """
                SELECT bucket_epoch_sec,
                       `service`,
                       SUM(request_cnt) AS request_cnt,
                       SUM(error_cnt) AS error_cnt,
                       SUM(sum_duration_ns) AS sum_duration_ns
                FROM (
                %s
                UNION ALL
                %s
                ) merged
                GROUP BY bucket_epoch_sec, `service`
                ORDER BY bucket_epoch_sec ASC, `service` ASC
                """.formatted(
                branch.formatted(inboundFilters),
                branch.formatted(legacyInboundFilters));
    }

    private static boolean needsExpandedInboundUnion(Integer isIn, Integer isOut) {
        return isIn != null && isIn == 1 && isOut == null;
    }

    private static String legacyInboundEntryFilter() {
        return " AND `isOut` = '0' AND `isIn` = '0'"
                + " AND COALESCE(NULLIF(`srcService`, ''), '') = '' ";
    }

    public static String componentTrendBucketsSql(
            String database,
            String table,
            long fromMillis,
            long toMillis,
            int intervalSec,
            String service,
            String serviceInstance,
            String resourceContains,
            Integer isIn,
            Integer isOut,
            Integer isSlow) {
        return componentTrendBucketsSql(
                database, table, fromMillis, toMillis, intervalSec,
                service, serviceInstance, resourceContains, isIn, isOut, isSlow, null);
    }

    public static String componentTrendBucketsSql(
            String database,
            String table,
            long fromMillis,
            long toMillis,
            int intervalSec,
            String service,
            String serviceInstance,
            String resourceContains,
            Integer isIn,
            Integer isOut,
            Integer isSlow,
            String srcServiceId) {
        java.util.List<String> serviceKeys = service == null || service.isBlank() ? null : java.util.List.of(service);
        java.util.List<String> srcKeys = srcServiceId == null || srcServiceId.isBlank() ? null : java.util.List.of(srcServiceId);
        return componentTrendBucketsSql(
                database, table, fromMillis, toMillis, intervalSec, serviceKeys, serviceInstance,
                resourceContains, isIn, isOut, isSlow, srcKeys);
    }

    public static String componentTrendBucketsSql(
            String database,
            String table,
            long fromMillis,
            long toMillis,
            int intervalSec,
            java.util.Collection<String> serviceKeys,
            String serviceInstance,
            String resourceContains,
            Integer isIn,
            Integer isOut,
            Integer isSlow,
            java.util.Collection<String> srcServiceKeys) {
        int bucketSec = Math.max(60, intervalSec);
        boolean expandHttpEntryInbound = DorisTableNames.METRIC_SERVICE_HTTP.equals(table);
        String filters = componentMetricFiltersWithKeys(
                serviceKeys, serviceInstance, resourceContains, null, null,
                isIn, isOut, isSlow, srcServiceKeys, expandHttpEntryInbound,
                resourceFilterMatchesSqlContent(table));
        return """
                SELECT %s AS bucket_epoch_sec,
                       `service`,
                       SUM(`cnt`) AS request_cnt,
                       SUM(`error`) AS error_cnt,
                       SUM(`sumDuration`) AS sum_duration_ns,
                       MAX(`maxDuration`) AS max_duration_ns,
                       MIN(NULLIF(`minDuration`, 0)) AS min_duration_ns,
                       %s
                FROM %s.`%s`
                WHERE %s
                %s
                GROUP BY bucket_epoch_sec, `service`
                ORDER BY bucket_epoch_sec ASC, `service` ASC
                """.formatted(
                metricBucketEpochSecSelect(bucketSec),
                componentTrendRowMetricSelect(table),
                database,
                table,
                metricTsWhere(fromMillis, toMillis),
                filters);
    }

    /**
     * Interface/resource-level trend buckets from component metric tables only.
     * Mirrors legacy {@code TraceServiceImpl.getQueryBuilder}: {@code service_id}, {@code resource},
     * {@code isIn} filters with {@code SUM(cnt)}, {@code SUM(slow)}, {@code SUM(error)}.
     */
    public static String componentResourceTrendBucketsSql(
            String database,
            String table,
            long fromMillis,
            long toMillis,
            int intervalSec,
            String serviceId,
            String serviceInstance,
            String url,
            String resource,
            Integer isIn,
            Integer isOut) {
        int bucketSec = Math.max(60, intervalSec);
        String filters = componentResourceTrendFilters(
                table, serviceId, serviceInstance, url, resource, isIn, isOut);
        return """
                SELECT %s AS bucket_epoch_sec,
                       `service`,
                       SUM(`cnt`) AS request_cnt,
                       SUM(`error`) AS error_cnt,
                       SUM(`sumDuration`) AS sum_duration_ns,
                       MAX(`maxDuration`) AS max_duration_ns,
                       MIN(NULLIF(`minDuration`, 0)) AS min_duration_ns,
                       %s,
                       %s
                FROM %s.`%s`
                WHERE %s
                %s
                GROUP BY bucket_epoch_sec, `service`
                ORDER BY bucket_epoch_sec ASC, `service` ASC
                """.formatted(
                metricBucketEpochSecSelect(bucketSec),
                componentTrendRowMetricSelect(table),
                componentTrendSlowCountSelect(table),
                database,
                table,
                metricTsWhere(fromMillis, toMillis),
                filters);
    }

    /** {@code service.trace} error trend buckets: only rows with {@code errorType = 'error'}. */
    public static String traceErrorTrendBucketsSql(
            String database,
            long fromMillis,
            long toMillis,
            int intervalSec,
            java.util.Collection<String> serviceKeys,
            String serviceInstance,
            String resourceContains) {
        int bucketSec = Math.max(60, intervalSec);
        String filters = componentMetricFiltersWithKeys(
                serviceKeys, serviceInstance, resourceContains, null, null,
                null, null, null, null, false, false)
                + " AND `errorType` = 'error' ";
        return """
                SELECT %s AS bucket_epoch_sec,
                       `service`,
                       0 AS request_cnt,
                       SUM(`error`) AS error_cnt,
                       0 AS sum_duration_ns,
                       0 AS max_duration_ns,
                       0 AS min_duration_ns,
                       0 AS sum_read_rows,
                       0 AS sum_update_rows
                FROM %s.`%s`
                WHERE %s
                %s
                GROUP BY bucket_epoch_sec, `service`
                HAVING SUM(`error`) > 0
                ORDER BY bucket_epoch_sec ASC, `service` ASC
                """.formatted(
                metricBucketEpochSecSelect(bucketSec),
                database,
                DorisTableNames.METRIC_SERVICE_TRACE,
                metricTsWhere(fromMillis, toMillis),
                filters);
    }

    /** DB metrics expose row counters; other component tables use literal zeros in SELECT. */
    private static String componentTrendRowMetricSelect(String tableName) {
        if (DorisTableNames.METRIC_SERVICE_DB.equals(tableName)) {
            return """
                    SUM(`readRows`) AS sum_read_rows,
                    SUM(`updateRows`) AS sum_update_rows""";
        }
        return """
                0 AS sum_read_rows,
                0 AS sum_update_rows""";
    }

    private static String componentTrendSlowCountSelect(String tableName) {
        if (DorisTableNames.METRIC_SERVICE_HTTP.equals(tableName)
                || DorisTableNames.METRIC_SERVICE_DB.equals(tableName)
                || DorisTableNames.METRIC_SERVICE_RPC.equals(tableName)
                || DorisTableNames.METRIC_SERVICE_REMOTE.equals(tableName)
                || DorisTableNames.METRIC_SERVICE_REDIS.equals(tableName)
                || DorisTableNames.METRIC_SERVICE_CONFIG.equals(tableName)) {
            return "SUM(`slow`) AS slow_cnt";
        }
        return "0 AS slow_cnt";
    }

    public static String serviceSummarySql(
            String database,
            long fromMillis,
            long toMillis,
            String sortField,
            String sortOrder,
            int offset,
            int size) {
        return serviceSummarySql(
                database, fromMillis, toMillis, sortField, sortOrder, offset, size, null, null, null, null);
    }

    public static String serviceSummarySql(
            String database,
            long fromMillis,
            long toMillis,
            String sortField,
            String sortOrder,
            int offset,
            int size,
            String serviceNameContains,
            java.util.Collection<String> serviceIds,
            Integer statusType,
            String listServiceCategory) {
        String orderColumn = resolveServiceSummarySortColumn(sortField);
        String direction = "asc".equalsIgnoreCase(sortOrder) ? "ASC" : "DESC";
        int safeOffset = Math.max(0, offset);
        int safeSize = Math.max(1, Math.min(size, 500));
        String whereClause = metricTsWhere(fromMillis, toMillis)
                + serviceSummaryWhereFilters(serviceNameContains, serviceIds, listServiceCategory);
        String havingClause = serviceSummaryHavingClause(statusType);
        return """
                SELECT `service`,
                       MAX(COALESCE(NULLIF(`service_id`, ''), `service`)) AS service_id,
                       SUM(`cnt`) AS request_cnt,
                       SUM(`error`) AS error_cnt,
                       SUM(`sumDuration`) AS sum_duration_ns,
                       MAX(`maxDuration`) AS max_duration_ns
                FROM %s.`metric_service`
                WHERE %s
                GROUP BY `service`
                %s
                ORDER BY %s %s, `service` ASC
                LIMIT %d OFFSET %d
                """.formatted(
                database,
                whereClause,
                havingClause,
                orderColumn,
                direction,
                safeSize,
                safeOffset);
    }

    public static String serviceSummaryCountSql(String database, long fromMillis, long toMillis) {
        return serviceSummaryCountSql(database, fromMillis, toMillis, null, null, null, null);
    }

    public static String serviceSummaryCountSql(
            String database,
            long fromMillis,
            long toMillis,
            String serviceNameContains,
            java.util.Collection<String> serviceIds,
            Integer statusType,
            String listServiceCategory) {
        String whereClause = metricTsWhere(fromMillis, toMillis)
                + serviceSummaryWhereFilters(serviceNameContains, serviceIds, listServiceCategory);
        String havingClause = serviceSummaryHavingClause(statusType);
        return """
                SELECT COUNT(*) AS total_cnt
                FROM (
                    SELECT `service`
                    FROM %s.`metric_service`
                    WHERE %s
                    GROUP BY `service`
                    %s
                ) AS service_summary
                """.formatted(database, whereClause, havingClause);
    }

    private static String serviceSummaryWhereFilters(
            String serviceNameContains,
            java.util.Collection<String> serviceIds,
            String listServiceCategory) {
        StringBuilder filters = new StringBuilder();
        if (serviceNameContains != null && !serviceNameContains.isBlank()) {
            filters.append(" AND LOWER(`service`) LIKE '%")
                    .append(escapeLiteral(serviceNameContains.toLowerCase(java.util.Locale.ROOT)))
                    .append("%' ");
        }
        if (serviceIds != null && !serviceIds.isEmpty()) {
            filters.append(buildServiceIdsInFilter(serviceIds));
        }
        if ("web".equalsIgnoreCase(listServiceCategory)) {
            filters.append(" AND `service` NOT LIKE '[%%' ");
            filters.append(serviceSummaryWebExcludeClause());
        } else if ("custom".equalsIgnoreCase(listServiceCategory)) {
            filters.append(serviceSummaryCustomIncludeClause());
        }
        return filters.toString();
    }

    private static String serviceSummaryHavingClause(Integer statusType) {
        if (statusType == null) {
            return "";
        }
        if (statusType == 1) {
            return """
                    HAVING SUM(`cnt`) = 0
                        OR (SUM(`error`) * 1.0 / NULLIF(SUM(`cnt`), 0)) < 0.05
                    """;
        }
        return """
                HAVING SUM(`cnt`) > 0
                    AND (SUM(`error`) * 1.0 / NULLIF(SUM(`cnt`), 0)) >= 0.05
                """;
    }

    private static String serviceSummaryWebExcludeClause() {
        return " AND NOT (" + serviceSummaryCustomKeywordPredicate() + ") ";
    }

    private static String serviceSummaryCustomIncludeClause() {
        return " AND (" + serviceSummaryCustomKeywordPredicate() + ") ";
    }

    private static String serviceSummaryCustomKeywordPredicate() {
        String[] keywords = {
            "gateway", "external", "third", "remote", "openapi", "feign", "dubbo"
        };
        StringBuilder clause = new StringBuilder();
        for (int i = 0; i < keywords.length; i++) {
            if (i > 0) {
                clause.append(" OR ");
            }
            clause.append("LOWER(`service`) LIKE '%").append(keywords[i]).append("%'");
        }
        return clause.toString();
    }

    /** Inbound component rollup for portal virtual-service lists ({@code dbList}, {@code cacheList}, …). */
    public static String componentServiceSummarySql(
            String database,
            String tableName,
            long fromMillis,
            long toMillis,
            String groupServiceColumn,
            String groupIdColumn,
            String typeColumn) {
        String slowExpr = slowCountSelectExpr(tableName);
        return """
                SELECT `%s` AS service,
                       MAX(COALESCE(NULLIF(`%s`, ''), `%s`)) AS service_id,
                       MAX(`%s`) AS db_type,
                       SUM(`cnt`) AS request_cnt,
                       SUM(`error`) AS error_cnt,
                       %s AS slow_cnt,
                       SUM(`sumDuration`) AS sum_duration_ns
                FROM %s.`%s`
                WHERE %s
                  AND `isIn` = '1'
                GROUP BY `%s`
                ORDER BY `%s` ASC
                """.formatted(
                groupServiceColumn,
                groupIdColumn,
                groupServiceColumn,
                typeColumn,
                slowExpr,
                database,
                tableName,
                metricTsWhere(fromMillis, toMillis),
                groupServiceColumn,
                groupServiceColumn);
    }

    public static String componentDistinctServicesSql(
            String database,
            String tableName,
            long fromMillis,
            long toMillis,
            String serviceColumn) {
        return """
                SELECT DISTINCT `%s` AS service
                FROM %s.`%s`
                WHERE %s
                  AND `isIn` = '1'
                  AND `%s` IS NOT NULL AND `%s` != ''
                ORDER BY `%s` ASC
                """.formatted(
                serviceColumn,
                database,
                tableName,
                metricTsWhere(fromMillis, toMillis),
                serviceColumn,
                serviceColumn,
                serviceColumn);
    }

    /** Inbound DB peer rollup for portal {@code POST /service/dbList}. */
    public static String dbServiceSummarySql(String database, long fromMillis, long toMillis) {
        return componentServiceSummarySql(
                database,
                DorisTableNames.METRIC_SERVICE_DB,
                fromMillis,
                toMillis,
                "service",
                "service_id",
                "dbType");
    }

    public static String dbDistinctServicesSql(String database, long fromMillis, long toMillis) {
        return componentDistinctServicesSql(
                database, DorisTableNames.METRIC_SERVICE_DB, fromMillis, toMillis, "service");
    }

    public static String mqProducerServiceSummarySql(String database, long fromMillis, long toMillis) {
        return componentServiceSummarySql(
                database,
                DorisTableNames.METRIC_SERVICE_MQ,
                fromMillis,
                toMillis,
                "service",
                "service_id",
                "type");
    }

    public static String mqConsumerServiceSummarySql(String database, long fromMillis, long toMillis) {
        return componentServiceSummarySql(
                database,
                DorisTableNames.METRIC_SERVICE_MQ,
                fromMillis,
                toMillis,
                "srcService",
                "srcServiceId",
                "type");
    }

    private static String slowCountSelectExpr(String tableName) {
        if (DorisTableNames.METRIC_SERVICE_DB.equals(tableName)
                || DorisTableNames.METRIC_SERVICE_RPC.equals(tableName)
                || DorisTableNames.METRIC_SERVICE_REMOTE.equals(tableName)) {
            return "SUM(`slowCnt`)";
        }
        return "SUM(`slow`)";
    }

    public static String distinctServicesSql(String database, long fromMillis, long toMillis) {        return """
                SELECT DISTINCT `service` AS tag_value
                FROM %s.`metric_service`
                WHERE %s
                  AND `service` IS NOT NULL AND `service` != ''
                ORDER BY tag_value ASC
                """.formatted(database, metricTsWhere(fromMillis, toMillis));
    }

    private static final String META_SERVICE_COLUMNS = """
            `id`, `name`, `service`, `service_type`, `apikey`, `type`, `technology`,
            `language`, `datasource`, `source`, `fqdn`, `container_service`, `virtual_service`,
            `describe`, `custom_tags`, `processRuntimeName`, `processRuntimeVersion`
            """;

    /** Single service row from {@code meta_service} (portal {@code /service/serviceInfo}). */
    public static String metaServiceByIdSql(String database, String serviceId) {
        return """
                SELECT %s
                FROM %s.`%s`
                WHERE `id` = '%s'
                LIMIT 1
                """.formatted(META_SERVICE_COLUMNS, database, DorisTableNames.META_SERVICE, escapeLiteral(serviceId));
    }

    /** Per-service rollup for portal {@code /service/serviceInfo}. */
    public static String serviceSummaryByServiceSql(
            String database, String service, long fromMillis, long toMillis) {
        String serviceFilter = buildServiceIdFilter(service);
        return """
                SELECT `service`,
                       SUM(`cnt`) AS request_cnt,
                       SUM(`error`) AS error_cnt,
                       SUM(`sumDuration`) AS sum_duration_ns
                FROM %s.`metric_service`
                WHERE %s
                %s
                GROUP BY `service`
                LIMIT 1
                """.formatted(database, metricTsWhere(fromMillis, toMillis), serviceFilter);
    }

    /** Virtual-service inbound rollup for portal {@code /service/serviceInfo}. */
    public static String componentInboundSummaryByServiceSql(
            String database,
            String tableName,
            String serviceId,
            long fromMillis,
            long toMillis,
            String typeColumn) {
        String slowExpr = slowCountSelectExpr(tableName);
        String serviceFilter = buildServiceIdFilter(serviceId);
        return """
                SELECT `service`,
                       MAX(COALESCE(NULLIF(`service_id`, ''), `service`)) AS service_id,
                       MAX(`%s`) AS db_type,
                       SUM(`cnt`) AS request_cnt,
                       SUM(`error`) AS error_cnt,
                       %s AS slow_cnt,
                       SUM(`sumDuration`) AS sum_duration_ns
                FROM %s.`%s`
                WHERE %s
                  AND `isIn` = '1'
                %s
                GROUP BY `service`
                LIMIT 1
                """.formatted(
                typeColumn,
                slowExpr,
                database,
                tableName,
                metricTsWhere(fromMillis, toMillis),
                serviceFilter);
    }

    /** Whether a metric table has traffic for the service (portal componentTypes). */
    public static String serviceMetricHasDataSql(
            String database, String tableName, String service, long fromMillis, long toMillis) {
        String serviceFilter = componentMetricServiceFilter(tableName, service);
        String inboundFilter = isInboundComponentTable(tableName) ? " AND `isIn` = '1' " : "";
        String aggregate = DorisTableNames.METRIC_JVM.equals(tableName)
                ? "COUNT(*)"
                : "SUM(`cnt`)";
        return """
                SELECT %s AS total_cnt
                FROM %s.`%s`
                WHERE %s
                %s
                %s
                """.formatted(
                aggregate,
                database,
                tableName,
                metricTsWhere(fromMillis, toMillis),
                serviceFilter,
                inboundFilter);
    }

    private static boolean isInboundComponentTable(String tableName) {
        return DorisTableNames.METRIC_SERVICE_DB.equals(tableName)
                || DorisTableNames.METRIC_SERVICE_MQ.equals(tableName)
                || DorisTableNames.METRIC_SERVICE_REDIS.equals(tableName)
                || DorisTableNames.METRIC_SERVICE_RPC.equals(tableName)
                || DorisTableNames.METRIC_SERVICE_REMOTE.equals(tableName)
                || DorisTableNames.METRIC_SERVICE_CONFIG.equals(tableName)
                || DorisTableNames.METRIC_SERVICE_HTTP.equals(tableName);
    }

    private static String componentMetricServiceFilter(String tableName, String service) {
        if (service == null || service.isBlank()) {
            return "";
        }
        if (!isInboundComponentTable(tableName)) {
            return buildServiceIdFilter(service);
        }
        return buildComponentTrafficFilter(service);
    }

    private static String buildComponentTrafficFilter(String service) {
        if (service == null || service.isBlank()) {
            return "";
        }
        String serviceId = PortalServiceIdResolver.normalize(service.trim());
        if (serviceId.isBlank()) {
            return "";
        }
        String escaped = escapeLiteral(serviceId);
        return " AND (`service_id` = '" + escaped + "' OR `srcServiceId` = '" + escaped + "') ";
    }

    /** Outbound DB peers for portal {@code /service/getServiceInstanceRelations}. */
    public static String dbDownstreamSummarySql(
            String database,
            java.util.Collection<String> srcServiceKeys,
            long fromMillis,
            long toMillis,
            int limit) {
        return componentOutboundDownstreamSummarySql(
                database,
                DorisTableNames.METRIC_SERVICE_DB,
                srcServiceKeys,
                null,
                1,
                false,
                fromMillis,
                toMillis,
                limit);
    }

    /** Inbound web-service peers called by {@code srcServiceKeys} (HTTP/RPC downstream). */
    public static String componentWebDownstreamSummarySql(
            String database,
            String tableName,
            java.util.Collection<String> srcServiceKeys,
            long fromMillis,
            long toMillis,
            int limit) {
        return componentWebDownstreamDirectionalSummarySql(
                database, tableName, srcServiceKeys, 1, null, fromMillis, toMillis, limit);
    }

    /** Outbound web-service peers called by {@code srcServiceKeys} (HTTP/RPC downstream). */
    public static String componentWebDownstreamOutboundSummarySql(
            String database,
            String tableName,
            java.util.Collection<String> srcServiceKeys,
            long fromMillis,
            long toMillis,
            int limit) {
        return componentWebDownstreamDirectionalSummarySql(
                database, tableName, srcServiceKeys, null, 1, fromMillis, toMillis, limit);
    }

    private static String componentWebDownstreamDirectionalSummarySql(
            String database,
            String tableName,
            java.util.Collection<String> srcServiceKeys,
            Integer isIn,
            Integer isOut,
            long fromMillis,
            long toMillis,
            int limit) {
        String filters = componentMetricFiltersWithKeys(
                null, null, null, null, null, isIn, isOut, null, srcServiceKeys);
        return """
                SELECT COALESCE(NULLIF(MAX(`service_id`), ''), `service`) AS service_id,
                       `service`,
                       SUM(`cnt`) AS request_cnt,
                       SUM(`error`) AS error_cnt,
                       SUM(`sumDuration`) / NULLIF(SUM(`cnt`), 0) / 1000000 AS avg_duration
                FROM %s.`%s`
                WHERE %s
                %s
                  AND `service` IS NOT NULL AND `service` != ''
                  AND `service` NOT LIKE '[%%'
                GROUP BY `service`
                ORDER BY request_cnt DESC
                LIMIT %d
                """.formatted(
                database,
                tableName,
                metricTsWhere(fromMillis, toMillis),
                filters,
                Math.max(1, Math.min(limit, 200)));
    }

    /** Inbound web-service callers of {@code serviceKeys} (HTTP/RPC upstream). */
    public static String componentWebUpstreamSummarySql(
            String database,
            String tableName,
            java.util.Collection<String> serviceKeys,
            long fromMillis,
            long toMillis,
            int limit) {
        return componentWebUpstreamDirectionalSummarySql(
                database, tableName, serviceKeys, 1, null, fromMillis, toMillis, limit);
    }

    /** Outbound web-service callers of {@code serviceKeys} (HTTP/RPC upstream). */
    public static String componentWebUpstreamOutboundSummarySql(
            String database,
            String tableName,
            java.util.Collection<String> serviceKeys,
            long fromMillis,
            long toMillis,
            int limit) {
        return componentWebUpstreamDirectionalSummarySql(
                database, tableName, serviceKeys, null, 1, fromMillis, toMillis, limit);
    }

    /** Inbound callers of virtual/outbound component services (DB/Redis/MQ/Remote/ES/Config). */
    public static String componentOutboundUpstreamSummarySql(
            String database,
            String tableName,
            java.util.Collection<String> serviceKeys,
            Integer isIn,
            Integer isOut,
            long fromMillis,
            long toMillis,
            int limit) {
        return componentOutboundUpstreamSummarySql(
                database, tableName, serviceKeys, isIn, isOut, fromMillis, toMillis, limit, "");
    }

    public static String componentOutboundUpstreamSummarySql(
            String database,
            String tableName,
            java.util.Collection<String> serviceKeys,
            Integer isIn,
            Integer isOut,
            long fromMillis,
            long toMillis,
            int limit,
            String extraFilter) {
        String filters = componentMetricFiltersWithKeys(
                serviceKeys, null, null, null, null, isIn, isOut, null, null);
        String suffix = extraFilter != null ? extraFilter : "";
        return """
                SELECT COALESCE(NULLIF(MAX(`srcServiceId`), ''), `srcService`) AS service_id,
                       `srcService` AS service,
                       SUM(`cnt`) AS request_cnt,
                       SUM(`error`) AS error_cnt,
                       SUM(`sumDuration`) / NULLIF(SUM(`cnt`), 0) / 1000000 AS avg_duration
                FROM %s.`%s`
                WHERE %s
                %s
                  AND `srcService` IS NOT NULL AND `srcService` != ''
                  AND `srcService` NOT LIKE '[%%'
                %s
                GROUP BY `srcService`, `srcServiceId`
                ORDER BY request_cnt DESC
                LIMIT %d
                """.formatted(
                database,
                tableName,
                metricTsWhere(fromMillis, toMillis),
                filters,
                suffix,
                Math.max(1, Math.min(limit, 200)));
    }

    private static String componentWebUpstreamDirectionalSummarySql(
            String database,
            String tableName,
            java.util.Collection<String> serviceKeys,
            Integer isIn,
            Integer isOut,
            long fromMillis,
            long toMillis,
            int limit) {
        String filters = componentMetricFiltersWithKeys(
                serviceKeys, null, null, null, null, isIn, isOut, null, null);
        return """
                SELECT COALESCE(NULLIF(MAX(`srcServiceId`), ''), `srcService`) AS service_id,
                       `srcService` AS service,
                       SUM(`cnt`) AS request_cnt,
                       SUM(`error`) AS error_cnt,
                       SUM(`sumDuration`) / NULLIF(SUM(`cnt`), 0) / 1000000 AS avg_duration
                FROM %s.`%s`
                WHERE %s
                %s
                  AND `srcService` IS NOT NULL AND `srcService` != ''
                  AND `srcService` NOT LIKE '[%%'
                GROUP BY `srcService`, `srcServiceId`
                ORDER BY request_cnt DESC
                LIMIT %d
                """.formatted(
                database,
                tableName,
                metricTsWhere(fromMillis, toMillis),
                filters,
                Math.max(1, Math.min(limit, 200)));
    }

    public static String componentOutboundDownstreamSummarySql(
            String database,
            String tableName,
            java.util.Collection<String> srcServiceKeys,
            Integer isIn,
            Integer isOut,
            boolean virtualOnly,
            long fromMillis,
            long toMillis,
            int limit) {
        return componentOutboundDownstreamSummarySql(
                database, tableName, srcServiceKeys, isIn, isOut, virtualOnly, fromMillis, toMillis, limit, "");
    }

    /** Outbound component peers (DB/Redis/MQ/Remote/ES/Config) for portal service relations. */
    public static String componentOutboundDownstreamSummarySql(
            String database,
            String tableName,
            java.util.Collection<String> srcServiceKeys,
            Integer isIn,
            Integer isOut,
            boolean virtualOnly,
            long fromMillis,
            long toMillis,
            int limit,
            String extraFilter) {
        String filters = componentMetricFiltersWithKeys(
                null, null, null, null, null, isIn, isOut, null, srcServiceKeys);
        String virtualFilter = virtualOnly ? " AND `service` LIKE '[%' " : "";
        String suffix = extraFilter != null ? extraFilter : "";
        return """
                SELECT COALESCE(NULLIF(MAX(`service_id`), ''), `service`) AS service_id,
                       `service`,
                       SUM(`cnt`) AS request_cnt,
                       SUM(`error`) AS error_cnt,
                       SUM(`sumDuration`) / NULLIF(SUM(`cnt`), 0) / 1000000 AS avg_duration
                FROM %s.`%s`
                WHERE %s
                %s
                  AND `service` IS NOT NULL AND `service` != ''
                %s
                %s
                GROUP BY `service`
                ORDER BY request_cnt DESC
                LIMIT %d
                """.formatted(
                database,
                tableName,
                metricTsWhere(fromMillis, toMillis),
                filters,
                virtualFilter,
                suffix,
                Math.max(1, Math.min(limit, 200)));
    }

    /** Inbound RPC peers called by {@code srcServiceKeys} (portal service relation downstream). */
    public static String rpcDownstreamSummarySql(
            String database,
            java.util.Collection<String> srcServiceKeys,
            long fromMillis,
            long toMillis,
            int limit) {
        return componentWebDownstreamSummarySql(
                database,
                DorisTableNames.METRIC_SERVICE_RPC,
                srcServiceKeys,
                fromMillis,
                toMillis,
                limit);
    }

    /** Inbound RPC callers of {@code serviceKeys} (portal service relation upstream). */
    public static String rpcUpstreamSummarySql(
            String database,
            java.util.Collection<String> serviceKeys,
            long fromMillis,
            long toMillis,
            int limit) {
        return componentWebUpstreamSummarySql(
                database,
                DorisTableNames.METRIC_SERVICE_RPC,
                serviceKeys,
                fromMillis,
                toMillis,
                limit);
    }

    public static String dbMetricDistinctSql(
            String database,
            String column,
            long fromMillis,
            long toMillis,
            int limit) {
        return metricDistinctSql(database, DorisTableNames.METRIC_SERVICE_DB, column, fromMillis, toMillis, limit);
    }

    public static String httpMetricDistinctSql(
            String database,
            String column,
            long fromMillis,
            long toMillis,
            int limit) {
        return metricDistinctSql(database, DorisTableNames.METRIC_SERVICE_HTTP, column, fromMillis, toMillis, limit);
    }

    public static String metricDistinctSql(
            String database,
            String table,
            String column,
            long fromMillis,
            long toMillis,
            int limit) {
        String safeColumn = switch (column) {
            case "srcService" -> "`srcService`";
            case "service" -> "`service`";
            default -> "`service`";
        };
        return """
                SELECT DISTINCT %s AS group_value
                FROM %s.`%s`
                WHERE %s
                  AND %s IS NOT NULL
                  AND %s != ''
                ORDER BY group_value ASC
                LIMIT %d
                """.formatted(
                safeColumn,
                database,
                table,
                metricTsWhere(fromMillis, toMillis),
                safeColumn,
                safeColumn,
                Math.max(1, Math.min(limit, 500)));
    }

    public static String dbEndpointSummarySql(
            String database,
            java.util.Collection<String> serviceKeys,
            long fromMillis,
            long toMillis,
            int limit,
            String resourceContains,
            String sqlOperation,
            String sqlDatabase,
            Integer isIn,
            Integer isOut,
            java.util.Collection<String> srcServiceKeys) {
        String filters = componentMetricFiltersWithKeys(
                serviceKeys, null, resourceContains, sqlOperation, sqlDatabase, isIn, isOut, null, srcServiceKeys,
                false, true);
        return """
                SELECT COALESCE(NULLIF(`service_id`, ''), `service`) AS service_id,
                       `service`,
                       COALESCE(NULLIF(`sqlContent`, ''), `resource`) AS resource,
                       `sqlOperation`, `dbType`, `sqlDatabase`,
                       SUM(`cnt`) AS request_cnt,
                       SUM(`error`) AS error_cnt,
                       SUM(`sumDuration`) / NULLIF(SUM(`cnt`), 0) / 1000000 AS avg_duration,
                       SUM(`readRows`) AS sum_read_rows,
                       SUM(`updateRows`) AS sum_update_rows
                FROM %s.`metric_service_db`
                WHERE %s
                %s
                GROUP BY `service_id`, `service`, `sqlContent`, `resource`, `sqlOperation`, `dbType`, `sqlDatabase`
                ORDER BY request_cnt DESC
                LIMIT %d
                """.formatted(
                database,
                metricTsWhere(fromMillis, toMillis),
                filters,
                Math.max(1, Math.min(limit, 500)));
    }

    /** Slow SQL rollup for portal {@code POST /service/slowSqlTopList}. */
    public static String dbSlowSqlTopSummarySql(
            String database,
            java.util.Collection<String> serviceKeys,
            long fromMillis,
            long toMillis,
            int limit,
            String resourceContains,
            String serviceInstance,
            Integer isIn,
            Integer isOut,
            Integer isSlow,
            java.util.Collection<String> srcServiceKeys) {
        String filters = componentMetricFiltersWithKeys(
                serviceKeys, serviceInstance, resourceContains, null, null,
                isIn, isOut, isSlow, srcServiceKeys, false, true);
        return """
                SELECT COALESCE(NULLIF(`sqlContent`, ''), `resource`) AS resource,
                       SUM(`cnt`) AS request_cnt,
                       SUM(`error`) AS error_cnt,
                       SUM(`sumDuration`) / NULLIF(SUM(`cnt`), 0) AS avg_time_ns,
                       MAX(`maxDuration`) AS max_duration_ns,
                       MIN(NULLIF(`minDuration`, 0)) AS min_duration_ns,
                       COUNT(DISTINCT COALESCE(NULLIF(`srcServiceId`, ''), `srcService`)) AS src_service_cnt
                FROM %s.`metric_service_db`
                WHERE %s
                %s
                GROUP BY COALESCE(NULLIF(`sqlContent`, ''), `resource`)
                ORDER BY request_cnt DESC
                LIMIT %d
                """.formatted(
                database,
                metricTsWhere(fromMillis, toMillis),
                filters,
                Math.max(1, Math.min(limit, 500)));
    }

    /**
     * Endpoint rollup for portal {@code /service/call_endpoints} on component metric tables
     * (RPC, Redis, MQ, Config, Remote). HTTP and DB keep dedicated builders.
     */
    public static String componentEndpointSummarySql(
            String database,
            String tableName,
            java.util.Collection<String> serviceKeys,
            long fromMillis,
            long toMillis,
            int limit,
            String resourceContains,
            Integer isIn,
            Integer isOut,
            java.util.Collection<String> srcServiceKeys) {
        ComponentEndpointSqlSpec spec = componentEndpointSqlSpec(tableName);
        String filters = componentMetricFiltersWithKeys(
                serviceKeys, null, resourceContains, null, null, isIn, isOut, null, srcServiceKeys,
                false, resourceFilterMatchesSqlContent(tableName));
        return """
                SELECT COALESCE(NULLIF(`service_id`, ''), `service`) AS service_id,
                       `service`,
                       %s AS resource,
                       %s
                       SUM(`cnt`) AS request_cnt,
                       SUM(`error`) AS error_cnt,
                       SUM(`sumDuration`) / NULLIF(SUM(`cnt`), 0) / 1000000 AS avg_duration,
                       %s
                FROM %s.`%s`
                WHERE %s
                %s
                GROUP BY `service_id`, `service`, %s
                ORDER BY request_cnt DESC
                LIMIT %d
                """.formatted(
                spec.resourceExpr(),
                spec.tagSelectColumns(),
                spec.aggregateSelectColumns(),
                database,
                tableName,
                metricTsWhere(fromMillis, toMillis),
                filters,
                spec.groupByColumns(),
                Math.max(1, Math.min(limit, 500)));
    }

    /** Directional peer-pair rollup for portal {@code /service/call_info}. */
    public static String componentCallStatsSummarySql(
            String database,
            String tableName,
            java.util.Collection<String> serviceKeys,
            java.util.Collection<String> srcServiceKeys,
            long fromMillis,
            long toMillis,
            String resourceContains,
            Integer isIn,
            Integer isOut) {
        String filters = componentMetricFiltersWithKeys(
                serviceKeys, null, resourceContains, null, null, isIn, isOut, null, srcServiceKeys,
                false, resourceFilterMatchesSqlContent(tableName));
        return """
                SELECT SUM(`cnt`) AS request_cnt,
                       SUM(`error`) AS error_cnt,
                       SUM(`sumDuration`) AS sum_duration_ns
                FROM %s.`%s`
                WHERE %s
                %s
                """.formatted(
                database,
                tableName,
                metricTsWhere(fromMillis, toMillis),
                filters);
    }

    /** Directional peer-pair rollup for portal {@code /service/call_info} on HTTP metrics. */
    public static String httpCallStatsSummarySql(
            String database,
            java.util.Collection<String> serviceKeys,
            java.util.Collection<String> srcServiceKeys,
            long fromMillis,
            long toMillis,
            String resourceContains,
            Integer isIn,
            Integer isOut) {
        String filters = httpMetricFiltersWithKeys(
                serviceKeys, null, null, resourceContains, isIn, isOut, srcServiceKeys);
        return """
                SELECT SUM(`cnt`) AS request_cnt,
                       SUM(`error`) AS error_cnt,
                       SUM(`sumDuration`) AS sum_duration_ns
                FROM %s.`metric_service_http`
                WHERE %s
                %s
                """.formatted(
                database,
                metricTsWhere(fromMillis, toMillis),
                filters);
    }

    /** RPC endpoint rollup (portal {@code /service/call_endpoints}, {@code service.rpc}). */
    public static String rpcEndpointSummarySql(
            String database,
            java.util.Collection<String> serviceKeys,
            long fromMillis,
            long toMillis,
            int limit,
            String resourceContains,
            Integer isIn,
            Integer isOut,
            java.util.Collection<String> srcServiceKeys) {
        return componentEndpointSummarySql(
                database,
                DorisTableNames.METRIC_SERVICE_RPC,
                serviceKeys,
                fromMillis,
                toMillis,
                limit,
                resourceContains,
                isIn,
                isOut,
                srcServiceKeys);
    }

    private record ComponentEndpointSqlSpec(
            String resourceExpr,
            String tagSelectColumns,
            String aggregateSelectColumns,
            String groupByColumns) {
    }

    private static ComponentEndpointSqlSpec componentEndpointSqlSpec(String tableName) {
        if (DorisTableNames.METRIC_SERVICE_RPC.equals(tableName)) {
            return new ComponentEndpointSqlSpec(
                    "`resource`",
                    """
                            `type`, `statusCode`,
                            """,
                    """
                            SUM(`reqBodyLength`) AS sum_req_body_length,
                            SUM(`respBodyLength`) AS sum_resp_body_length,
                            0 AS sum_read_rows,
                            0 AS sum_update_rows,
                            0 AS sum_delay,
                            0 AS sum_mq_body_length
                            """,
                    "`resource`, `type`, `statusCode`");
        }
        if (DorisTableNames.METRIC_SERVICE_REDIS.equals(tableName)) {
            return new ComponentEndpointSqlSpec(
                    "`resource`",
                    "`command`, ",
                    """
                            SUM(`reqBodyLength`) AS sum_req_body_length,
                            SUM(`respBodyLength`) AS sum_resp_body_length,
                            0 AS sum_read_rows,
                            0 AS sum_update_rows,
                            0 AS sum_delay,
                            0 AS sum_mq_body_length
                            """,
                    "`resource`, `command`");
        }
        if (DorisTableNames.METRIC_SERVICE_MQ.equals(tableName)) {
            return new ComponentEndpointSqlSpec(
                    "`resource`",
                    """
                            `topic`, `group`, `partition`, `type`, `broker`,
                            """,
                    """
                            0 AS sum_req_body_length,
                            0 AS sum_resp_body_length,
                            0 AS sum_read_rows,
                            0 AS sum_update_rows,
                            SUM(`delay`) AS sum_delay,
                            SUM(`mqBodyLength`) AS sum_mq_body_length
                            """,
                    "`resource`, `topic`, `group`, `partition`, `type`, `broker`");
        }
        if (DorisTableNames.METRIC_SERVICE_CONFIG.equals(tableName)) {
            return new ComponentEndpointSqlSpec(
                    "`resource`",
                    """
                            `operation`, `config.type` AS config_type,
                            """,
                    """
                            0 AS sum_req_body_length,
                            0 AS sum_resp_body_length,
                            0 AS sum_read_rows,
                            0 AS sum_update_rows,
                            0 AS sum_delay,
                            0 AS sum_mq_body_length
                            """,
                    "`resource`, `operation`, `config.type`");
        }
        if (DorisTableNames.METRIC_SERVICE_REMOTE.equals(tableName)) {
            return new ComponentEndpointSqlSpec(
                    "`resource`",
                    "`remoteType`, ",
                    """
                            SUM(`reqBodyLength`) AS sum_req_body_length,
                            SUM(`respBodyLength`) AS sum_resp_body_length,
                            0 AS sum_read_rows,
                            0 AS sum_update_rows,
                            0 AS sum_delay,
                            0 AS sum_mq_body_length
                            """,
                    "`resource`, `remoteType`");
        }
        throw new IllegalArgumentException("Unsupported component endpoint table: " + tableName);
    }

    /** All registered services from {@code meta_service} (portal {@code /service/basicAllServices}). */
    public static String metaServicesSql(String database, String serviceNameContains) {
        StringBuilder sql = new StringBuilder("""
                SELECT %s
                FROM %s.`%s`
                WHERE `id` IS NOT NULL AND `id` != ''
                """.formatted(META_SERVICE_COLUMNS, database, DorisTableNames.META_SERVICE));
        if (serviceNameContains != null && !serviceNameContains.isBlank()) {
            String pattern = escapeLiteral(serviceNameContains);
            sql.append(" AND (`name` LIKE '%").append(pattern)
                    .append("%' OR `service` LIKE '%").append(pattern)
                    .append("%' OR `id` LIKE '%").append(pattern).append("%') ");
        }
        sql.append(" ORDER BY `name` ASC, `id` ASC ");
        return sql.toString();
    }

    private static String buildServiceInstanceFilter(String serviceInstance) {
        if (serviceInstance == null || serviceInstance.isBlank()) {
            return "";
        }
        return " AND `service_instance` = '" + serviceInstance.replace("'", "''") + "' ";
    }

    private static String resolveServiceSummarySortColumn(String sortField) {
        if (sortField == null || sortField.isBlank()) {
            return "request_cnt";
        }
        return switch (sortField) {
            case "errCnt", "errorCnt" -> "error_cnt";
            case "errRate" -> "error_cnt * 1.0 / NULLIF(request_cnt, 0)";
            case "avgLatency", "avgTime" -> "sum_duration_ns / NULLIF(request_cnt, 0)";
            case "maxLatency", "maxDuration" -> "max_duration_ns";
            case "reqRate", "callCnt", "reqCount", "lastMinReqRate" -> "request_cnt";
            default -> "request_cnt";
        };
    }

    public static String serviceErrorRateSql(
            String database, String service, long fromMillis, long toMillis) {
        String serviceFilter = buildServiceIdFilter(service);
        return """
                SELECT SUM(`error`) AS error_cnt, SUM(`cnt`) AS total_cnt
                FROM %s.`metric_service`
                WHERE %s
                %s
                """.formatted(database, metricTsWhere(fromMillis, toMillis), serviceFilter);
    }

    /** Service-to-service edges from {@code metric_service_http} outbound traffic. */
    public static String topologyEdgesSql(String database, long fromMillis, long toMillis, int limit) {
        return topologyMetricEdgesSql(
                database, DorisTableNames.METRIC_SERVICE_HTTP, fromMillis, toMillis, limit, null, 1, false);
    }

    /**
     * Aggregated edges between real services from {@code metric_service_http} / {@code metric_service_rpc}.
     */
    public static String globalTopologyPeerEdgesSql(
            String database,
            String tableName,
            long fromMillis,
            long toMillis,
            int limit,
            Integer isIn,
            Integer isOut) {
        return globalTopologyComponentEdgesSql(
                database, tableName, fromMillis, toMillis, limit, isIn, isOut, false, false);
    }

    /**
     * Aggregated edges from virtual-service component metric tables
     * ({@code service.db}, {@code service.redis}, {@code service.remote}, …): real {@code srcService}
     * to virtual {@code service} destination.
     */
    public static String globalTopologyVirtualEdgesSql(
            String database,
            String tableName,
            long fromMillis,
            long toMillis,
            int limit,
            Integer isIn,
            Integer isOut,
            boolean virtualDestinationOnly) {
        return globalTopologyComponentEdgesSql(
                database, tableName, fromMillis, toMillis, limit, isIn, isOut, true, virtualDestinationOnly);
    }

    private static String globalTopologyComponentEdgesSql(
            String database,
            String tableName,
            long fromMillis,
            long toMillis,
            int limit,
            Integer isIn,
            Integer isOut,
            boolean allowVirtualDestination,
            boolean virtualDestinationOnly) {
        StringBuilder directionFilters = new StringBuilder();
        appendMetricIsInFilter(directionFilters, isIn, true);
        if (isOut != null) {
            directionFilters.append(" AND `isOut` = '").append(isOut).append("' ");
        }
        String dstNameFilter = allowVirtualDestination ? "" : " AND `service` NOT LIKE '[%' ";
        String virtualOnlyFilter = virtualDestinationOnly ? " AND `service` LIKE '[%' " : "";
        return """
                SELECT MAX(`srcService`) AS src_service,
                       COALESCE(NULLIF(MAX(`srcServiceId`), ''), MAX(`srcService`)) AS src_service_id,
                       MAX(`service`) AS dst_service,
                       COALESCE(NULLIF(MAX(`service_id`), ''), MAX(`service`)) AS dst_service_id,
                       SUM(`cnt`) AS call_cnt,
                       SUM(`error`) AS error_cnt,
                       SUM(`sumDuration`) / NULLIF(SUM(`cnt`), 0) / 1000000 AS avg_duration
                FROM %s.`%s`
                WHERE %s
                %s
                  AND `srcService` IS NOT NULL AND `srcService` != ''
                  AND `service` IS NOT NULL AND `service` != ''
                  AND `srcService` NOT LIKE '[%%'
                  %s
                  %s
                  AND `srcService` != `service`
                GROUP BY `srcService`, `srcServiceId`, `service`, `service_id`
                ORDER BY call_cnt DESC
                LIMIT %d
                """.formatted(
                database,
                tableName,
                metricTsWhere(fromMillis, toMillis),
                directionFilters,
                dstNameFilter,
                virtualOnlyFilter,
                Math.max(1, Math.min(limit, 500)));
    }

    public static String serviceFlowSql(
            String database, String service, long fromMillis, long toMillis, int limit) {
        return """
                SELECT `parentService` AS src_service,
                       MAX(COALESCE(NULLIF(`parentServiceId`, ''), `parentService`)) AS src_service_id,
                       `service` AS dst_service,
                       MAX(COALESCE(NULLIF(`service_id`, ''), `service`)) AS dst_service_id,
                       SUM(`cnt`) AS call_cnt,
                       SUM(`error`) AS error_cnt,
                       SUM(`sumDuration`) / NULLIF(SUM(`cnt`), 0) / 1000000 AS avg_duration
                FROM %s.`metric_service_flow`
                WHERE %s
                %s
                GROUP BY `parentService`, `service`
                ORDER BY call_cnt DESC
                LIMIT %d
                """.formatted(
                database,
                metricTsWhere(fromMillis, toMillis),
                buildFlowServiceFilter(service),
                Math.max(1, Math.min(limit, 500)));
    }

    public static String serviceFlowEntryPathIdsSql(String database, long fromMillis, long toMillis, String serviceFilter) {
        return """
                SELECT DISTINCT `entryPathId` AS entry_path_id
                FROM %s.`metric_service_flow`
                WHERE %s
                  AND `entryPathId` IS NOT NULL AND `entryPathId` != ''
                  AND (`parentPathId` IS NULL OR `parentPathId` = '')
                %s
                LIMIT 500
                """.formatted(
                database,
                metricTsWhere(fromMillis, toMillis),
                serviceFilter == null ? "" : serviceFilter);
    }

    public static String serviceFlowEntryPointsSql(
            String database, long fromMillis, long toMillis, java.util.Collection<String> entryPathIds) {
        if (entryPathIds == null || entryPathIds.isEmpty()) {
            return """
                    SELECT '' AS service, '' AS service_id, '' AS entry_path_id
                    FROM %s.`metric_service_flow`
                    WHERE 1 = 0
                    """.formatted(database);
        }
        String inClause = entryPathIds.stream()
                .map(MetricQueryBuilder::escapeLiteral)
                .map(value -> "'" + value + "'")
                .collect(java.util.stream.Collectors.joining(", "));
        return """
                SELECT MAX(NULLIF(`service`, '')) AS service,
                       MAX(NULLIF(`service_id`, '')) AS service_id,
                       `entryPathId` AS entry_path_id,
                       SUM(`cnt`) AS call_cnt
                FROM %s.`metric_service_flow`
                WHERE %s
                  AND `entryPathId` IN (%s)
                  AND (`parentPathId` IS NULL OR `parentPathId` = '')
                GROUP BY `entryPathId`
                HAVING MAX(NULLIF(`service`, '')) IS NOT NULL
                    OR MAX(NULLIF(`service_id`, '')) IS NOT NULL
                ORDER BY call_cnt DESC
                LIMIT 500
                """.formatted(database, metricTsWhere(fromMillis, toMillis), inClause);
    }

    public static String serviceFlowEntryInterfacePathIdsSql(
            String database,
            long fromMillis,
            long toMillis,
            String entryPathId,
            String resource) {
        return """
                SELECT DISTINCT `entryInterfacePathId` AS entry_interface_path_id
                FROM %s.`metric_service_flow`
                WHERE %s
                  AND `entryPathId` = '%s'
                  AND `resource` = '%s'
                  AND `entryInterfacePathId` IS NOT NULL AND `entryInterfacePathId` != ''
                LIMIT 500
                """.formatted(
                database,
                metricTsWhere(fromMillis, toMillis),
                escapeLiteral(entryPathId),
                escapeLiteral(resource));
    }

    public static String multipleServiceFlowSql(
            String database,
            long fromMillis,
            long toMillis,
            String entryPathId,
            java.util.Collection<String> entryInterfacePathIds) {
        StringBuilder filters = new StringBuilder();
        filters.append(" AND `entryPathId` = '").append(escapeLiteral(entryPathId)).append("' ");
        if (entryInterfacePathIds != null && !entryInterfacePathIds.isEmpty()) {
            String inClause = entryInterfacePathIds.stream()
                    .map(MetricQueryBuilder::escapeLiteral)
                    .map(value -> "'" + value + "'")
                    .collect(java.util.stream.Collectors.joining(", "));
            filters.append(" AND `entryInterfacePathId` IN (").append(inClause).append(") ");
        }
        return """
                SELECT `pathId` AS path_id,
                       `parentPathId` AS parent_path_id,
                       MAX(`service`) AS service,
                       MAX(COALESCE(NULLIF(`service_id`, ''), `service`)) AS service_id,
                       MAX(`resource`) AS resource,
                       MAX(`isIn`) AS is_in,
                       SUM(`cnt`) AS call_cnt,
                       SUM(`error`) AS error_cnt,
                       SUM(`srcCall`) AS src_call,
                       SUM(`sumDuration`) AS sum_duration
                FROM %s.`metric_service_flow`
                WHERE %s
                %s
                GROUP BY `pathId`, `parentPathId`, `service`, `service_id`, `resource`, `isIn`
                LIMIT 5000
                """.formatted(database, metricTsWhere(fromMillis, toMillis), filters);
    }

    public static String serviceFlowEntryServiceFilter(String serviceId, String serviceName, String resource) {
        StringBuilder filters = new StringBuilder();
        if (serviceId != null && !serviceId.isBlank()) {
            filters.append(buildServiceIdFilter(serviceId));
        }
        if (serviceName != null && !serviceName.isBlank()) {
            String escaped = escapeLiteral(serviceName);
            filters.append(" AND `service` = '").append(escaped).append("' ");
        }
        if (resource != null && !resource.isBlank()) {
            filters.append(" AND `resource` = '").append(escapeLiteral(resource)).append("' ");
        }
        return filters.toString();
    }

    public static String serviceRequestCountSql(
            String database, String service, long fromMillis, long toMillis) {
        String serviceFilter = buildServiceIdFilter(service);
        return """
                SELECT SUM(`cnt`) AS total_cnt
                FROM %s.`metric_service`
                WHERE %s
                %s
                """.formatted(database, metricTsWhere(fromMillis, toMillis), serviceFilter);
    }

    /** Real-service to virtual-service edges from component metric tables (outbound). */
    public static String topologyMiddlewareEdgesSql(String database, long fromMillis, long toMillis, int limit) {
        return topologyMetricEdgesSql(
                database, DorisTableNames.METRIC_SERVICE_DB, fromMillis, toMillis, limit, null, 1, true);
    }

    private static String topologyMetricEdgesSql(
            String database,
            String tableName,
            long fromMillis,
            long toMillis,
            int limit,
            Integer isIn,
            Integer isOut,
            boolean virtualDestinationOnly) {
        StringBuilder directionFilters = new StringBuilder();
        appendMetricIsInFilter(directionFilters, isIn, true);
        if (isOut != null) {
            directionFilters.append(" AND `isOut` = '").append(isOut).append("' ");
        }
        String virtualOnlyFilter = virtualDestinationOnly ? " AND `service` LIKE '[%' " : " AND `service` NOT LIKE '[%' ";
        return """
                SELECT MAX(`srcService`) AS srcService,
                       MAX(`service`) AS dstService,
                       SUM(`cnt`) AS call_cnt,
                       SUM(`error`) AS error_cnt
                FROM %s.`%s`
                WHERE %s
                %s
                  AND `srcService` IS NOT NULL AND `srcService` != ''
                  AND `service` IS NOT NULL AND `service` != ''
                  AND `srcService` NOT LIKE '[%%'
                  %s
                  AND `srcService` != `service`
                GROUP BY `srcService`, `srcServiceId`, `service`, `service_id`
                ORDER BY call_cnt DESC
                LIMIT %d
                """.formatted(
                database,
                tableName,
                metricTsWhere(fromMillis, toMillis),
                directionFilters,
                virtualOnlyFilter,
                Math.max(1, Math.min(limit, 500)));
    }

    public static String httpEndpointSummarySql(
            String database, String service, long fromMillis, long toMillis, int limit) {
        return httpEndpointSummarySql(database, service, fromMillis, toMillis, limit, null, null, null);
    }

    public static String httpEndpointSummarySql(
            String database,
            String service,
            long fromMillis,
            long toMillis,
            int limit,
            String httpMethod,
            String httpCode,
            String urlContains) {
        return httpEndpointSummarySql(
                database, service, fromMillis, toMillis, limit, httpMethod, httpCode, urlContains,
                null, null, null);
    }

    public static String httpEndpointSummarySql(
            String database,
            String service,
            long fromMillis,
            long toMillis,
            int limit,
            String httpMethod,
            String httpCode,
            String urlContains,
            Integer isIn,
            Integer isOut,
            String srcServiceId) {
        java.util.List<String> serviceKeys = service == null || service.isBlank() ? null : java.util.List.of(service);
        java.util.List<String> srcKeys = srcServiceId == null || srcServiceId.isBlank() ? null : java.util.List.of(srcServiceId);
        return httpEndpointSummarySql(
                database, serviceKeys, fromMillis, toMillis, limit, httpMethod, httpCode, urlContains, isIn, isOut, srcKeys);
    }

    public static String httpEndpointSummarySql(
            String database,
            java.util.Collection<String> serviceKeys,
            long fromMillis,
            long toMillis,
            int limit,
            String httpMethod,
            String httpCode,
            String urlContains,
            Integer isIn,
            Integer isOut,
            java.util.Collection<String> srcServiceKeys) {
        return httpEndpointSummarySql(
                database, serviceKeys, fromMillis, toMillis, limit, httpMethod, httpCode, urlContains,
                isIn, isOut, srcServiceKeys, false);
    }

    public static String httpEndpointSummarySql(
            String database,
            java.util.Collection<String> serviceKeys,
            long fromMillis,
            long toMillis,
            int limit,
            String httpMethod,
            String httpCode,
            String urlContains,
            Integer isIn,
            Integer isOut,
            java.util.Collection<String> srcServiceKeys,
            boolean exactUrlMatch) {
        String filters = httpMetricFiltersWithKeys(
                serviceKeys, httpMethod, httpCode, urlContains, isIn, isOut, srcServiceKeys, exactUrlMatch);
        return """
                SELECT COALESCE(NULLIF(`service_id`, ''), `service`) AS service_id,
                       `service`, `url`, `httpMethod`, `httpCode`,
                       SUM(`cnt`) AS request_cnt,
                       SUM(`error`) AS error_cnt,
                       SUM(`sumDuration`) / NULLIF(SUM(`cnt`), 0) / 1000000 AS avg_duration
                FROM %s.`metric_service_http`
                WHERE %s
                %s
                GROUP BY `service_id`, `service`, `url`, `httpMethod`, `httpCode`
                ORDER BY request_cnt DESC
                LIMIT %d
                """.formatted(database, metricTsWhere(fromMillis, toMillis), filters, Math.max(1, Math.min(limit, 500)));
    }

    /**
     * Component resource flow rollup for portal {@code /slowInterface/getResourceRelations}.
     * {@code groupByColumns} must use Doris column names (e.g. {@code service_id}, {@code url}).
     */
    public static String componentResourceRelationSql(
            String database,
            String tableName,
            long fromMillis,
            long toMillis,
            java.util.Collection<String> serviceKeys,
            java.util.Collection<String> srcServiceKeys,
            String resourcePath,
            String rootResourcePath,
            Integer isIn,
            Integer isOut,
            java.util.List<String> groupByColumns,
            int limit) {
        String slowExpr = slowCountSelectExpr(tableName);
        double durationSec = Math.max(1.0, (toMillis - fromMillis) / 1000.0);
        StringBuilder filters = new StringBuilder();
        filters.append(buildServiceKeyOrFilter(serviceKeys));
        filters.append(buildSrcServiceKeyOrFilter(srcServiceKeys));
        appendEndpointResourceFilter(filters, tableName, resourcePath);
        appendRootResourceFilter(filters, tableName, rootResourcePath);
        boolean expandHttpEntryInbound = DorisTableNames.METRIC_SERVICE_HTTP.equals(tableName);
        appendMetricIsInFilter(filters, isIn, expandHttpEntryInbound);
        if (isOut != null) {
            filters.append(" AND `isOut` = '").append(isOut).append("' ");
        }
        String groupBy = groupByColumns.stream()
                .map(column -> "`" + column + "`")
                .collect(java.util.stream.Collectors.joining(", "));
        return """
                SELECT %s,
                       SUM(`cnt`) AS all_cnt,
                       %s AS slow_cnt,
                       SUM(`error`) AS err_cnt,
                       SUM(`sumDuration`) / NULLIF(SUM(`cnt`), 0) AS avg_time_ns,
                       MAX(`maxDuration`) AS max_time_ns,
                       SUM(`cnt`) / %s AS req_rate
                FROM %s.`%s`
                WHERE %s
                %s
                GROUP BY %s
                ORDER BY all_cnt DESC
                LIMIT %d
                """.formatted(
                groupBy,
                slowExpr,
                durationSec,
                database,
                tableName,
                metricTsWhere(fromMillis, toMillis),
                filters,
                groupBy,
                Math.max(1, Math.min(limit, 500)));
    }

    private static void appendEndpointResourceFilter(StringBuilder filters, String tableName, String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return;
        }
        String escaped = escapeLiteral(resourcePath.trim());
        if (DorisTableNames.METRIC_SERVICE_HTTP.equals(tableName)) {
            filters.append(" AND `url` = '").append(escaped).append("' ");
            return;
        }
        if (DorisTableNames.METRIC_SERVICE_DB.equals(tableName)) {
            filters.append(" AND (`sqlContent` = '").append(escaped).append("'")
                    .append(" OR `sqlContent` LIKE '%").append(escaped).append("%'")
                    .append(" OR `resource` = '").append(escaped).append("'")
                    .append(" OR `resource` LIKE '%").append(escaped).append("%') ");
            return;
        }
        filters.append(" AND (`resource` = '").append(escaped).append("'")
                .append(" OR `resource` LIKE '%").append(escaped).append("%') ");
    }

    private static void appendRootResourceFilter(
            StringBuilder filters, String tableName, String rootResourcePath) {
        if (rootResourcePath == null || rootResourcePath.isBlank()) {
            return;
        }
        String escaped = escapeLiteral(rootResourcePath.trim());
        filters.append(" AND (`rootResource` = '").append(escaped).append("'");
        if (metricTableHasUrlColumn(tableName)) {
            if (DorisTableNames.METRIC_SERVICE_DB.equals(tableName)) {
                filters.append(" OR `sqlContent` = '").append(escaped).append("'");
            } else {
                filters.append(" OR `url` = '").append(escaped).append("'");
            }
        }
        filters.append(") ");
    }

    private static boolean metricTableHasUrlColumn(String tableName) {
        return DorisTableNames.METRIC_SERVICE_HTTP.equals(tableName)
                || DorisTableNames.METRIC_SERVICE_DB.equals(tableName);
    }

    public static String httpLatencyDistributionSql(
            String database, String service, long fromMillis, long toMillis) {
        return httpLatencyDistributionSql(database, service, fromMillis, toMillis, null, null, null);
    }

    public static String httpLatencyDistributionSql(
            String database,
            String service,
            long fromMillis,
            long toMillis,
            String httpMethod,
            String httpCode,
            String urlContains) {
        String filters = httpMetricFilters(service, httpMethod, httpCode, urlContains, null, null, null);
        return """
                SELECT `durationRange`,
                       SUM(`cnt`) AS request_cnt,
                       SUM(`error`) AS error_cnt
                FROM %s.`metric_service_http`
                WHERE %s
                %s
                GROUP BY `durationRange`
                ORDER BY `durationRange` ASC
                """.formatted(database, metricTsWhere(fromMillis, toMillis), filters);
    }

    private static String buildServiceIdFilter(String service) {
        if (service == null || service.isBlank()) {
            return "";
        }
        return buildServiceKeyOrFilter(java.util.List.of(service));
    }

    private static String buildSrcServiceIdFilter(String service) {
        if (service == null || service.isBlank()) {
            return "";
        }
        return buildSrcServiceKeyOrFilter(java.util.List.of(service));
    }

    static String buildServiceKeyOrFilter(java.util.Collection<String> keys) {
        String serviceId = firstNormalizedServiceId(keys);
        if (serviceId == null) {
            return "";
        }
        return " AND `service_id` = '" + escapeLiteral(serviceId) + "' ";
    }

    private static String buildServiceIdsInFilter(java.util.Collection<String> serviceIds) {
        if (serviceIds == null || serviceIds.isEmpty()) {
            return "";
        }
        java.util.LinkedHashSet<String> normalized = new java.util.LinkedHashSet<>();
        for (String serviceId : serviceIds) {
            if (serviceId == null || serviceId.isBlank()) {
                continue;
            }
            String id = PortalServiceIdResolver.normalize(serviceId.trim());
            if (!id.isBlank()) {
                normalized.add(id);
            }
        }
        if (normalized.isEmpty()) {
            return "";
        }
        String joined = normalized.stream()
                .map(id -> "'" + escapeLiteral(id) + "'")
                .collect(java.util.stream.Collectors.joining(", "));
        return " AND `service_id` IN (" + joined + ") ";
    }

    static String buildSrcServiceKeyOrFilter(java.util.Collection<String> keys) {
        String serviceId = firstNormalizedServiceId(keys);
        if (serviceId == null) {
            return "";
        }
        return " AND `srcServiceId` = '" + escapeLiteral(serviceId) + "' ";
    }

    private static String firstNormalizedServiceId(java.util.Collection<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            if (key != null && !key.isBlank()) {
                return PortalServiceIdResolver.normalize(key.trim());
            }
        }
        return null;
    }

    private static String buildFlowServiceFilter(String service) {
        if (service == null || service.isBlank()) {
            return "";
        }
        String serviceId = PortalServiceIdResolver.normalize(service.trim());
        if (serviceId.isBlank()) {
            return "";
        }
        String escaped = escapeLiteral(serviceId);
        return " AND (`service_id` = '" + escaped + "' OR `parentServiceId` = '" + escaped + "') ";
    }

    private static String componentMetricFilters(
            String service,
            String serviceInstance,
            String resourceContains,
            String sqlOperation,
            String sqlDatabase,
            Integer isIn,
            Integer isOut,
            Integer isSlow,
            String srcServiceId) {
        java.util.List<String> serviceKeys = service == null || service.isBlank() ? null : java.util.List.of(service);
        java.util.List<String> srcKeys = srcServiceId == null || srcServiceId.isBlank() ? null : java.util.List.of(srcServiceId);
        return componentMetricFiltersWithKeys(
                serviceKeys, serviceInstance, resourceContains, sqlOperation, sqlDatabase,
                isIn, isOut, isSlow, srcKeys, false, true);
    }

    private static String componentMetricFiltersWithKeys(
            java.util.Collection<String> serviceKeys,
            String serviceInstance,
            String resourceContains,
            String sqlOperation,
            String sqlDatabase,
            Integer isIn,
            Integer isOut,
            Integer isSlow,
            java.util.Collection<String> srcServiceKeys) {
        return componentMetricFiltersWithKeys(
                serviceKeys, serviceInstance, resourceContains, sqlOperation, sqlDatabase,
                isIn, isOut, isSlow, srcServiceKeys, false, false);
    }

    private static String componentMetricFiltersWithKeys(
            java.util.Collection<String> serviceKeys,
            String serviceInstance,
            String resourceContains,
            String sqlOperation,
            String sqlDatabase,
            Integer isIn,
            Integer isOut,
            Integer isSlow,
            java.util.Collection<String> srcServiceKeys,
            boolean expandHttpEntryInbound) {
        return componentMetricFiltersWithKeys(
                serviceKeys, serviceInstance, resourceContains, sqlOperation, sqlDatabase,
                isIn, isOut, isSlow, srcServiceKeys, expandHttpEntryInbound, false);
    }

    private static boolean resourceFilterMatchesSqlContent(String tableName) {
        return DorisTableNames.METRIC_SERVICE_DB.equals(tableName);
    }

    private static String componentMetricFiltersWithKeys(
            java.util.Collection<String> serviceKeys,
            String serviceInstance,
            String resourceContains,
            String sqlOperation,
            String sqlDatabase,
            Integer isIn,
            Integer isOut,
            Integer isSlow,
            java.util.Collection<String> srcServiceKeys,
            boolean expandHttpEntryInbound,
            boolean matchSqlContentOnResource) {
        StringBuilder filters = new StringBuilder();
        filters.append(buildServiceKeyOrFilter(serviceKeys));
        filters.append(buildServiceInstanceFilter(serviceInstance));
        appendResourceContainsFilter(filters, resourceContains, matchSqlContentOnResource);
        if (sqlOperation != null && !sqlOperation.isBlank()) {
            filters.append(" AND `sqlOperation` LIKE '%").append(escapeLiteral(sqlOperation)).append("%' ");
        }
        if (sqlDatabase != null && !sqlDatabase.isBlank()) {
            filters.append(" AND `sqlDatabase` LIKE '%").append(escapeLiteral(sqlDatabase)).append("%' ");
        }
        appendMetricIsInFilter(filters, isIn, expandHttpEntryInbound);
        if (isOut != null) {
            filters.append(" AND `isOut` = '").append(isOut).append("' ");
        }
        if (isSlow != null) {
            filters.append(" AND `isSlow` = '").append(isSlow).append("' ");
        }
        filters.append(buildSrcServiceKeyOrFilter(srcServiceKeys));
        return filters.toString();
    }

    private static String componentResourceTrendFilters(
            String table,
            String serviceId,
            String serviceInstance,
            String url,
            String resource,
            Integer isIn,
            Integer isOut) {
        StringBuilder filters = new StringBuilder();
        if (serviceId != null && !serviceId.isBlank()) {
            filters.append(" AND `service_id` = '").append(escapeLiteral(serviceId.trim())).append("' ");
        }
        filters.append(buildServiceInstanceFilter(serviceInstance));
        if (DorisTableNames.METRIC_SERVICE_HTTP.equals(table)) {
            if (url != null && !url.isBlank()) {
                filters.append(" AND `url` = '").append(escapeLiteral(url.trim())).append("' ");
            }
        } else if (resource != null && !resource.isBlank()) {
            filters.append(" AND `resource` = '").append(escapeLiteral(resource.trim())).append("' ");
        }
        appendMetricIsInFilter(filters, isIn, false);
        if (isOut != null) {
            filters.append(" AND `isOut` = '").append(isOut).append("' ");
        }
        return filters.toString();
    }

    private static void appendResourceContainsFilter(
            StringBuilder filters, String resourceContains, boolean matchSqlContentOnResource) {
        if (resourceContains == null || resourceContains.isBlank()) {
            return;
        }
        String escaped = escapeLiteral(resourceContains);
        if (matchSqlContentOnResource) {
            filters.append(" AND (`resource` LIKE '%").append(escaped)
                    .append("%' OR `sqlContent` LIKE '%").append(escaped).append("%') ");
        } else {
            filters.append(" AND `resource` LIKE '%").append(escaped).append("%' ");
        }
    }

    /**
     * Root HTTP entry spans have no {@code srcService}; legacy rows may keep {@code isIn=0,isOut=0}.
     * Do not OR those predicates on Doris aggregate key columns — the engine returns zero rows.
     * Callers that need legacy inbound rows must UNION a second query ({@link #legacyInboundEntryFilter()}).
     */
    private static void appendMetricIsInFilter(
            StringBuilder filters, Integer isIn, @SuppressWarnings("unused") boolean expandServiceEntryInbound) {
        if (isIn == null) {
            return;
        }
        filters.append(" AND `isIn` = '").append(isIn).append("' ");
    }

    private static String httpMetricFilters(
            String service,
            String httpMethod,
            String httpCode,
            String urlContains,
            Integer isIn,
            Integer isOut,
            String srcServiceId) {
        java.util.List<String> serviceKeys = service == null || service.isBlank() ? null : java.util.List.of(service);
        java.util.List<String> srcKeys = srcServiceId == null || srcServiceId.isBlank() ? null : java.util.List.of(srcServiceId);
        return httpMetricFiltersWithKeys(serviceKeys, httpMethod, httpCode, urlContains, isIn, isOut, srcKeys);
    }

    private static String httpMetricFiltersWithKeys(
            java.util.Collection<String> serviceKeys,
            String httpMethod,
            String httpCode,
            String urlContains,
            Integer isIn,
            Integer isOut,
            java.util.Collection<String> srcServiceKeys) {
        return httpMetricFiltersWithKeys(
                serviceKeys, httpMethod, httpCode, urlContains, isIn, isOut, srcServiceKeys, false);
    }

    private static String httpMetricFiltersWithKeys(
            java.util.Collection<String> serviceKeys,
            String httpMethod,
            String httpCode,
            String urlContains,
            Integer isIn,
            Integer isOut,
            java.util.Collection<String> srcServiceKeys,
            boolean exactUrlMatch) {
        StringBuilder filters = new StringBuilder();
        filters.append(buildServiceKeyOrFilter(serviceKeys));
        if (httpMethod != null && !httpMethod.isBlank()) {
            filters.append(" AND `httpMethod` = '").append(escapeLiteral(httpMethod)).append("' ");
        }
        if (httpCode != null && !httpCode.isBlank()) {
            filters.append(" AND `httpCode` = '").append(escapeLiteral(httpCode)).append("' ");
        }
        appendHttpUrlFilter(filters, urlContains, exactUrlMatch);
        appendMetricIsInFilter(filters, isIn, true);
        if (isOut != null) {
            filters.append(" AND `isOut` = '").append(isOut).append("' ");
        }
        filters.append(buildSrcServiceKeyOrFilter(srcServiceKeys));
        return filters.toString();
    }

    private static void appendHttpUrlFilter(StringBuilder filters, String urlValue, boolean exact) {
        if (urlValue == null || urlValue.isBlank()) {
            return;
        }
        String escaped = escapeLiteral(urlValue.trim());
        if (exact) {
            filters.append(" AND `url` = '").append(escaped).append("' ");
        } else {
            filters.append(" AND `url` LIKE '%").append(escaped).append("%' ");
        }
    }

    private static final String SPAN_EXCEPTION_NAME_EXPR = """
            CASE
              WHEN `meta.error.type` IS NOT NULL AND `meta.error.type` != '' THEN `meta.error.type`
              WHEN `meta.http.status_code` >= 400 THEN CONCAT('HTTP ', CAST(`meta.http.status_code` AS VARCHAR))
              ELSE COALESCE(NULLIF(`resource`, ''), 'Unknown Error')
            END""";

    private static final String SPAN_ENTRY_RESOURCE_EXPR =
            "COALESCE(NULLIF(" + metaJsonString("entry.resource") + ", ''), "
                    + "COALESCE(NULLIF(`resource`, ''), `name`))";

    private static final String SPAN_ROOT_RESOURCE_EXPR =
            "COALESCE(NULLIF(" + metaJsonString("root.resource") + ", ''), "
                    + "COALESCE(NULLIF(`resource`, ''), `name`))";

    public static String exceptionDistFromSpanSql(
            String database,
            String groupBy,
            long fromMillis,
            long toMillis,
            String serviceId,
            String serviceInstance,
            String resourceContains,
            String exceptionContains) {
        String timeWhere = spanEndBucketTimeWhere(fromMillis, toMillis, null, null);
        String groupColumn = switch (groupBy) {
            case "serviceId" -> "COALESCE(NULLIF(`serviceId`, ''), `service`)";
            case "serviceInstance" -> "COALESCE(NULLIF(`serviceInstance`, ''), `hostName`)";
            case "resource" -> SPAN_ENTRY_RESOURCE_EXPR;
            case "rootResource" -> SPAN_ROOT_RESOURCE_EXPR;
            case "serviceId,serviceInstance" -> null;
            default -> SPAN_EXCEPTION_NAME_EXPR;
        };
        if ("serviceId,serviceInstance".equals(groupBy)) {
            StringBuilder filters = spanExceptionFilters(
                    serviceId, serviceInstance, resourceContains, exceptionContains, groupBy);
            return """
                    SELECT COALESCE(NULLIF(`serviceId`, ''), `service`) AS service_id,
                           COALESCE(NULLIF(`serviceInstance`, ''), `hostName`) AS service_instance,
                           COUNT(*) AS err_cnt
                    FROM %s.`trace_dc_span`
                    WHERE %s
                    %s
                    GROUP BY COALESCE(NULLIF(`serviceId`, ''), `service`),
                             COALESCE(NULLIF(`serviceInstance`, ''), `hostName`)
                    ORDER BY err_cnt DESC
                    LIMIT 500
                    """.formatted(database, timeWhere, filters);
        }
        String groupAlias = switch (groupBy) {
            case "serviceId" -> "service_id";
            case "serviceInstance" -> "service_instance";
            case "resource", "rootResource" -> "resource";
            default -> "exception_name";
        };
        StringBuilder filters = spanExceptionFilters(
                serviceId, serviceInstance, resourceContains, exceptionContains, groupBy);
        return """
                SELECT %s AS %s,
                       COUNT(*) AS err_cnt
                FROM %s.`trace_dc_span`
                WHERE %s
                %s
                GROUP BY %s
                ORDER BY err_cnt DESC
                LIMIT 500
                """.formatted(groupColumn, groupAlias, database, timeWhere, filters, groupColumn);
    }

    private static StringBuilder spanExceptionFilters(
            String serviceId,
            String serviceInstance,
            String resourceContains,
            String exceptionContains,
            String groupBy) {
        StringBuilder filters = new StringBuilder(" AND `error` = 1 ");
        if (serviceId != null && !serviceId.isBlank()) {
            filters.append(buildTraceServiceKeyOrFilter(java.util.List.of(serviceId)));
        }
        if (serviceInstance != null && !serviceInstance.isBlank()) {
            filters.append(" AND COALESCE(NULLIF(`serviceInstance`, ''), `hostName`) = '")
                    .append(escapeLiteral(serviceInstance)).append("' ");
        }
        if (resourceContains != null && !resourceContains.isBlank()) {
            String resourceExpr = switch (groupBy) {
                case "rootResource" -> SPAN_ROOT_RESOURCE_EXPR;
                case "resource" -> SPAN_ENTRY_RESOURCE_EXPR;
                default -> "COALESCE(NULLIF(`resource`, ''), `name`)";
            };
            filters.append(" AND ").append(resourceExpr).append(" LIKE '%")
                    .append(escapeLiteral(resourceContains)).append("%' ");
        }
        if (exceptionContains != null && !exceptionContains.isBlank()
                && "exceptionName".equals(groupBy)) {
            filters.append(" AND ").append(SPAN_EXCEPTION_NAME_EXPR)
                    .append(" LIKE '%").append(escapeLiteral(exceptionContains)).append("%' ");
        }
        return filters;
    }

    public static String exceptionDistFromMetricResourceSql(
            String database,
            long fromMillis,
            long toMillis,
            String serviceId,
            String serviceInstance,
            String resourceContains) {
        StringBuilder filters = new StringBuilder();
        appendServiceExceptionFilters(filters, serviceId, serviceInstance, resourceContains, null, null);
        return """
                SELECT `resource` AS resource,
                       SUM(`cnt`) AS err_cnt
                FROM %s.`metric_service_exception`
                WHERE %s
                %s
                GROUP BY `resource`
                HAVING SUM(`cnt`) > 0
                ORDER BY err_cnt DESC
                LIMIT 500
                """.formatted(database, metricTsWhere(fromMillis, toMillis), filters);
    }

    public static String exceptionDistFromMetricRootResourceSql(
            String database,
            long fromMillis,
            long toMillis,
            String serviceId,
            String serviceInstance,
            String rootResourceContains) {
        StringBuilder filters = new StringBuilder();
        appendServiceExceptionFilters(filters, serviceId, serviceInstance, null, null, rootResourceContains);
        filters.append(" AND `rootResource` IS NOT NULL AND `rootResource` != '' ");
        return """
                SELECT `rootResource` AS resource,
                       SUM(`cnt`) AS err_cnt
                FROM %s.`metric_service_exception`
                WHERE %s
                %s
                GROUP BY `rootResource`
                HAVING SUM(`cnt`) > 0
                ORDER BY err_cnt DESC
                LIMIT 500
                """.formatted(database, metricTsWhere(fromMillis, toMillis), filters);
    }

    public static String exceptionDistFromMetricSql(
            String database,
            long fromMillis,
            long toMillis,
            String serviceId,
            String serviceInstance,
            String exceptionContains) {
        StringBuilder filters = new StringBuilder();
        appendServiceExceptionFilters(filters, serviceId, serviceInstance, null, exceptionContains, null);
        return """
                SELECT `exceptionName` AS exception_name,
                       SUM(`cnt`) AS err_cnt
                FROM %s.`metric_service_exception`
                WHERE %s
                %s
                GROUP BY `exceptionName`
                ORDER BY err_cnt DESC
                LIMIT 500
                """.formatted(database, metricTsWhere(fromMillis, toMillis), filters);
    }

    public static String exceptionListSql(
            String database,
            long fromMillis,
            long toMillis,
            String serviceId,
            String serviceInstance,
            String resourceContains,
            String exceptionContains,
            String rootResourceContains,
            String sortField,
            String sortOrder,
            int offset,
            int limit) {
        StringBuilder filters = new StringBuilder();
        appendServiceExceptionFilters(
                filters, serviceId, serviceInstance, resourceContains, exceptionContains, rootResourceContains);
        String orderColumn = exceptionListOrderColumn(sortField);
        String direction = "asc".equalsIgnoreCase(sortOrder) ? "ASC" : "DESC";
        return """
                SELECT `ts`,
                       `resource`,
                       `exceptionName`,
                       `service`,
                       `service_id`,
                       `service_instance`,
                       `rootResource`,
                       `cnt` AS err_cnt
                FROM %s.`metric_service_exception`
                WHERE %s
                %s
                  AND `cnt` > 0
                ORDER BY %s %s, `ts` DESC
                LIMIT %d OFFSET %d
                """.formatted(
                database,
                metricTsWhere(fromMillis, toMillis),
                filters,
                orderColumn,
                direction,
                Math.max(1, limit),
                Math.max(0, offset));
    }

    public static String exceptionListCountSql(
            String database,
            long fromMillis,
            long toMillis,
            String serviceId,
            String serviceInstance,
            String resourceContains,
            String exceptionContains,
            String rootResourceContains) {
        StringBuilder filters = new StringBuilder();
        appendServiceExceptionFilters(
                filters, serviceId, serviceInstance, resourceContains, exceptionContains, rootResourceContains);
        return """
                SELECT COUNT(*) AS total_cnt
                FROM %s.`metric_service_exception`
                WHERE %s
                %s
                  AND `cnt` > 0
                """.formatted(database, metricTsWhere(fromMillis, toMillis), filters);
    }

    private static void appendServiceExceptionFilters(
            StringBuilder filters,
            String serviceId,
            String serviceInstance,
            String resourceContains,
            String exceptionContains,
            String rootResourceContains) {
        if (serviceId != null && !serviceId.isBlank()) {
            filters.append(buildServiceKeyOrFilter(java.util.List.of(serviceId)));
        }
        if (serviceInstance != null && !serviceInstance.isBlank()) {
            filters.append(" AND `service_instance` = '").append(escapeLiteral(serviceInstance)).append("' ");
        }
        if (resourceContains != null && !resourceContains.isBlank()) {
            filters.append(" AND `resource` LIKE '%").append(escapeLiteral(resourceContains)).append("%' ");
        }
        if (exceptionContains != null && !exceptionContains.isBlank()) {
            filters.append(" AND `exceptionName` LIKE '%").append(escapeLiteral(exceptionContains)).append("%' ");
        }
        if (rootResourceContains != null && !rootResourceContains.isBlank()) {
            filters.append(" AND `rootResource` LIKE '%")
                    .append(escapeLiteral(rootResourceContains)).append("%' ");
        }
    }

    private static String exceptionListOrderColumn(String sortField) {
        if (sortField == null || sortField.isBlank()) {
            return "`ts`";
        }
        return switch (sortField) {
            case "resource" -> "`resource`";
            case "service", "serviceId" -> "`service`";
            case "serviceInstance", "hostName" -> "`service_instance`";
            case "errorType", "exceptionName" -> "`exceptionName`";
            default -> "`ts`";
        };
    }

    public static String serviceErrorDistSql(
            String database, long fromMillis, long toMillis, String serviceNameFilter) {
        String serviceFilter = serviceNameFilter == null || serviceNameFilter.isBlank()
                ? ""
                : " AND `service` LIKE '%" + escapeLiteral(serviceNameFilter) + "%' ";
        return """
                SELECT COALESCE(NULLIF(`service_id`, ''), `service`) AS service_id,
                       SUM(`cnt`) AS err_cnt
                FROM %s.`metric_service_exception`
                WHERE %s
                %s
                GROUP BY COALESCE(NULLIF(`service_id`, ''), `service`)
                HAVING SUM(`cnt`) > 0
                ORDER BY err_cnt DESC
                LIMIT 500
                """.formatted(database, metricTsWhere(fromMillis, toMillis), serviceFilter);
    }

    public static String exceptionDistFromMetricServiceInstanceSql(
            String database,
            long fromMillis,
            long toMillis,
            String serviceId,
            String serviceInstance) {
        StringBuilder filters = new StringBuilder();
        appendServiceExceptionFilters(filters, serviceId, serviceInstance, null, null, null);
        return """
                SELECT COALESCE(NULLIF(`service_id`, ''), `service`) AS service_id,
                       `service_instance` AS service_instance,
                       SUM(`cnt`) AS err_cnt
                FROM %s.`metric_service_exception`
                WHERE %s
                %s
                GROUP BY COALESCE(NULLIF(`service_id`, ''), `service`), `service_instance`
                HAVING SUM(`cnt`) > 0
                ORDER BY err_cnt DESC
                LIMIT 500
                """.formatted(database, metricTsWhere(fromMillis, toMillis), filters);
    }

    public static String httpErrorResourceDistSql(
            String database,
            long fromMillis,
            long toMillis,
            String serviceId,
            String resourceContains) {
        String filters = httpMetricFilters(serviceId, null, null, resourceContains, null, null, null);
        return """
                SELECT `url` AS resource,
                       SUM(`error`) AS err_cnt
                FROM %s.`metric_service_http`
                WHERE %s
                %s
                GROUP BY `url`
                HAVING SUM(`error`) > 0
                ORDER BY err_cnt DESC
                LIMIT 500
                """.formatted(database, metricTsWhere(fromMillis, toMillis), filters);
    }

    public static String serviceFlowSrcServicesSql(
            String database, String dstService, long fromMillis, long toMillis, int limit) {
        return """
                SELECT DISTINCT `parentService` AS tag_value
                FROM %s.`metric_service_flow`
                WHERE %s
                  AND `parentService` = '%s'
                  AND `parentService` IS NOT NULL AND `parentService` != ''
                ORDER BY tag_value ASC
                LIMIT %d
                """.formatted(
                database, metricTsWhere(fromMillis, toMillis), escapeLiteral(dstService), Math.max(1, Math.min(limit, 200)));
    }

    private static String escapeLiteral(String value) {
        return value.replace("'", "''");
    }

    /** OTLP attribute keys contain dots; Doris JSON path must quote them: {@code $."db.system"}. */
    static String metaJsonString(String otelAttributeKey) {
        return "get_json_string(`meta`, '$.\"" + escapeLiteral(otelAttributeKey) + "\"')";
    }

    public static String metricTagDistinctSql(
            String database,
            String table,
            String tagColumn,
            long fromMillis,
            long toMillis,
            String extraFilters) {
        String column = MetricIdentifierParser.toColumnName(tagColumn);
        return """
                SELECT DISTINCT `%s` AS tag_value
                FROM %s.`%s`
                WHERE %s
                  AND `%s` IS NOT NULL AND `%s` != ''
                %s
                ORDER BY tag_value ASC
                LIMIT 200
                """.formatted(
                column,
                database,
                table,
                metricTsWhere(fromMillis, toMillis),
                column,
                column,
                extraFilters == null ? "" : extraFilters);
    }

    public static String metricFieldSeriesSql(
            String database,
            String table,
            String fieldColumn,
            long fromMillis,
            long toMillis,
            String extraFilters) {
        return metricFieldSeriesSql(database, table, fieldColumn, fromMillis, toMillis, extraFilters, 60);
    }

    public static String metricFieldSeriesSql(
            String database,
            String table,
            String fieldColumn,
            long fromMillis,
            long toMillis,
            String extraFilters,
            int intervalSec) {
        return metricFieldSeriesSql(
                database, table, fieldColumn, fromMillis, toMillis, extraFilters, intervalSec, null);
    }

    public static boolean isJvmGcMonotonicField(String fieldColumn) {
        if (fieldColumn == null) {
            return false;
        }
        return switch (fieldColumn) {
            case "gc_major_collection_count", "gc_minor_collection_count",
                 "gc_major_collection_time", "gc_minor_collection_time" -> true;
            default -> false;
        };
    }

    private static boolean isJvmGcCollectionTimeField(String fieldColumn) {
        return fieldColumn != null && fieldColumn.endsWith("_collection_time");
    }

    private static String jvmGcSeriesPartitionKeyExpr() {
        return "COALESCE(NULLIF(`service_instance`, ''), NULLIF(`instance`, ''), `service_id`, '')";
    }

    private static String jvmGcDeltaValueExpr(String fieldColumn) {
        String delta = "GREATEST(counter_value - COALESCE(prev_value, counter_value), 0)";
        return isJvmGcCollectionTimeField(fieldColumn) ? "(" + delta + " * 1000)" : delta;
    }

    private static String jvmGcCounterSeriesSql(
            String database,
            String table,
            String fieldColumn,
            long fromMillis,
            long toMillis,
            String extraFilters,
            int intervalSec,
            String aggs) {
        int bucketSec = Math.max(60, intervalSec);
        String column = MetricIdentifierParser.toFieldColumnName(fieldColumn);
        String outerAgg = resolveFieldAggregation(fieldColumn, aggs).formatted("delta_value");
        String partitionKey = jvmGcSeriesPartitionKeyExpr();
        String deltaValue = jvmGcDeltaValueExpr(fieldColumn);
        return """
                WITH bucketed AS (
                    SELECT %s AS epoch_sec,
                           %s AS series_key,
                           MAX(`%s`) AS counter_value
                    FROM %s.`%s`
                    WHERE %s
                    %s
                    GROUP BY epoch_sec, series_key
                ),
                deltas AS (
                    SELECT epoch_sec,
                           %s AS delta_value
                    FROM (
                        SELECT epoch_sec,
                               series_key,
                               counter_value,
                               LAG(counter_value) OVER (PARTITION BY series_key ORDER BY epoch_sec) AS prev_value
                        FROM bucketed
                    ) raw
                )
                SELECT epoch_sec,
                       %s AS metric_value
                FROM deltas
                GROUP BY epoch_sec
                ORDER BY epoch_sec ASC
                """.formatted(
                metricBucketEpochSecSelect(bucketSec),
                partitionKey,
                column,
                database,
                table,
                metricTsWhere(fromMillis, toMillis),
                extraFilters == null ? "" : extraFilters,
                deltaValue,
                outerAgg);
    }

    private static String jvmGcCounterScalarSql(
            String database,
            String table,
            String fieldColumn,
            long fromMillis,
            long toMillis,
            String extraFilters) {
        String column = MetricIdentifierParser.toFieldColumnName(fieldColumn);
        String partitionKey = jvmGcSeriesPartitionKeyExpr();
        String deltaValue = jvmGcDeltaValueExpr(fieldColumn);
        int bucketSec = 60;
        return """
                WITH bucketed AS (
                    SELECT %s AS epoch_sec,
                           %s AS series_key,
                           MAX(`%s`) AS counter_value
                    FROM %s.`%s`
                    WHERE %s
                    %s
                    GROUP BY epoch_sec, series_key
                ),
                deltas AS (
                    SELECT %s AS delta_value
                    FROM (
                        SELECT epoch_sec,
                               series_key,
                               counter_value,
                               LAG(counter_value) OVER (PARTITION BY series_key ORDER BY epoch_sec) AS prev_value
                        FROM bucketed
                    ) raw
                )
                SELECT SUM(delta_value) AS metric_value
                FROM deltas
                """.formatted(
                metricBucketEpochSecSelect(bucketSec),
                partitionKey,
                column,
                database,
                table,
                metricTsWhere(fromMillis, toMillis),
                extraFilters == null ? "" : extraFilters,
                deltaValue);
    }

    private static String jvmGcCounterTopGroupsSql(
            String database,
            String table,
            String fieldColumn,
            String groupColumn,
            long fromMillis,
            long toMillis,
            String extraFilters,
            int limit) {
        String column = MetricIdentifierParser.toFieldColumnName(fieldColumn);
        String group = MetricIdentifierParser.toColumnName(groupColumn);
        String deltaValue = jvmGcDeltaValueExpr(fieldColumn);
        int bucketSec = 60;
        return """
                WITH bucketed AS (
                    SELECT `%s` AS group_value,
                           %s AS epoch_sec,
                           MAX(`%s`) AS counter_value
                    FROM %s.`%s`
                    WHERE %s
                      AND `%s` IS NOT NULL AND `%s` != ''
                    %s
                    GROUP BY group_value, epoch_sec
                ),
                deltas AS (
                    SELECT group_value,
                           %s AS delta_value
                    FROM (
                        SELECT group_value,
                               epoch_sec,
                               counter_value,
                               LAG(counter_value) OVER (PARTITION BY group_value ORDER BY epoch_sec) AS prev_value
                        FROM bucketed
                    ) raw
                )
                SELECT group_value,
                       SUM(delta_value) AS metric_total
                FROM deltas
                GROUP BY group_value
                ORDER BY metric_total DESC
                LIMIT %d
                """.formatted(
                group,
                metricBucketEpochSecSelect(bucketSec),
                column,
                database,
                table,
                metricTsWhere(fromMillis, toMillis),
                group,
                group,
                extraFilters == null ? "" : extraFilters,
                deltaValue,
                Math.max(1, Math.min(limit, 50)));
    }

    public static String metricFieldSeriesSql(
            String database,
            String table,
            String fieldColumn,
            long fromMillis,
            long toMillis,
            String extraFilters,
            int intervalSec,
            String aggs) {
        if (isJvmGcMonotonicField(fieldColumn)) {
            return jvmGcCounterSeriesSql(
                    database, table, fieldColumn, fromMillis, toMillis, extraFilters, intervalSec, aggs);
        }
        String derivedExpr = derivedMetricValueExpr(fieldColumn);
        if (derivedExpr != null) {
            return derivedMetricSeriesSql(
                    database, table, derivedExpr, fromMillis, toMillis, extraFilters, intervalSec);
        }
        int bucketSec = Math.max(60, intervalSec);
        String column = MetricIdentifierParser.toFieldColumnName(fieldColumn);
        String agg = resolveFieldAggregation(fieldColumn, aggs);
        return """
                SELECT %s AS epoch_sec,
                       %s AS metric_value
                FROM %s.`%s`
                WHERE %s
                %s
                GROUP BY epoch_sec
                ORDER BY epoch_sec ASC
                """.formatted(
                metricBucketEpochSecSelect(bucketSec),
                agg.formatted(column),
                database,
                table,
                metricTsWhere(fromMillis, toMillis),
                extraFilters == null ? "" : extraFilters);
    }

    private static String derivedMetricValueExpr(String fieldColumn) {
        if (fieldColumn == null) {
            return null;
        }
        return switch (fieldColumn) {
            case "avgDuration" -> AVG_DURATION_MS_EXPR;
            case "error.pct" -> "SUM(`error`) / NULLIF(SUM(`cnt`), 0) * 100";
            case "success.pct" -> "(1 - SUM(`error`) / NULLIF(SUM(`cnt`), 0)) * 100";
            case "slow.pct" -> "SUM(`slow`) / NULLIF(SUM(`cnt`), 0) * 100";
            default -> null;
        };
    }

    private static String derivedMetricSeriesSql(
            String database,
            String table,
            String valueExpr,
            long fromMillis,
            long toMillis,
            String extraFilters,
            int intervalSec) {
        int bucketSec = Math.max(60, intervalSec);
        return """
                SELECT %s AS epoch_sec,
                       %s AS metric_value
                FROM %s.`%s`
                WHERE %s
                %s
                GROUP BY epoch_sec
                ORDER BY epoch_sec ASC
                """.formatted(
                metricBucketEpochSecSelect(bucketSec),
                valueExpr,
                database,
                table,
                metricTsWhere(fromMillis, toMillis),
                extraFilters == null ? "" : extraFilters);
    }

    public static String metricAggregateScalarSql(
            String database,
            String table,
            String fieldColumn,
            long fromMillis,
            long toMillis,
            String extraFilters) {
        return metricAggregateScalarSql(database, table, fieldColumn, fromMillis, toMillis, extraFilters, null);
    }

    public static String metricAggregateScalarSql(
            String database,
            String table,
            String fieldColumn,
            long fromMillis,
            long toMillis,
            String extraFilters,
            String aggs) {
        if (isJvmGcMonotonicField(fieldColumn)) {
            return jvmGcCounterScalarSql(database, table, fieldColumn, fromMillis, toMillis, extraFilters);
        }
        String column = MetricIdentifierParser.toFieldColumnName(fieldColumn);
        String agg = resolveFieldAggregation(fieldColumn, aggs);
        return """
                SELECT %s AS metric_value
                FROM %s.`%s`
                WHERE %s
                %s
                """.formatted(
                agg.formatted(column),
                database,
                table,
                metricTsWhere(fromMillis, toMillis),
                extraFilters == null ? "" : extraFilters);
    }

    static String resolveFieldAggregation(String fieldColumn, String aggs) {
        if (aggs != null && !aggs.isBlank()) {
            return switch (aggs.toLowerCase()) {
                case "mean", "avg" -> "AVG(`%s`)";
                case "sum" -> "SUM(`%s`)";
                case "max" -> "MAX(`%s`)";
                case "min" -> "MIN(`%s`)";
                default -> defaultFieldAggregation(fieldColumn);
            };
        }
        return defaultFieldAggregation(fieldColumn);
    }

    private static String defaultFieldAggregation(String fieldColumn) {
        return fieldColumn != null && (fieldColumn.contains("Time") || fieldColumn.contains("Duration"))
                ? "AVG(`%s`)"
                : "SUM(`%s`)";
    }

    public static String metricErrorPctScalarSql(
            String database,
            String table,
            long fromMillis,
            long toMillis,
            String extraFilters) {
        return """
                SELECT SUM(`error`) AS error_cnt, SUM(`cnt`) AS total_cnt
                FROM %s.`%s`
                WHERE %s
                %s
                """.formatted(
                database,
                table,
                metricTsWhere(fromMillis, toMillis),
                extraFilters == null ? "" : extraFilters);
    }

    public static String metricAvgDurationScalarSql(
            String database,
            String table,
            long fromMillis,
            long toMillis,
            String extraFilters) {
        return """
                SELECT %s AS metric_value
                FROM %s.`%s`
                WHERE %s
                %s
                """.formatted(
                AVG_DURATION_MS_EXPR,
                database,
                table,
                metricTsWhere(fromMillis, toMillis),
                extraFilters == null ? "" : extraFilters);
    }

    public static String metricFilterClause(String column, String operator, String value) {
        String col = MetricIdentifierParser.toColumnName(column);
        String escaped = escapeLiteral(value == null ? "" : value);
        return switch (normalizeMetricFilterOperator(operator)) {
            case "!=" -> " AND `" + col + "` != '" + escaped + "' ";
            case "LIKE" -> " AND `" + col + "` LIKE '%" + escaped + "%' ";
            case "NOT LIKE" -> " AND `" + col + "` NOT LIKE '%" + escaped + "%' ";
            default -> " AND `" + col + "` = '" + escaped + "' ";
        };
    }

    private static String normalizeMetricFilterOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            return "=";
        }
        return switch (operator.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "!=", "neq" -> "!=";
            case "like" -> "LIKE";
            case "notlike", "not_like", "not like" -> "NOT LIKE";
            default -> "=";
        };
    }

    public static String metricTopGroupsSql(
            String database,
            String table,
            String fieldColumn,
            String groupColumn,
            long fromMillis,
            long toMillis,
            String extraFilters,
            int limit) {
        return metricTopGroupsSql(
                database, table, fieldColumn, groupColumn, fromMillis, toMillis, extraFilters, limit, null);
    }

    public static String metricTopGroupsSql(
            String database,
            String table,
            String fieldColumn,
            String groupColumn,
            long fromMillis,
            long toMillis,
            String extraFilters,
            int limit,
            String aggs) {
        if (isJvmGcMonotonicField(fieldColumn)) {
            return jvmGcCounterTopGroupsSql(
                    database, table, fieldColumn, groupColumn, fromMillis, toMillis, extraFilters, limit);
        }
        String derivedExpr = derivedMetricValueExpr(fieldColumn);
        if (derivedExpr != null) {
            return derivedMetricTopGroupsSql(
                    database, table, derivedExpr, groupColumn, fromMillis, toMillis, extraFilters, limit);
        }
        String column = MetricIdentifierParser.toFieldColumnName(fieldColumn);
        String group = MetricIdentifierParser.toColumnName(groupColumn);
        String agg = resolveFieldAggregation(fieldColumn, aggs);
        return """
                SELECT `%s` AS group_value,
                       %s AS metric_total
                FROM %s.`%s`
                WHERE %s
                  AND `%s` IS NOT NULL AND `%s` != ''
                %s
                GROUP BY `%s`
                ORDER BY metric_total DESC
                LIMIT %d
                """.formatted(
                group,
                agg.formatted(column),
                database,
                table,
                metricTsWhere(fromMillis, toMillis),
                group,
                group,
                extraFilters == null ? "" : extraFilters,
                group,
                Math.max(1, Math.min(limit, 50)));
    }

    private static String derivedMetricTopGroupsSql(
            String database,
            String table,
            String valueExpr,
            String groupColumn,
            long fromMillis,
            long toMillis,
            String extraFilters,
            int limit) {
        String group = MetricIdentifierParser.toColumnName(groupColumn);
        return """
                SELECT `%s` AS group_value,
                       %s AS metric_total
                FROM %s.`%s`
                WHERE %s
                  AND `%s` IS NOT NULL AND `%s` != ''
                %s
                GROUP BY `%s`
                ORDER BY metric_total DESC
                LIMIT %d
                """.formatted(
                group,
                valueExpr,
                database,
                table,
                metricTsWhere(fromMillis, toMillis),
                group,
                group,
                extraFilters == null ? "" : extraFilters,
                group,
                Math.max(1, Math.min(limit, 50)));
    }

    public static String metricFieldSeriesByGroupSql(
            String database,
            String table,
            String fieldColumn,
            String groupColumn,
            String groupValue,
            long fromMillis,
            long toMillis,
            String extraFilters) {
        return metricFieldSeriesByGroupSql(
                database, table, fieldColumn, groupColumn, groupValue,
                fromMillis, toMillis, extraFilters, 60);
    }

    public static String metricFieldSeriesByGroupSql(
            String database,
            String table,
            String fieldColumn,
            String groupColumn,
            String groupValue,
            long fromMillis,
            long toMillis,
            String extraFilters,
            int intervalSec) {
        return metricFieldSeriesByGroupSql(
                database, table, fieldColumn, groupColumn, groupValue,
                fromMillis, toMillis, extraFilters, intervalSec, null);
    }

    public static String metricFieldSeriesByGroupSql(
            String database,
            String table,
            String fieldColumn,
            String groupColumn,
            String groupValue,
            long fromMillis,
            long toMillis,
            String extraFilters,
            int intervalSec,
            String aggs) {
        String groupFilter = metricFilterClause(groupColumn, "=", groupValue);
        return metricFieldSeriesSql(
                database, table, fieldColumn, fromMillis, toMillis,
                (extraFilters == null ? "" : extraFilters) + groupFilter, intervalSec, aggs);
    }

    public static String distinctResourceValuesSql(
            String database,
            String table,
            String column,
            long fromMillis,
            long toMillis,
            String extraFilters,
            int limit) {
        String col = MetricIdentifierParser.toColumnName(column);
        return """
                SELECT DISTINCT `%s` AS tag_value
                FROM %s.`%s`
                WHERE %s
                  AND `%s` IS NOT NULL AND `%s` != ''
                %s
                ORDER BY tag_value ASC
                LIMIT %d
                """.formatted(
                col,
                database,
                table,
                metricTsWhere(fromMillis, toMillis),
                col,
                col,
                extraFilters == null ? "" : extraFilters,
                Math.max(1, Math.min(limit, 1000)));
    }

    /** Distinct inbound caller services for portal {@code /service/resourcesGroupBy}. */
    public static String distinctSrcServicesSql(
            String database,
            String tableName,
            long fromMillis,
            long toMillis,
            String extraFilters,
            int limit) {
        return """
                SELECT `srcService`,
                       COALESCE(NULLIF(MAX(`srcServiceId`), ''), `srcService`) AS srcServiceId
                FROM %s.`%s`
                WHERE %s
                  AND `srcService` IS NOT NULL AND `srcService` != ''
                  AND `srcService` NOT LIKE '[%%'
                %s
                GROUP BY `srcService`, `srcServiceId`
                ORDER BY `srcService` ASC
                LIMIT %d
                """.formatted(
                database,
                tableName,
                metricTsWhere(fromMillis, toMillis),
                extraFilters == null ? "" : extraFilters,
                Math.max(1, Math.min(limit, 1000)));
    }

    private static final String CALL_SPAN_COLUMNS = """
            `trace_id`, `span_id`, `parent_id`, `start`, `end`, `resource`, `duration`, `error`, `slow`,
            `service`, COALESCE(NULLIF(`serviceId`, ''), `service`) AS service_id,
            COALESCE(`serviceInstance`, '') AS serviceInstance,
            COALESCE(`srcService`, '') AS srcService,
            COALESCE(`srcServiceId`, '') AS srcServiceId,
            COALESCE(`srcServiceInstance`, '') AS srcServiceInstance,
            COALESCE(`dstService`, '') AS dstService,
            COALESCE(`dstServiceId`, '') AS dstServiceId,
            COALESCE(`dstServiceInstance`, '') AS dstServiceInstance,
            `isIn`, `isOut`, `name`, `meta`, `metrics`,
            `meta.http.status_code` AS meta_http_status_code,
            `meta.http.method` AS meta_http_method,
            `meta.http.url` AS meta_http_url,
            `meta.error.type` AS meta_error_type
            """;

    public static String callSpanCountSql(
            String database,
            long fromMillis,
            long toMillis,
            String serviceId,
            String serviceInstance,
            String srcServiceId,
            String srcServiceInstance,
            String dstServiceId,
            String dstServiceInstance,
            String resource,
            String httpMethod,
            String rootResourceQuery,
            Boolean inbound,
            String componentType) {
        return callSpanCountSql(
                database, fromMillis, toMillis, null, null,
                serviceId, serviceInstance, srcServiceId, srcServiceInstance,
                dstServiceId, dstServiceInstance, resource, httpMethod, rootResourceQuery,
                inbound, componentType);
    }

    public static String callSpanCountSql(
            String database,
            long fromMillis,
            long toMillis,
            String fromTimeText,
            String toTimeText,
            String serviceId,
            String serviceInstance,
            String srcServiceId,
            String srcServiceInstance,
            String dstServiceId,
            String dstServiceInstance,
            String resource,
            String httpMethod,
            String rootResourceQuery,
            Boolean inbound,
            String componentType) {
        return """
                SELECT COUNT(*) AS total_cnt
                FROM %s.`trace_dc_span`
                WHERE %s
                %s
                """.formatted(
                database,
                spanEndBucketTimeWhere(fromMillis, toMillis, fromTimeText, toTimeText),
                callSpanFilters(
                        serviceId,
                        serviceInstance,
                        srcServiceId,
                        srcServiceInstance,
                        dstServiceId,
                        dstServiceInstance,
                        resource,
                        httpMethod,
                        rootResourceQuery,
                        inbound,
                        componentType));
    }

    public static String callSpanListSql(
            String database,
            long fromMillis,
            long toMillis,
            String serviceId,
            String serviceInstance,
            String srcServiceId,
            String srcServiceInstance,
            String dstServiceId,
            String dstServiceInstance,
            String resource,
            String httpMethod,
            String rootResourceQuery,
            Boolean inbound,
            String componentType,
            String sortField,
            String sortOrder,
            int limit,
            int offset) {
        return callSpanListSql(
                database, fromMillis, toMillis, null, null,
                serviceId, serviceInstance, srcServiceId, srcServiceInstance,
                dstServiceId, dstServiceInstance, resource, httpMethod, rootResourceQuery,
                inbound, componentType, sortField, sortOrder, limit, offset);
    }

    public static String callSpanListSql(
            String database,
            long fromMillis,
            long toMillis,
            String fromTimeText,
            String toTimeText,
            String serviceId,
            String serviceInstance,
            String srcServiceId,
            String srcServiceInstance,
            String dstServiceId,
            String dstServiceInstance,
            String resource,
            String httpMethod,
            String rootResourceQuery,
            Boolean inbound,
            String componentType,
            String sortField,
            String sortOrder,
            int limit,
            int offset) {
        String orderColumn = callSpanOrderColumn(sortField);
        String direction = "asc".equalsIgnoreCase(sortOrder) ? "ASC" : "DESC";
        return """
                SELECT %s
                FROM %s.`trace_dc_span`
                WHERE %s
                %s
                ORDER BY %s %s
                LIMIT %d OFFSET %d
                """.formatted(
                CALL_SPAN_COLUMNS,
                database,
                spanEndBucketTimeWhere(fromMillis, toMillis, fromTimeText, toTimeText),
                callSpanFilters(
                        serviceId,
                        serviceInstance,
                        srcServiceId,
                        srcServiceInstance,
                        dstServiceId,
                        dstServiceInstance,
                        resource,
                        httpMethod,
                        rootResourceQuery,
                        inbound,
                        componentType),
                orderColumn,
                direction,
                Math.max(1, Math.min(limit, 500)),
                Math.max(0, offset));
    }

    public static String callSpanChildrenSql(
            String database,
            long fromMillis,
            long toMillis,
            String serviceId,
            List<String> parentIds) {
        return callSpanChildrenSql(database, fromMillis, toMillis, null, null, serviceId, parentIds);
    }

    public static String callSpanChildrenSql(
            String database,
            long fromMillis,
            long toMillis,
            String fromTimeText,
            String toTimeText,
            String serviceId,
            List<String> parentIds) {
        if (parentIds == null || parentIds.isEmpty()) {
            return """
                    SELECT %s
                    FROM %s.`trace_dc_span`
                    WHERE 1 = 0
                    """.formatted(CALL_SPAN_COLUMNS, database);
        }
        String inClause = parentIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(id -> "'" + escapeLiteral(id) + "'")
                .reduce((left, right) -> left + ", " + right)
                .orElse("''");
        String serviceFilter = buildSpanServiceIdFilter(serviceId);
        return """
                SELECT %s
                FROM %s.`trace_dc_span`
                WHERE %s
                  AND `parent_id` IN (%s)
                  AND `isIn` = 1
                %s
                """.formatted(
                CALL_SPAN_COLUMNS,
                database,
                spanEndBucketTimeWhere(fromMillis, toMillis, fromTimeText, toTimeText),
                inClause,
                serviceFilter);
    }

    private static String callSpanOrderColumn(String sortField) {
        if (sortField == null || sortField.isBlank()) {
            return "`start`";
        }
        return switch (sortField) {
            case "end" -> "`end`";
            case "resource" -> "`resource`";
            case "duration", "client.duration", "server.duration" -> "`duration`";
            case "error", "client.error", "server.error" -> "`error`";
            default -> "`start`";
        };
    }

    private static String callSpanFilters(
            String serviceId,
            String serviceInstance,
            String srcServiceId,
            String srcServiceInstance,
            String dstServiceId,
            String dstServiceInstance,
            String resource,
            String httpMethod,
            String rootResourceQuery,
            Boolean inbound,
            String componentType) {
        StringBuilder filters = new StringBuilder();
        filters.append(buildSpanServiceIdFilter(serviceId));
        if (serviceInstance != null && !serviceInstance.isBlank()) {
            filters.append(" AND COALESCE(NULLIF(`serviceInstance`, ''), `hostName`) = '")
                    .append(escapeLiteral(serviceInstance)).append("' ");
        }
        filters.append(buildSpanSrcServiceIdFilter(srcServiceId));
        if (srcServiceInstance != null && !srcServiceInstance.isBlank()) {
            filters.append(" AND `srcServiceInstance` = '")
                    .append(escapeLiteral(srcServiceInstance)).append("' ");
        }
        filters.append(buildSpanDstServiceIdFilter(dstServiceId));
        if (dstServiceInstance != null && !dstServiceInstance.isBlank()) {
            filters.append(" AND `dstServiceInstance` = '")
                    .append(escapeLiteral(dstServiceInstance)).append("' ");
        }
        appendCallSpanResourceFilter(filters, httpMethod, resource, componentType);
        if (rootResourceQuery != null && !rootResourceQuery.isBlank()) {
            filters.append(" AND ").append(SPAN_ROOT_RESOURCE_EXPR).append(" LIKE '%")
                    .append(escapeLiteral(rootResourceQuery)).append("%' ");
        }
        if (inbound != null) {
            filters.append(inbound ? " AND `isIn` = 1 " : " AND `isOut` = 1 ");
        }
        filters.append(callSpanComponentFilter(componentType));
        return filters.toString();
    }

    private static String callSpanResourceMatch(String httpMethod, String resource) {
        if (resource == null || resource.isBlank()) {
            return null;
        }
        String trimmed = resource.trim();
        if (httpMethod != null && !httpMethod.isBlank() && !trimmed.contains(" ")) {
            return httpMethod.trim() + " " + trimmed;
        }
        return trimmed;
    }

    /** HTTP portal resource is usually a path; span {@code resource}/{@code name} may use route templates. */
    private static String callSpanHttpPath(String httpMethod, String resource) {
        if (resource == null || resource.isBlank()) {
            return null;
        }
        String trimmed = resource.trim();
        if (httpMethod != null && !httpMethod.isBlank() && trimmed.regionMatches(
                true, 0, httpMethod.trim() + " ", 0, httpMethod.trim().length() + 1)) {
            return trimmed.substring(httpMethod.trim().length() + 1).trim();
        }
        int space = trimmed.indexOf(' ');
        if (space > 0 && space < trimmed.length() - 1) {
            return trimmed.substring(space + 1).trim();
        }
        return trimmed;
    }

    static String buildSpanServiceIdFilter(String serviceId) {
        return buildTraceColumnServiceIdFilter("serviceId", serviceId);
    }

    static String buildSpanSrcServiceIdFilter(String srcServiceId) {
        return buildTraceColumnServiceIdFilter("srcServiceId", srcServiceId);
    }

    static String buildSpanDstServiceIdFilter(String dstServiceId) {
        return buildTraceColumnServiceIdFilter("dstServiceId", dstServiceId);
    }

    private static void appendCallSpanResourceFilter(
            StringBuilder filters, String httpMethod, String resource, String componentType) {
        if (resource == null || resource.isBlank()) {
            return;
        }
        if ("service.db".equals(componentType)) {
            String resourceMatch = callSpanResourceMatch(httpMethod, resource);
            if (resourceMatch == null) {
                return;
            }
            String escaped = escapeLiteral(resourceMatch);
            String dbStatement = metaJsonString("db.statement");
            filters.append(" AND (COALESCE(NULLIF(`resource`, ''), `name`) = '").append(escaped).append("'")
                    .append(" OR ").append(dbStatement).append(" = '").append(escaped).append("'")
                    .append(" OR ").append(dbStatement).append(" LIKE '%").append(escaped).append("%'")
                    .append(" OR COALESCE(NULLIF(`resource`, ''), `name`) LIKE '%").append(escaped).append("%') ");
            return;
        }
        if (componentType == null || componentType.isBlank() || "service.http".equals(componentType)) {
            String path = callSpanHttpPath(httpMethod, resource);
            if (path == null || path.isBlank()) {
                return;
            }
            String escapedPath = escapeLiteral(path);
            String urlFull = metaJsonString("url.full");
            String httpUrl = metaJsonString("http.url");
            String httpMethodMeta = metaJsonString("http.method");
            filters.append(" AND (COALESCE(NULLIF(`resource`, ''), `name`) LIKE '%")
                    .append(escapedPath).append("%'")
                    .append(" OR COALESCE(NULLIF(`meta.http.url`, ''), '') LIKE '%")
                    .append(escapedPath).append("%'")
                    .append(" OR ").append(urlFull).append(" LIKE '%")
                    .append(escapedPath).append("%'")
                    .append(" OR ").append(httpUrl).append(" LIKE '%")
                    .append(escapedPath).append("%') ");
            if (httpMethod != null && !httpMethod.isBlank()) {
                String escapedMethod = escapeLiteral(httpMethod.trim());
                filters.append(" AND COALESCE(NULLIF(`meta.http.method`, ''), ")
                        .append(httpMethodMeta).append(", '') = '")
                        .append(escapedMethod).append("' ");
            }
            return;
        }
        String resourceMatch = callSpanResourceMatch(httpMethod, resource);
        if (resourceMatch == null) {
            return;
        }
        String escaped = escapeLiteral(resourceMatch);
        filters.append(" AND COALESCE(NULLIF(`resource`, ''), `name`) = '").append(escaped).append("' ");
    }

    private static String callSpanComponentFilter(String componentType) {
        if (componentType == null || componentType.isBlank() || "service.http".equals(componentType)) {
            return """
                     AND (`meta.http.method` IS NOT NULL AND `meta.http.method` != ''
                          OR `meta.http.url` IS NOT NULL AND `meta.http.url` != ''
                          OR `meta.http.status_code` IS NOT NULL) """;
        }
        String dbSystem = metaJsonString("db.system");
        String rpcSystem = metaJsonString("rpc.system");
        String messagingSystem = metaJsonString("messaging.system");
        String configType = metaJsonString("config.type");
        return switch (componentType) {
            case "service.rpc" -> """
                     AND %s IS NOT NULL
                     AND %s != '' """.formatted(rpcSystem, rpcSystem);
            case "service.db" -> """
                     AND %s IS NOT NULL
                     AND %s != ''
                     AND LOWER(%s) NOT LIKE '%%redis%%' """.formatted(dbSystem, dbSystem, dbSystem);
            case "service.redis" -> " AND LOWER(" + dbSystem + ") LIKE '%redis%' ";
            case "service.mq" -> """
                     AND %s IS NOT NULL
                     AND %s != '' """.formatted(messagingSystem, messagingSystem);
            case "service.config" -> """
                     AND (%s IS NOT NULL
                          OR LOWER(%s) LIKE '%%nacos%%'
                          OR LOWER(%s) LIKE '%%apollo%%'
                          OR LOWER(%s) LIKE '%%zookeeper%%'
                          OR LOWER(%s) LIKE '%%consul%%'
                          OR LOWER(%s) LIKE '%%etcd%%'
                          OR LOWER(%s) LIKE '%%config%%') """.formatted(
                    configType, dbSystem, dbSystem, dbSystem, dbSystem, dbSystem, dbSystem);
            default -> "";
        };
    }
}
