package com.databuff.apm.web.tools.local;

import com.databuff.apm.common.query.TimeSeriesFillUtil;
import com.databuff.apm.common.time.ApmTimeZones;
import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.web.config.ApmStorageProperties;
import com.databuff.apm.web.monitor.service.AlarmService;
import com.databuff.apm.web.portal.ServicePortalService;
import com.databuff.apm.web.portal.TracePortalService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Local APM data tools for the data expert.
 */
@Component
@Lazy
public class DataTools {

    @Autowired
    private ServicePortalService servicePortalService;
    @Autowired
    private TracePortalService tracePortalService;
    @Autowired
    private AlarmService alarmService;
    @Autowired
    private ApmReadRepository readRepository;
    @Autowired
    private ApmStorageProperties storageProperties;
    @Autowired
    private ObjectMapper objectMapper;
    private String metricDatabase;

    @PostConstruct
    void initMetricDatabase() {
        metricDatabase = storageProperties == null ? "databuff" : storageProperties.metricDatabase();
    }

    @Tool(converter = PlainTextToolResultConverter.class, description = "Query service list from the service catalog (meta_service with metric fallback). Optional keyword filters by service name; literal \"null\" means no filter. Defaults to the last 1 hour when fromTime/toTime are omitted; pass both in yyyy-MM-dd HH:mm:ss for a custom window (obtain via getCurrentTimeRange or getTimeRangeAroundTime). Returns up to 20 services. Do not use queryMetricData for service lists.")
    public String queryServicesAll(
            @ToolParam(name = "keyword", required = false, description = "Optional service name keyword; omit or pass null for no filter")
            String keyword,
            @ToolParam(name = "fromTime", required = false, description = "Optional start time; defaults to last 1 hour when omitted, format yyyy-MM-dd HH:mm:ss")
            String fromTime,
            @ToolParam(name = "toTime", required = false, description = "Optional end time; defaults to last 1 hour when omitted, format yyyy-MM-dd HH:mm:ss")
            String toTime) {
        String timeRangeError = validateOptionalTimeRange(fromTime, toTime);
        if (timeRangeError != null) {
            return error(timeRangeError);
        }
        return queryServicesInternal("all", keyword, null, fromTime, toTime);
    }

    @Tool(converter = PlainTextToolResultConverter.class, description = "Query service list by serviceType from the service catalog (meta_service with metric fallback). Supported values: service/web, db, mq, cache, remote. Defaults to the last 1 hour when fromTime/toTime are omitted; pass both in yyyy-MM-dd HH:mm:ss for a custom window. Do not use queryMetricData for service lists.")
    public String queryServicesByServiceType(
            @ToolParam(name = "serviceType", description = "Required service type: service, web, db, mq, cache, remote")
            String serviceType,
            @ToolParam(name = "keyword", required = false, description = "Optional service name keyword; omit or pass null for no filter")
            String keyword,
            @ToolParam(name = "size", required = false, description = "Optional max rows, default 20")
            Integer size,
            @ToolParam(name = "fromTime", required = false, description = "Optional start time; defaults to last 1 hour when omitted, format yyyy-MM-dd HH:mm:ss")
            String fromTime,
            @ToolParam(name = "toTime", required = false, description = "Optional end time; defaults to last 1 hour when omitted, format yyyy-MM-dd HH:mm:ss")
            String toTime) {
        if (isBlank(serviceType)) {
            return error("serviceType is required");
        }
        String normalized = normalizeServiceType(serviceType);
        if (normalized == null) {
            return error("serviceType must be one of service, web, db, mq, cache, remote");
        }
        String timeRangeError = validateOptionalTimeRange(fromTime, toTime);
        if (timeRangeError != null) {
            return error(timeRangeError);
        }
        return queryServicesInternal(normalized, keyword, size, fromTime, toTime);
    }

