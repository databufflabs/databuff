package com.databuff.apm.web.ai.mcp.standard;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolCatalogTest {

    @Test
    void exposesElevenToolsWithSchemas() {
        McpToolCatalog catalog = new McpToolCatalog();

        assertThat(catalog.listTools()).hasSize(11);
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
        assertThat(catalog.findByName("inspectService"))
                .isPresent()
                .get()
                .extracting(McpToolCatalog.McpToolDefinition::implementation)
                .isEqualTo("inspectTools.inspectService");
    }
}
