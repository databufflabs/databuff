package com.databuff.apm.web.ai.mcp.standard;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class McpToolCatalog {

    private final List<McpToolDefinition> tools;

    public McpToolCatalog() {
        this.tools = List.of(
                tool("getCurrentTimeRange",
                        "Get current query time range",
                        schema(Map.of("rangeMinutes", integerProp("Minutes to look back from now")))),
                tool("getTimeRangeAroundTime",
                        "Get query time range around a HH:mm target time",
                        schema(Map.of("targetTime", stringProp("Target time in HH:mm format")))),
                tool("drawTrendCharts",
                        "Draw multiple trend charts from queried metric data",
                        schema(Map.of("charts", arrayProp("Trend chart specifications")))),
                tool("queryServicesAll",
                        "Query service list from service catalog, at most 20 results. Omitted times default to the last hour; not an unlimited directory. Pass both fromTime/toTime for a custom window",
                        schema(Map.of(
                                "keyword", stringProp("Optional service name keyword filter"),
                                "fromTime", stringProp("Query start time in Asia/Shanghai yyyy-MM-dd HH:mm:ss; pass both fromTime and toTime"),
                                "toTime", stringProp("Query end time in Asia/Shanghai yyyy-MM-dd HH:mm:ss; pass both fromTime and toTime")))),
                tool("queryServicesByServiceType",
                        "Query service list by serviceType (service/web, db, mq, cache, remote). Omitted times default to the last hour; pass both fromTime/toTime for a custom window",
                        schema(Map.of(
                                "serviceType", Map.of("type", "string", "enum", List.of("service", "web", "db", "mq", "cache", "remote"), "description", "Required service type"),
                                "keyword", stringProp("Optional service name keyword filter"),
                                "size", integerProp("Maximum number of results"),
                                "fromTime", stringProp("Query start time in Asia/Shanghai yyyy-MM-dd HH:mm:ss; pass both fromTime and toTime"),
                                "toTime", stringProp("Query end time in Asia/Shanghai yyyy-MM-dd HH:mm:ss; pass both fromTime and toTime")), List.of("serviceType"))),
                tool("queryServiceTopology",
                        "Query upstream and downstream topology for one service by service name",
                        schema(Map.of(
                                "serviceName", stringProp("Service name"),
                                "serviceInstance", stringProp("Optional service instance"),
                                "fromTime", stringProp("Query start time in Asia/Shanghai yyyy-MM-dd HH:mm:ss; pass both fromTime and toTime"),
                                "toTime", stringProp("Query end time in Asia/Shanghai yyyy-MM-dd HH:mm:ss; pass both fromTime and toTime")),
                        List.of("serviceName", "fromTime", "toTime"))),
                tool("queryTraceListByCondition",
                        "Query trace list by service call condition",
                        schema(Map.of(
                                "srcServiceId", stringProp("Source service ID"),
                                "serviceId", stringProp("Target service ID"),
                                "componentType", stringProp("Component type filter"),
                                "resource", stringProp("Resource filter"),
                                "direction", stringProp("Call direction"),
                                "fromTime", stringProp("Query start time in Asia/Shanghai yyyy-MM-dd HH:mm:ss; pass both fromTime and toTime"),
                                "toTime", stringProp("Query end time in Asia/Shanghai yyyy-MM-dd HH:mm:ss; pass both fromTime and toTime"),
                                "size", integerProp("Maximum number of results")), List.of("fromTime", "toTime"))),
                tool("queryTraceDetail",
                        "Query trace detail by traceId",
                        schema(Map.of("traceId", stringProp("Trace ID")),
                        List.of("traceId"))),
                tool("queryServiceAlarms",
                        "Query alarm data for one service entity",
                        schema(Map.of(
                                "serviceId", stringProp("Service ID"),
                                "status", integerProp("Optional alarm status: 0 open/pending, 1 closed"),
                                "fromTime", stringProp("Query start time in Asia/Shanghai yyyy-MM-dd HH:mm:ss; pass both fromTime and toTime"),
                                "toTime", stringProp("Query end time in Asia/Shanghai yyyy-MM-dd HH:mm:ss; pass both fromTime and toTime")), List.of("serviceId", "fromTime", "toTime"))),
                tool("queryMetricData",
                        "Query real Doris metric tables (e.g. metric_service). measurement is a table name, never reqCount or a metric alias. Use documented fields; do not guess table names after a missing-table error. Requests use start/end, not fromTime/toTime. size belongs at the tool top level",
                        schema(Map.of(
                                "queryRequests", metricRequestsSchema(),
                                "size", integerProp("Maximum rows per query, default 200, capped at 1000")), List.of("queryRequests"))),
                tool("queryLogTrend",
                        "Query log volume trend by service, service instance, severity, or keyword",
                        schema(logTrendSchema(), List.of("fromTime", "toTime"))),
                tool("queryLogDetail",
                        "Query paginated log detail lines; do not pass traceId or spanId",
                        schema(logDetailSchema(), List.of("fromTime", "toTime"))),
                tool("queryLogsByTraceId",
                        "Query paginated log lines for one traceId",
                        schema(Map.of(
                                "traceId", stringProp("Trace ID"),
                                "severities", stringArrayProp("Optional severity filters"),
                                "query", stringProp("Optional body keyword"),
                                "fromTime", stringProp("Optional start time, defaults to last 24 hours"),
                                "toTime", stringProp("Optional end time, defaults to now"),
                                "offset", integerProp("Pagination offset"),
                                "size", integerProp("Page size, max 200")),
                        List.of("traceId"))),
                tool("queryLogsBySpanId",
                        "Query paginated log lines for one spanId; pass traceId when known",
                        schema(Map.of(
                                "spanId", stringProp("Span ID"),
                                "traceId", stringProp("Optional but recommended trace ID"),
                                "severities", stringArrayProp("Optional severity filters"),
                                "query", stringProp("Optional body keyword"),
                                "fromTime", stringProp("Optional start time, defaults to last 24 hours"),
                                "toTime", stringProp("Optional end time, defaults to now"),
                                "offset", integerProp("Pagination offset"),
                                "size", integerProp("Page size, max 200")),
                        List.of("spanId"))),
                tool("inspectService",
                        "Inspect one service over a fixed last-hour window; no custom time input. For a user-selected window use explicit metric queries. Returns entry metrics, ERROR/WARN/keyword logs, alarms, dependencies, error traces, instances; web also checks exception/JVM/CPU/memory",
                        schema(Map.of("serviceName", stringProp("Service name to inspect")),
                        List.of("serviceName"))));
    }

    public List<McpToolDefinition> listTools() {
        return tools;
    }

    public Optional<McpToolDefinition> findByName(String name) {
        return tools.stream().filter(tool -> tool.name().equals(name)).findFirst();
    }

    private static McpToolDefinition tool(String name, String description, Map<String, Object> inputSchema) {
        return new McpToolDefinition(name, description, inputSchema, implementationFor(name));
    }

    private static Map<String, Object> logTrendSchema() {
        Map<String, Object> props = new LinkedHashMap<>(logFilterSchema());
        props.put("interval", integerProp("Optional bucket interval in seconds, default 60"));
        return props;
    }

    private static Map<String, Object> logDetailSchema() {
        Map<String, Object> props = new LinkedHashMap<>(logFilterSchema());
        props.put("offset", integerProp("Pagination offset, default 0"));
        props.put("size", integerProp("Page size, default 50, max 200"));
        return props;
    }

    private static Map<String, Object> logFilterSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("fromTime", stringProp("Query start time in yyyy-MM-dd HH:mm:ss"));
        props.put("toTime", stringProp("Query end time in yyyy-MM-dd HH:mm:ss"));
        props.put("services", stringArrayProp("Optional service names"));
        props.put("serviceIds", stringArrayProp("Optional service IDs"));
        props.put("serviceInstances", stringArrayProp("Optional service instance IDs (OTel service.instance.id)"));
        props.put("severities", stringArrayProp("Optional severity filters such as ERROR, WARN, INFO"));
        props.put("query", stringProp("Optional body keyword"));
        return props;
    }

    private static Map<String, Object> metricRequestsSchema() {
        Map<String, Object> aggregation = schema(Map.of(
                "function", Map.of("type", "string", "description", "Supported aggregate function; omit for a raw field", "enum", List.of("SUM", "AVG", "MAX", "MIN", "COUNT")),
                "field", stringProp("Documented table field: cnt, error or sumDuration for metric_service"),
                "alias", stringProp("Result column alias, e.g. total_cnt")), List.of("field"));
        Map<String, Object> where = schema(Map.of(
                "field", stringProp("Documented tag column, e.g. service or service_id; do not guess"),
                "operator", stringProp("Default =; supports =, !=, >, >=, <, <=, LIKE, NOT LIKE, IN/INLIST, NOT IN, IS NULL, IS NOT NULL"),
                "value", Map.of("description", "Filter value; IN/INLIST requires a JSON array, never a JSON-encoded string")), List.of("field"));
        Map<String, Object> item = schema(Map.of(
                "measurement", stringProp("Real Doris table name: metric_service for service metrics, metric_service_http/rpc/db/redis/mq for call metrics. Never an alias such as reqCount"),
                "aggregations", Map.of("type", "array", "items", aggregation),
                "wheres", Map.of("type", "array", "items", where),
                "groupBy", stringArrayProp("Documented tag columns, e.g. service or service_id"),
                "interval", Map.of("type", "integer", "minimum", 0, "description", "0 or omitted: aggregate; for a trend use 1 with intervalUnit=m"),
                "intervalUnit", Map.of("type", "string", "enum", List.of("s", "m", "h", "ms"), "description", "Default s"),
                "start", stringProp("Required start in Asia/Shanghai yyyy-MM-dd HH:mm:ss"),
                "end", stringProp("Required end in Asia/Shanghai yyyy-MM-dd HH:mm:ss")), List.of("measurement", "start", "end"));
        item.put("additionalProperties", false);
        return Map.of("type", "array", "minItems", 1, "description", "Non-empty QueryRequest object array; not a JSON string. For request/error/duration use SUM(cnt), SUM(error), SUM(sumDuration); average duration is summed duration divided by count", "items", item,
                "examples", List.of(List.of(Map.of(
                        "measurement", "metric_service",
                        "aggregations", List.of(Map.of("function", "SUM", "field", "cnt", "alias", "total_cnt")),
                        "wheres", List.of(Map.of("field", "service", "operator", "=", "value", "service-a")),
                        "start", "2026-09-07 16:00:00", "end", "2026-09-07 17:00:00"))));
    }

    private static String implementationFor(String name) {
        return switch (name) {
            case "getCurrentTimeRange" -> "commonTools.getCurrentTimeRange";
            case "getTimeRangeAroundTime" -> "commonTools.getTimeRangeAroundTime";
            case "drawTrendCharts" -> "commonTools.drawTrendCharts";
            case "queryServicesAll" -> "dataTools.queryServicesAll";
            case "queryServicesByServiceType" -> "dataTools.queryServicesByServiceType";
            case "queryServiceTopology" -> "dataTools.queryServiceTopology";
            case "queryTraceListByCondition" -> "dataTools.queryTraceListByCondition";
            case "queryTraceDetail" -> "dataTools.queryTraceDetail";
            case "queryServiceAlarms" -> "dataTools.queryServiceAlarms";
            case "queryMetricData" -> "dataTools.queryMetricData";
            case "queryLogTrend" -> "logTools.queryLogTrend";
            case "queryLogDetail" -> "logTools.queryLogDetail";
            case "queryLogsByTraceId" -> "logTools.queryLogsByTraceId";
            case "queryLogsBySpanId" -> "logTools.queryLogsBySpanId";
            case "inspectService" -> "inspectTools.inspectService";
            default -> throw new IllegalArgumentException("unknown MCP tool: " + name);
        };
    }

    private static Map<String, Object> schema(Map<String, Object> properties) {
        return schema(properties, List.of());
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    private static Map<String, Object> stringProp(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> integerProp(String description) {
        return Map.of("type", "integer", "description", description);
    }

    private static Map<String, Object> arrayProp(String description) {
        return Map.of("type", "array", "description", description);
    }

    private static Map<String, Object> stringArrayProp(String description) {
        return Map.of(
                "type", "array",
                "description", description,
                "items", Map.of("type", "string"));
    }

    public record McpToolDefinition(
            String name,
            String description,
            Map<String, Object> inputSchema,
            String implementation) {
    }
}