    private String queryServicesInternal(
            String normalized, String keyword, Integer size, String fromTime, String toTime) {
        Map<String, Object> body = buildServiceListBody(size, keyword, fromTime, toTime);
        if (!"all".equals(normalized)) {
            applyServiceTypeFilter(body, normalized);
        }
        List<Map<String, Object>> rows = servicePortalService.basicServices(body);
        if ("remote".equals(normalized) && rows.isEmpty()) {
            body.put("serviceType", "custom");
            body.remove("serviceTypes");
            rows = servicePortalService.basicServices(body);
        }
        int limit = Math.max(1, Math.min(size == null ? 20 : size, 1000));
        if (rows.size() > limit) {
            rows = List.copyOf(rows.subList(0, limit));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("serviceType", normalized);
        result.put("fromTime", body.get("fromTime"));
        result.put("toTime", body.get("toTime"));
        result.put("total", rows.size());
        result.put("data", rows);
        return json(result);
    }

    private static final long ONE_MINUTE_MS = 60_000L;
    private static final int DEFAULT_SERVICE_LIST_LOOKBACK_MINUTES = 60;

    private static Map<String, Object> buildServiceListBody(
            Integer size, String keyword, String fromTime, String toTime) {
        Map<String, Object> body = pageBody(size == null ? 20 : size);
        putIfNotBlank(body, "serviceName", keyword);
        String effectiveFrom = fromTime;
        String effectiveTo = toTime;
        if (isBlank(fromTime) && isBlank(toTime)) {
            Map<String, String> defaults = defaultServiceListTimeRange();
            effectiveFrom = defaults.get("fromTime");
            effectiveTo = defaults.get("toTime");
        }
        body.put("ignoreTime", 0);
        body.put("fromTime", requireWallClockTime(effectiveFrom, "fromTime"));
        body.put("toTime", requireWallClockTime(effectiveTo, "toTime"));
        return body;
    }

    private static Map<String, String> defaultServiceListTimeRange() {
        long endTime = System.currentTimeMillis() / ONE_MINUTE_MS * ONE_MINUTE_MS;
        long startTime = endTime - DEFAULT_SERVICE_LIST_LOOKBACK_MINUTES * ONE_MINUTE_MS;
        return Map.of(
                "fromTime", ApmTimeZones.formatWallClock(startTime),
                "toTime", ApmTimeZones.formatWallClock(endTime));
    }

    private static void applyServiceTypeFilter(Map<String, Object> body, String normalized) {
        switch (normalized) {
            case "service", "web" -> body.put("serviceTypes", List.of("web", "custom"));
            case "db" -> body.put("serviceType", "db");
            case "mq" -> body.put("serviceType", "mq");
            case "cache" -> body.put("serviceType", "cache");
            case "remote" -> body.put("serviceType", "remote");
            default -> {
            }
        }
    }

    @Tool(converter = PlainTextToolResultConverter.class, description = "Query upstream and downstream topology for one service by service name. fromTime/toTime are required in yyyy-MM-dd HH:mm:ss; obtain them via getCurrentTimeRange or getTimeRangeAroundTime first.")
    public String queryServiceTopology(
            @ToolParam(name = "serviceName", description = "Service name, not serviceId")
            String serviceName,
            @ToolParam(name = "serviceInstance", required = false, description = "Optional service instance")
            String serviceInstance,
            @ToolParam(name = "fromTime", description = "Required start time, format yyyy-MM-dd HH:mm:ss")
            String fromTime,
            @ToolParam(name = "toTime", description = "Required end time, format yyyy-MM-dd HH:mm:ss")
            String toTime) {
        if (isBlank(serviceName)) {
            return error("serviceName is required");
        }
        String timeRangeError = validateTimeRange(fromTime, toTime);
        if (timeRangeError != null) {
            return error(timeRangeError);
        }
        String from = requireWallClockTime(fromTime, "fromTime");
        String to = requireWallClockTime(toTime, "toTime");
        Map<String, Object> body = timeRangeBody(50, from, to);
        body.put("serviceName", serviceName.trim());
        putIfNotBlank(body, "serviceInstance", serviceInstance);
        return json(Map.of(
                "fromTime", from,
                "toTime", to,
                "serviceName", serviceName.trim(),
                "data", serviceTopologyForTool(servicePortalService.getServiceInstanceRelations(body))));
    }

    @Tool(converter = PlainTextToolResultConverter.class, description = "Query trace list for a service call condition. Uses service/call_graph_stats for metric stats and trace/call_spans for trace rows. fromTime/toTime are required in yyyy-MM-dd HH:mm:ss.")
    public String queryTraceListByCondition(
            @ToolParam(name = "srcServiceId", required = false, description = "Optional upstream/source serviceId")
            String srcServiceId,
            @ToolParam(name = "serviceId", required = false, description = "Optional downstream/target serviceId")
            String serviceId,
            @ToolParam(name = "componentType", required = false, description = "Optional componentType, default service.http")
            String componentType,
            @ToolParam(name = "resource", required = false, description = "Optional endpoint/resource/sql/command filter")
            String resource,
            @ToolParam(name = "direction", required = false, description = "Optional direction: in, out, both")
            String direction,
            @ToolParam(name = "fromTime", description = "Required start time, format yyyy-MM-dd HH:mm:ss")
            String fromTime,
            @ToolParam(name = "toTime", description = "Required end time, format yyyy-MM-dd HH:mm:ss")
            String toTime,
            @ToolParam(name = "size", required = false, description = "Optional max trace rows, default 50")
            Integer size) {
        String timeRangeError = validateTimeRange(fromTime, toTime);
        if (timeRangeError != null) {
            return error(timeRangeError);
        }
        String from = requireWallClockTime(fromTime, "fromTime");
        String to = requireWallClockTime(toTime, "toTime");
        Map<String, Object> body = timeRangeBody(size == null ? 50 : size, from, to);
        body.put("componentType", isBlank(componentType) ? "service.http" : componentType.trim());
        putIfNotBlank(body, "srcServiceId", srcServiceId);
        putIfNotBlank(body, "serviceId", serviceId);
        putIfNotBlank(body, "resource", resource);
        applyDirection(body, direction);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("fromTime", from);
        response.put("toTime", to);
        response.put("condition", body);
        response.put("metricStats", servicePortalService.callGraphStats(body));
        response.put("traces", tracePortalService.callSpans(body));
        return json(response);
    }

    @Tool(converter = PlainTextToolResultConverter.class, description = "Execute metric queries with QueryRequest objects, similar to TSDBResultSet executeQuery. Each request has measurement, aggregations, wheres, groupBy, interval, intervalUnit, start, and end. Doris database is fixed by server config.")
    public String queryMetricData(
            @ToolParam(name = "queryRequests", description = "Required list of QueryRequest objects. Use the Doris metric table name directly in measurement, for example metric_service or metric_service_db. Each item has measurement, aggregations (function/field/alias), wheres (field/operator/value), groupBy, interval, intervalUnit, start, and end.")
            List<MetricQueryRequest> queryRequests,
            @ToolParam(name = "size", required = false, description = "Optional max rows per query, default 200")
            Integer size) {
        if (readRepository == null) {
            return error("metric query dependencies are not ready");
        }
        try {
            if (queryRequests == null || queryRequests.isEmpty()) {
                return error("queryRequests must be a non-empty list");
            }
            int limit = Math.max(1, Math.min(size == null ? 200 : size, 1000));
            List<List<Map<String, Object>>> dataResults = new java.util.ArrayList<>();
            for (MetricQueryRequest request : queryRequests) {
                if (request == null) {
                    return error("queryRequests must not contain null items");
                }
                String sql = buildExecuteQuerySql(request, limit);
                List<Map<String, Object>> rows = readRepository.queryRows(sql, limit);
                dataResults.add(fillMetricQueryRows(rows, request));
            }
            return json(dataResults);
        } catch (Exception e) {
            return error("metric query failed: " + e.getMessage());
        }
    }

    @Tool(converter = PlainTextToolResultConverter.class, description = "Query trace detail by traceId. Equivalent to trace/spans; traceId alone is sufficient and no query time range is required.")
    public String queryTraceDetail(
            @ToolParam(name = "traceId", description = "Trace id")
            String traceId) {
        if (isBlank(traceId)) {
            return error("traceId is required");
        }
        Map<String, Object> body = pageBody(1000);
        body.put("traceId", traceId.trim());
        return json(Map.of(
                "traceId", traceId.trim(),
                "data", tracePortalService.traceSpans(body)));
    }

    @Tool(converter = PlainTextToolResultConverter.class, description = "Query alarm data for one service entity. Equivalent to alarm/list. fromTime/toTime are required in yyyy-MM-dd HH:mm:ss.")
    public String queryServiceAlarms(
            @ToolParam(name = "serviceId", description = "Service entity id or name")
            String serviceId,
            @ToolParam(name = "status", required = false, description = "Optional alarm status: 0 open/pending, 1 closed")
            Integer status,
            @ToolParam(name = "fromTime", description = "Required start time, format yyyy-MM-dd HH:mm:ss")
            String fromTime,
            @ToolParam(name = "toTime", description = "Required end time, format yyyy-MM-dd HH:mm:ss")
            String toTime) {
        if (isBlank(serviceId)) {
            return error("serviceId is required");
        }
        String timeRangeError = validateTimeRange(fromTime, toTime);
        if (timeRangeError != null) {
            return error(timeRangeError);
        }
        String from = requireWallClockTime(fromTime, "fromTime");
        String to = requireWallClockTime(toTime, "toTime");
        Map<String, Object> body = timeRangeBody(100, from, to);
        body.put("serviceId", serviceId.trim());
        if (status != null) {
            body.put("status", List.of(status));
        }
        return json(Map.of(
                "fromTime", from,
                "toTime", to,
                "serviceId", serviceId.trim(),
                "data", alarmService.list(body)));
    }

    private static Map<String, Object> pageBody(int size) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("offset", 0);
        body.put("size", Math.max(1, Math.min(size, 1000)));
        body.put("pageSize", Math.max(1, Math.min(size, 1000)));
        body.put("pageNum", 1);
        return body;
    }

