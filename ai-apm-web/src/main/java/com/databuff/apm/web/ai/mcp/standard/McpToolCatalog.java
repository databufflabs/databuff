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
                        "Query service list from service catalog; optional fromTime/toTime for time-windowed list",
                        schema(Map.of(
                                "keyword", stringProp("Optional service name keyword filter"),
                                "fromTime", stringProp("Query start time"),
                                "toTime", stringProp("Query end time")))),
                tool("queryServicesByServiceType",
                        "Query service list by serviceType from service catalog; optional fromTime/toTime",
                        schema(Map.of(
                                "serviceType", stringProp("Service type filter"),
                                "keyword", stringProp("Optional service name keyword filter"),
                                "size", integerProp("Maximum number of results"),
                                "fromTime", stringProp("Query start time"),
                                "toTime", stringProp("Query end time")))),
                tool("queryServiceTopology",
                        "Query upstream and downstream topology for one service by service name",
                        schema(Map.of(
                                "serviceName", stringProp("Service name"),
                                "serviceInstance", stringProp("Optional service instance"),
                                "fromTime", stringProp("Query start time"),
                                "toTime", stringProp("Query end time")),
                        List.of("serviceName"))),
                tool("queryTraceListByCondition",
                        "Query trace list by service call condition",
                        schema(Map.of(
                                "srcServiceId", stringProp("Source service ID"),
                                "serviceId", stringProp("Target service ID"),
                                "componentType", stringProp("Component type filter"),
                                "resource", stringProp("Resource filter"),
                                "direction", stringProp("Call direction"),
                                "fromTime", stringProp("Query start time"),
                                "toTime", stringProp("Query end time"),
                                "size", integerProp("Maximum number of results")))),
                tool("queryTraceDetail",
                        "Query trace detail by traceId",
                        schema(Map.of("traceId", stringProp("Trace ID")),
                        List.of("traceId"))),
                tool("queryServiceAlarms",
                        "Query alarm data for one service entity",
                        schema(Map.of(
                                "serviceId", stringProp("Service ID"),
                                "status", integerProp("Alarm status filter"),
                                "fromTime", stringProp("Query start time"),
                                "toTime", stringProp("Query end time")))),
                tool("queryMetricData",
                        "Query Doris metric tables by metric_core measurement, field, and tags",
                        schema(Map.of(
                                "queryRequests", arrayProp("Metric query request list"),
                                "size", integerProp("Maximum number of results per query")))),
                tool("inspectService",
                        "Run threshold-free preliminary anomaly inspection for one service",
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

    public record McpToolDefinition(
            String name,
            String description,
            Map<String, Object> inputSchema,
            String implementation) {
    }
}
