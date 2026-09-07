package com.databuff.apm.web.ai.mcp.standard;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolCatalogTest {

    @Test
    void exposesFifteenToolsWithSchemas() {
        McpToolCatalog catalog = new McpToolCatalog();

        assertThat(catalog.listTools()).hasSize(15);
        assertThat(catalog.listTools())
                .extracting(McpToolCatalog.McpToolDefinition::name)
                .containsExactly(
                        "getCurrentTimeRange",
                        "getTimeRangeAroundTime",
                        "drawTrendCharts",
                        "queryServicesAll",
                        "queryServicesByServiceType",
                        "queryServiceTopology",
                        "queryTraceListByCondition",
                        "queryTraceDetail",
                        "queryServiceAlarms",
                        "queryMetricData",
                        "queryLogTrend",
                        "queryLogDetail",
                        "queryLogsByTraceId",
                        "queryLogsBySpanId",
                        "inspectService");
        assertThat(catalog.listTools()).allSatisfy(tool -> {
            assertThat(tool.description()).isNotBlank();
            assertThat(tool.implementation()).isNotBlank();
            assertThat(tool.inputSchema()).isNotEmpty();
            assertThat(tool.inputSchema()).containsEntry("type", "object");
            assertThat(tool.inputSchema().get("properties")).isInstanceOf(Map.class);
        });
    }

    @Test
    void mapsToolNamesToImplementations() {
        McpToolCatalog catalog = new McpToolCatalog();

        assertThat(catalog.findByName("queryServicesAll"))
                .isPresent()
                .get()
                .extracting(McpToolCatalog.McpToolDefinition::implementation)
                .isEqualTo("dataTools.queryServicesAll");
        assertThat(catalog.findByName("queryLogDetail"))
                .isPresent()
                .get()
                .extracting(McpToolCatalog.McpToolDefinition::implementation)
                .isEqualTo("logTools.queryLogDetail");
        assertThat(catalog.findByName("inspectService"))
                .isPresent()
                .get()
                .extracting(McpToolCatalog.McpToolDefinition::implementation)
                .isEqualTo("inspectTools.inspectService");
    }
    @Test
    void metricSchemaExplainsNestedRequestsAndRequiredTimes() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var schema = mapper.valueToTree(new McpToolCatalog().findByName("queryMetricData").orElseThrow().inputSchema());
        assertThat(schema.path("required").toString()).contains("queryRequests");
        var requests = schema.path("properties").path("queryRequests");
        assertThat(requests.path("minItems").asInt()).isEqualTo(1);
        var item = requests.path("items");
        assertThat(item.path("required").toString()).contains("measurement", "start", "end");
        assertThat(item.path("additionalProperties").asBoolean()).isFalse();
        assertThat(item.path("properties").path("measurement").path("description").asText()).contains("metric_service", "reqCount");
        assertThat(item.path("properties").path("aggregations").path("items").path("properties").has("field")).isTrue();
        assertThat(item.path("properties").path("wheres").path("items").path("properties").has("value")).isTrue();
        var example = requests.path("examples").get(0).get(0);
        assertThat(example.path("measurement").asText()).isEqualTo("metric_service");
        assertThat(item.path("properties").has("size")).isFalse();
    }

    @Test
    void topologyAndAlarmSchemasDeclareActualRequiredArguments() {
        var catalog = new McpToolCatalog();
        assertThat(catalog.findByName("queryServiceTopology").orElseThrow().inputSchema().get("required"))
                .isEqualTo(java.util.List.of("serviceName", "fromTime", "toTime"));
        assertThat(catalog.findByName("queryServiceAlarms").orElseThrow().inputSchema().get("required"))
                .isEqualTo(java.util.List.of("serviceId", "fromTime", "toTime"));
    }

}