    private static Map<String, Object> timeRangeBody(int size, String fromTime, String toTime) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fromTime", fromTime);
        body.put("toTime", toTime);
        body.put("offset", 0);
        body.put("size", Math.max(1, Math.min(size, 1000)));
        body.put("pageSize", Math.max(1, Math.min(size, 1000)));
        body.put("pageNum", 1);
        return body;
    }

    private String buildExecuteQuerySql(MetricQueryRequest request, int limit) {
        String database = safeIdentifier(metricDatabase);
        String table = safeIdentifier(request.getMeasurement());
        String start = requireWallClockTime(request.getStart(), "start");
        String end = requireWallClockTime(request.getEnd(), "end");
        long fromMillis = ApmTimeZones.wallClockToEpochMilli(start);
        long toMillis = ApmTimeZones.wallClockToEpochMilli(end);
        int interval = request.getInterval() == null ? 0 : request.getInterval();
        long bucketMillis = bucketMillis(interval, request.getIntervalUnit());
        List<MetricQueryAggregation> aggregations = aggregationsOf(request);
        List<MetricQueryWhere> wheres = wheresOf(request);
        List<String> groupBy = groupByOf(request).stream()
                .map(DataTools::safeIdentifier)
                .toList();

        List<String> selectParts = new java.util.ArrayList<>();
        List<String> groupParts = new java.util.ArrayList<>();
        if (bucketMillis > 0) {
            selectParts.add("CAST(FLOOR(`ts` / " + bucketMillis + ") * " + (bucketMillis / 1000) + " AS BIGINT) AS epoch_sec");
            groupParts.add("epoch_sec");
        }
        for (String group : groupBy) {
            selectParts.add("`" + group + "`");
            groupParts.add("`" + group + "`");
        }
        if (aggregations.isEmpty()) {
            selectParts.add("*");
        } else {
            for (MetricQueryAggregation aggregation : aggregations) {
                selectParts.add(aggregationExpression(aggregation));
            }
        }

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(String.join(", ", selectParts)).append("\n")
                .append("FROM ").append(database).append(".`").append(table).append("`\n")
                .append("WHERE ").append(metricTsWhere(fromMillis, toMillis));
        for (MetricQueryWhere where : wheres) {
            String clause = whereClause(where);
            if (!clause.isBlank()) {
                sql.append("\n  AND ").append(clause);
            }
        }
        if (!groupParts.isEmpty()) {
            sql.append("\nGROUP BY ").append(String.join(", ", groupParts));
            sql.append("\nORDER BY ").append(groupParts.get(0)).append(" ASC");
        } else if (aggregations.isEmpty()) {
            sql.append("\nORDER BY `ts` DESC");
        }
        sql.append("\nLIMIT ").append(limit);
        return sql.toString();
    }

    private List<Map<String, Object>> fillMetricQueryRows(
            List<Map<String, Object>> rows,
            MetricQueryRequest request) {
        int interval = request.getInterval() == null ? 0 : request.getInterval();
        if (interval <= 0) {
            return rows;
        }
        long bucketSec = bucketMillis(interval, request.getIntervalUnit()) / 1000L;
        String start = requireWallClockTime(request.getStart(), "start");
        String end = requireWallClockTime(request.getEnd(), "end");
        long fromMillis = ApmTimeZones.wallClockToEpochMilli(start);
        long toMillis = ApmTimeZones.wallClockToEpochMilli(end);
        List<String> groupBy = groupByOf(request);
        List<MetricQueryAggregation> aggregations = aggregationsOf(request);
        List<String> metricColumns = metricColumnNames(aggregations, rows, groupBy);
        List<Map<String, Object>> filled = TimeSeriesFillUtil.fillGroupedTimeSeriesRows(
                rows,
                fromMillis,
                toMillis,
                bucketSec,
                "epoch_sec",
                groupBy,
                metricColumns);
        return MetricToolResultFormat.formatEpochSecRows(filled);
    }

    private static List<String> metricColumnNames(
            List<MetricQueryAggregation> aggregations,
            List<Map<String, Object>> rows,
            List<String> groupBy) {
        List<String> names = new java.util.ArrayList<>();
        for (MetricQueryAggregation aggregation : aggregations) {
            String alias = aggregation.getAlias();
            if (!isBlank(alias)) {
                names.add(alias);
            }
        }
        if (!names.isEmpty()) {
            return names;
        }
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Set<String> excluded = new LinkedHashSet<>(groupBy);
        excluded.add("epoch_sec");
        return rows.get(0).keySet().stream()
                .filter(key -> !excluded.contains(key))
                .toList();
    }

    private static String aggregationExpression(MetricQueryAggregation aggregation) {
        String field = safeIdentifier(aggregation.getField());
        String function = aggregation.getFunction();
        String alias = aggregation.getAlias();
        String expression = isBlank(function)
                ? "`" + field + "`"
                : function.trim().toUpperCase(Locale.ROOT) + "(`" + field + "`)";
        if (!isBlank(alias)) {
            expression += " AS `" + safeIdentifier(alias) + "`";
        }
        return expression;
    }

    private String whereClause(MetricQueryWhere where) {
        String field = safeIdentifier(where.getField());
        String operator = normalizeWhereOperator(where.getOperator());
        Object value = where.getValue();
        if ("IS NULL".equals(operator) || "IS NOT NULL".equals(operator)) {
            return "`" + field + "` " + operator;
        }
        if ("IN".equals(operator) || "NOT IN".equals(operator)) {
            List<String> values = valueList(value);
            if (values.isEmpty()) {
                return "";
            }
            return "`" + field + "` " + operator + " ("
                    + values.stream().map(item -> "'" + escapeLiteral(item) + "'")
                    .collect(java.util.stream.Collectors.joining(","))
                    + ")";
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return "";
        }
        return "`" + field + "` " + operator + " '" + escapeLiteral(String.valueOf(value)) + "'";
    }

    private static String normalizeWhereOperator(String operator) {
        if (isBlank(operator)) {
            return "=";
        }
        return switch (operator.trim().toUpperCase(Locale.ROOT)) {
            case "EQ", "=" -> "=";
            case "NEQ", "!=" -> "!=";
            case "GT", ">" -> ">";
            case "GTE", ">=" -> ">=";
            case "LT", "<" -> "<";
            case "LTE", "<=" -> "<=";
            case "LIKE" -> "LIKE";
            case "NOT_LIKE", "NOTLIKE", "NOT LIKE" -> "NOT LIKE";
            case "IN", "INLIST" -> "IN";
            case "NOT_IN", "NOTINLIST", "NOT IN" -> "NOT IN";
            case "IS", "EMPTY", "IS NULL" -> "IS NULL";
            case "IS_NOT", "NOTEMPTY", "IS NOT NULL" -> "IS NOT NULL";
            default -> "=";
        };
    }

    private static long bucketMillis(int interval, String unit) {
        if (interval <= 0) {
            return 0;
        }
        String normalized = isBlank(unit) ? "s" : unit.trim().toLowerCase(Locale.ROOT);
        long unitMillis = switch (normalized) {
            case "ms", "millisecond", "milliseconds" -> 1L;
            case "m", "min", "minute", "minutes" -> 60_000L;
            case "h", "hour", "hours" -> 3_600_000L;
            default -> 1_000L;
        };
        return Math.max(1L, interval * unitMillis);
    }

    private static List<MetricQueryAggregation> aggregationsOf(MetricQueryRequest request) {
        return request.getAggregations() == null ? List.of() : request.getAggregations();
    }

    private static List<MetricQueryWhere> wheresOf(MetricQueryRequest request) {
        return request.getWheres() == null ? List.of() : request.getWheres();
    }

    private static List<String> groupByOf(MetricQueryRequest request) {
        if (request.getGroupBy() == null) {
            return List.of();
        }
        return request.getGroupBy().stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .toList();
    }

    private List<String> valueList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(item -> item != null && !String.valueOf(item).isBlank())
                    .map(item -> String.valueOf(item).trim())
                    .toList();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return List.of();
        }
        String text = String.valueOf(value).trim();
        if (text.startsWith("[") && text.endsWith("]")) {
            try {
                List<?> parsed = objectMapper.readValue(text, List.class);
                return parsed.stream()
                        .filter(item -> item != null && !String.valueOf(item).isBlank())
                        .map(item -> String.valueOf(item).trim())
                        .toList();
            } catch (JsonProcessingException ignored) {
                // fall through to treat as a single literal
            }
        }
        return List.of(text);
    }

    private static String validateTimeRange(String fromTime, String toTime) {
        if (isBlank(fromTime) && isBlank(toTime)) {
            return "fromTime and toTime are required in yyyy-MM-dd HH:mm:ss; call getCurrentTimeRange or getTimeRangeAroundTime first";
        }
        if (isBlank(fromTime)) {
            return "fromTime is required in yyyy-MM-dd HH:mm:ss";
        }
        if (isBlank(toTime)) {
            return "toTime is required in yyyy-MM-dd HH:mm:ss";
        }
        return null;
    }

    private static String validateOptionalTimeRange(String fromTime, String toTime) {
        if (isBlank(fromTime) && isBlank(toTime)) {
            return null;
        }
        return validateTimeRange(fromTime, toTime);
    }

    private static String requireWallClockTime(String value, String fieldName) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(fieldName + " is required in yyyy-MM-dd HH:mm:ss format");
        }
        return ApmTimeZones.normalizeWallClockText(value);
    }

    private static String safeIdentifier(String value) {
        if (!isSafeIdentifier(value)) {
            throw new IllegalArgumentException("unsafe identifier: " + value);
        }
        return value;
    }

    private static boolean isSafeIdentifier(String value) {
        return value != null && value.matches("[A-Za-z0-9_]+");
    }

    private static String escapeLiteral(String value) {
        return value == null ? "" : value.replace("'", "''");
    }

    private static String firstNonBlank(String first, String second) {
        return isBlank(first) ? second : first;
    }

    private static String metricTsWhere(long fromMillis, long toMillis) {
        return "`ts` >= " + fromMillis + " AND `ts` < " + toMillis;
    }

    private static Map<String, Object> serviceTopologyForTool(Map<String, Object> raw) {
        Map<String, Object> data = new LinkedHashMap<>(raw);
        data.put("upflowServiceStats", convertTopologyTimeFields(raw.get("upflowServiceStats")));
        data.put("downflowServiceStats", convertTopologyTimeFields(raw.get("downflowServiceStats")));
        return data;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> convertTopologyTimeFields(Object stats) {
        if (!(stats instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> converted = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> row)) {
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : row.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            convertNsTimeFieldToMs(copy, "reqInTime");
            convertNsTimeFieldToMs(copy, "reqOutTime");
            converted.add(copy);
        }
        return converted;
    }

    private static void convertNsTimeFieldToMs(Map<String, Object> row, String field) {
        Object value = row.get(field);
        if (value instanceof Number number) {
            row.put(field, number.doubleValue() / 1_000_000.0);
        }
    }

    private static String normalizeServiceType(String serviceType) {
        if (isBlank(serviceType)) {
            return null;
        }
        String normalized = serviceType.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "service", "web", "db", "mq", "cache", "remote" -> normalized;
            default -> null;
        };
    }

    private static void applyDirection(Map<String, Object> body, String direction) {
        if (isBlank(direction)) {
            return;
        }
        switch (direction.trim().toLowerCase(Locale.ROOT)) {
            case "in", "upstream" -> body.put("isIn", 1);
            case "out", "downstream" -> body.put("isOut", 1);
            case "both" -> {
                body.put("isIn", 1);
                body.put("isOut", 1);
            }
            default -> {
            }
        }
    }

    private static void putIfNotBlank(Map<String, Object> body, String key, String value) {
        if (!isBlank(value)) {
            body.put(key, value.trim());
        }
    }

    private static boolean isBlank(String value) {
        if (value == null || value.trim().isEmpty()) {
            return true;
        }
        String normalized = value.trim();
        return "null".equalsIgnoreCase(normalized) || "undefined".equalsIgnoreCase(normalized);
    }

    private String error(String message) {
        return json(Map.of(
                "ok", false,
                "message", message));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }
}
