package com.databuff.apm.web.ai.platform.runtime;

import com.databuff.apm.web.ai.platform.tool.AiToolDefinition;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class RemoteMcpToolRegistrar {

    private static final Logger log = LoggerFactory.getLogger(RemoteMcpToolRegistrar.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration INIT_TIMEOUT = Duration.ofSeconds(30);

    private final ObjectMapper objectMapper;

    public RemoteMcpToolRegistrar(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public McpClientWrapper register(Toolkit toolkit, AiToolDefinition tool) {
        if (toolkit == null || tool == null) {
            return null;
        }
        McpConnectionConfig config = parseConfig(tool);
        if (config.endpoint().isBlank()) {
            log.warn("MCP tool {} has empty endpoint", tool.toolId());
            return null;
        }
        try {
            McpClientBuilder builder = McpClientBuilder.create(tool.toolId())
                    .timeout(CONNECT_TIMEOUT)
                    .initializationTimeout(INIT_TIMEOUT);
            // AgentScope applies headers onto the transport config; transport must be set first
            // or headers() is a silent no-op.
            if (!applyTransportAndHeaders(builder, config)) {
                return null;
            }
            McpClientWrapper client = builder.buildSync();
            toolkit.registerMcpClient(client).block(CONNECT_TIMEOUT);
            log.info(
                    "Registered MCP client {} at {} via {}",
                    tool.toolId(),
                    config.endpoint(),
                    config.transport());
            return client;
        } catch (Exception ex) {
            log.error(
                    "Failed to register MCP client {} at {}: {}",
                    tool.toolId(),
                    config.endpoint(),
                    ex.getMessage(),
                    ex);
            return null;
        }
    }

    /**
     * Bind transport then headers. Order matters for AgentScope {@link McpClientBuilder}:
     * {@code headers()} is ignored until an HTTP transport exists.
     */
    boolean applyTransportAndHeaders(McpClientBuilder builder, McpConnectionConfig config) {
        if (builder == null || config == null) {
            return false;
        }
        switch (config.transport()) {
            case "SSE" -> builder.sseTransport(config.endpoint());
            case "STREAMABLE_HTTP" -> builder.streamableHttpTransport(config.endpoint());
            default -> {
                log.warn("Unsupported MCP transport {} for tool endpoint {}", config.transport(), config.endpoint());
                return false;
            }
        }
        if (!config.headers().isEmpty()) {
            builder.headers(config.headers());
        }
        return true;
    }

    McpConnectionConfig parseConfig(AiToolDefinition tool) {
        String endpoint = blankToEmpty(tool.implementation());
        String transport = "SSE";
        Map<String, String> headers = new LinkedHashMap<>();
        if (tool.configJson() != null && !tool.configJson().isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(tool.configJson());
                if (root.hasNonNull("endpoint")) {
                    endpoint = root.get("endpoint").asText("").trim();
                }
                if (root.hasNonNull("transport")) {
                    transport = normalizeTransport(root.get("transport").asText(""));
                }
                if (root.has("headers") && root.get("headers").isObject()) {
                    Map<String, String> parsed = objectMapper.convertValue(
                            root.get("headers"), new TypeReference<Map<String, String>>() {});
                    if (parsed != null) {
                        parsed.forEach((key, value) -> {
                            if (key != null && !key.isBlank() && value != null) {
                                headers.put(key.trim(), value);
                            }
                        });
                    }
                }
            } catch (Exception ex) {
                log.warn("Failed to parse MCP config for tool {}: {}", tool.toolId(), ex.getMessage());
            }
        }
        if (endpoint.isBlank()) {
            endpoint = blankToEmpty(tool.implementation());
        }
        return new McpConnectionConfig(endpoint, normalizeTransport(transport), headers);
    }

    static String normalizeTransport(String transport) {
        if (transport == null || transport.isBlank()) {
            return "SSE";
        }
        String normalized = transport.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        if ("HTTP".equals(normalized) || "STREAMABLEHTTP".equals(normalized)) {
            return "STREAMABLE_HTTP";
        }
        return normalized;
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    record McpConnectionConfig(String endpoint, String transport, Map<String, String> headers) {
    }
}
