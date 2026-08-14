package com.databuff.apm.web.ai.platform.runtime;

import com.databuff.apm.web.ai.platform.tool.AiToolDefinition;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Component
public class RemoteMcpToolRegistrar {

    private static final Logger log = LoggerFactory.getLogger(RemoteMcpToolRegistrar.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration INIT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration PROBE_CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration PROBE_INIT_TIMEOUT = Duration.ofSeconds(8);

    private final ObjectMapper objectMapper;

    public RemoteMcpToolRegistrar(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * MCP Java SDK 0.17 resolves {@link McpJsonMapper} via {@code ServiceLoader.load(TCCL)}.
     * {@code expert-task} threads (and Spring Boot nested JARs) can have a TCCL that cannot
     * see {@code mcp-json-jackson2}, which surfaces as "No default McpJsonMapper implementation
     * found" before any HTTP request is sent.
     */
    @PostConstruct
    void warmupMcpJsonMapper() {
        withAppClassLoader(McpJsonMapper::getDefault);
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
            return withAppClassLoader(() -> {
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
            });
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
     * Connect to a remote MCP server, list tools, then {@code tools/call}.
     * {@code ok=true} only when the remote call returns non-empty text and {@code isError} is not true.
     */
    public Map<String, Object> probe(AiToolDefinition tool, String callName, Map<String, Object> arguments) {
        Map<String, Object> out = new LinkedHashMap<>();
        String toolId = tool == null ? "" : tool.toolId();
        out.put("toolId", toolId);
        if (tool == null) {
            out.put("ok", false);
            out.put("error", "tool is null");
            return out;
        }
        McpConnectionConfig config = parseConfig(tool);
        out.put("endpoint", config.endpoint());
        out.put("transport", config.transport());
        if (config.endpoint().isBlank()) {
            out.put("ok", false);
            out.put("error", "MCP tool has empty endpoint");
            return out;
        }
        Toolkit toolkit = new Toolkit();
        McpClientWrapper client = null;
        try {
            client = withAppClassLoader(() -> connect(
                    toolkit, tool, config, PROBE_CONNECT_TIMEOUT, PROBE_INIT_TIMEOUT));
            List<McpSchema.Tool> remoteTools = client.listTools().block(INIT_TIMEOUT);
            List<String> names = new ArrayList<>();
            if (remoteTools != null) {
                for (McpSchema.Tool remoteTool : remoteTools) {
                    if (remoteTool != null && remoteTool.name() != null && !remoteTool.name().isBlank()) {
                        names.add(remoteTool.name());
                    }
                }
            }
            out.put("initialized", client.isInitialized());
            out.put("tools", names);
            String name = callName == null || callName.isBlank()
                    ? (names.isEmpty() ? null : names.get(0))
                    : callName.trim();
            if (name == null) {
                out.put("ok", false);
                out.put("error", "remote MCP listed no tools");
                return out;
            }
            Map<String, Object> args = arguments == null ? Map.of() : arguments;
            McpSchema.CallToolResult result = client.callTool(name, args).block(PROBE_CONNECT_TIMEOUT);
            if (result == null) {
                out.put("ok", false);
                out.put("error", "tools/call returned null");
                return out;
            }
            String text = extractCallText(result);
            boolean error = Boolean.TRUE.equals(result.isError());
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("name", name);
            call.put("isError", error);
            call.put("text", text);
            out.put("call", call);
            boolean ok = !error && text != null && !text.isBlank();
            out.put("ok", ok);
            if (!ok) {
                out.put("error", error ? ("remote MCP tools/call isError: " + text) : "empty tools/call text");
            }
            return out;
        } catch (Exception ex) {
            out.put("ok", false);
            out.put("error", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            return out;
        } finally {
            if (client != null) {
                try {
                    client.close();
                } catch (Exception ignored) {
                    // probe must not leak the client if close is noisy
                }
            }
        }
    }

    private McpClientWrapper connect(
            Toolkit toolkit,
            AiToolDefinition tool,
            McpConnectionConfig config,
            Duration connectTimeout,
            Duration initTimeout) {
        McpClientBuilder builder = McpClientBuilder.create(tool.toolId())
                .timeout(connectTimeout)
                .initializationTimeout(initTimeout);
        if (!applyTransportAndHeaders(builder, config)) {
            throw new IllegalStateException("Unsupported MCP transport " + config.transport());
        }
        McpClientWrapper client = builder.buildSync();
        toolkit.registerMcpClient(client).block(CONNECT_TIMEOUT);
        return client;
    }

    static String extractCallText(McpSchema.CallToolResult result) {
        if (result == null || result.content() == null || result.content().isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent textContent && textContent.text() != null) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(textContent.text());
            }
        }
        return text.toString();
    }

    static <T> T withAppClassLoader(Supplier<T> action) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(RemoteMcpToolRegistrar.class.getClassLoader());
            return action.get();
        } finally {
            thread.setContextClassLoader(previous);
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
