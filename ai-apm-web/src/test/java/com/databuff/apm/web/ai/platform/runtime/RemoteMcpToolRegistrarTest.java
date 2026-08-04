package com.databuff.apm.web.ai.platform.runtime;

import com.databuff.apm.web.ai.platform.tool.AiToolDefinition;
import com.databuff.apm.web.ai.platform.tool.ToolType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteMcpToolRegistrarTest {

    private RemoteMcpToolRegistrar registrar;

    @BeforeEach
    void setUp() {
        registrar = new RemoteMcpToolRegistrar(new ObjectMapper());
    }

    @Test
    void parsesEndpointTransportAndHeadersFromConfigJson() {
        RemoteMcpToolRegistrar.McpConnectionConfig config = registrar.parseConfig(mcpTool(
                "remote.metrics",
                "https://ignored.example/mcp",
                """
                {
                  "endpoint": "https://mcp.example/sse",
                  "transport": "Streamable HTTP",
                  "headers": {"Authorization": "Bearer token"}
                }
                """));

        assertThat(config.endpoint()).isEqualTo("https://mcp.example/sse");
        assertThat(config.transport()).isEqualTo("STREAMABLE_HTTP");
        assertThat(config.headers()).containsEntry("Authorization", "Bearer token");
    }

    @Test
    void fallsBackToImplementationWhenConfigEndpointMissing() {
        RemoteMcpToolRegistrar.McpConnectionConfig config = registrar.parseConfig(mcpTool(
                "remote.metrics",
                "https://mcp.example/sse",
                "{}"));

        assertThat(config.endpoint()).isEqualTo("https://mcp.example/sse");
        assertThat(config.transport()).isEqualTo("SSE");
    }

    @Test
    void normalizesTransportAliases() {
        assertThat(RemoteMcpToolRegistrar.normalizeTransport("sse")).isEqualTo("SSE");
        assertThat(RemoteMcpToolRegistrar.normalizeTransport("streamable-http")).isEqualTo("STREAMABLE_HTTP");
        assertThat(RemoteMcpToolRegistrar.normalizeTransport("HTTP")).isEqualTo("STREAMABLE_HTTP");
    }

    @Test
    void agentscopeDropsHeadersWhenSetBeforeTransport() throws Exception {
        // Documents the AgentScope quirk that previously bit RemoteMcpToolRegistrar:
        // headers() before sse/streamableHttpTransport is a silent no-op.
        McpClientBuilder builder = McpClientBuilder.create("jenkins-mcp")
                .headers(Map.of("Authorization", "Bearer token"))
                .streamableHttpTransport("http://127.0.0.1:9/mcp");

        assertThat(readTransportHeaders(builder))
                .as("headers applied before transport must be dropped by AgentScope")
                .doesNotContainKey("Authorization");
    }

    @Test
    void applyTransportAndHeadersKeepsConfiguredAuthHeader() throws Exception {
        RemoteMcpToolRegistrar.McpConnectionConfig config = new RemoteMcpToolRegistrar.McpConnectionConfig(
                "http://127.0.0.1:9/mcp",
                "STREAMABLE_HTTP",
                Map.of("Authorization", "Bearer token"));

        McpClientBuilder builder = McpClientBuilder.create("jenkins-mcp");
        assertThat(registrar.applyTransportAndHeaders(builder, config)).isTrue();

        assertThat(readTransportHeaders(builder))
                .containsEntry("Authorization", "Bearer token");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> readTransportHeaders(McpClientBuilder builder) throws Exception {
        Field transportField = McpClientBuilder.class.getDeclaredField("transportConfig");
        transportField.setAccessible(true);
        Object transportConfig = transportField.get(builder);
        assertThat(transportConfig).isNotNull();

        Field headersField = transportConfig.getClass().getSuperclass().getDeclaredField("headers");
        headersField.setAccessible(true);
        return (Map<String, String>) headersField.get(transportConfig);
    }

    private static AiToolDefinition mcpTool(String toolId, String implementation, String configJson) {
        Instant now = Instant.now();
        return new AiToolDefinition(
                toolId,
                toolId,
                "远程 MCP",
                "desc",
                ToolType.MCP,
                implementation,
                "{}",
                configJson,
                true,
                false,
                1L,
                now,
                now);
    }
}
