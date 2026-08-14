package com.databuff.apm.web.ai.platform.runtime;

import com.databuff.apm.web.ai.platform.tool.AiToolDefinition;
import com.databuff.apm.web.ai.platform.tool.ToolType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end Streamable HTTP coverage for remote MCP registration:
 * initialize → session header → tools/list → tools/call.
 */
class RemoteMcpStreamableHttpIT {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final String AUTH = "Bearer test-token";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FakeStreamableMcpServer server;
    private RemoteMcpToolRegistrar registrar;

    @BeforeEach
    void setUp() throws IOException {
        server = FakeStreamableMcpServer.start();
        registrar = new RemoteMcpToolRegistrar(MAPPER);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void streamableHttpRegistersListsAndCallsToolsWithSessionId() {
        Toolkit toolkit = new Toolkit();
        McpClientWrapper client = registrar.register(toolkit, mcpTool(server.endpointUrl()));

        assertThat(client).as("MCP client should register against Streamable HTTP server").isNotNull();
        assertThat(client.isInitialized()).isTrue();

        List<McpSchema.Tool> tools = client.listTools().block(TIMEOUT);
        assertThat(tools).extracting(McpSchema.Tool::name).contains("jenkins_list_jobs");

        McpSchema.CallToolResult call = client.callTool(
                "jenkins_list_jobs", Map.of("folder", "prod")).block(TIMEOUT);
        assertThat(call).isNotNull();
        assertThat(call.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(call.content()).isNotEmpty();
        assertThat(call.content().get(0)).isInstanceOf(McpSchema.TextContent.class);
        assertThat(((McpSchema.TextContent) call.content().get(0)).text())
                .contains("job-a")
                .contains("job-b");

        assertThat(server.initializeCount.get()).isGreaterThanOrEqualTo(1);
        assertThat(server.toolsListWithSession.get()).isGreaterThanOrEqualTo(1);
        assertThat(server.toolsCallWithSession.get()).isGreaterThanOrEqualTo(1);
        assertThat(server.rejectedMissingSession.get())
                .as("client must send mcp-session-id after initialize")
                .isZero();
        assertThat(server.seenSessionIds).isNotEmpty();
        assertThat(server.authHits.get()).isGreaterThanOrEqualTo(1);

        client.close();
    }

    @Test
    void probeCallsRemoteToolAndReturnsLegalText() {
        Map<String, Object> probed = registrar.probe(
                mcpTool(server.endpointUrl()),
                "echo_ping",
                Map.of("token", "streamable-it"));

        assertThat(probed.get("ok")).as("probe must succeed: %s", probed).isEqualTo(true);
        assertThat(probed.get("initialized")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<String> tools = (List<String>) probed.get("tools");
        assertThat(tools).contains("echo_ping");
        @SuppressWarnings("unchecked")
        Map<String, Object> call = (Map<String, Object>) probed.get("call");
        assertThat(call.get("name")).isEqualTo("echo_ping");
        assertThat(call.get("isError")).isEqualTo(false);
        assertThat(String.valueOf(call.get("text")))
                .as("DataBuff must receive the remote MCP tools/call payload")
                .contains("streamable-it")
                .contains("streamable-executed");
        assertThat(server.toolsCallWithSession.get()).isGreaterThanOrEqualTo(1);
        assertThat(server.authHits.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void streamableHttpFailsWhenAuthHeaderMissing() {
        Toolkit toolkit = new Toolkit();
        AiToolDefinition tool = mcpToolWithoutAuth(server.endpointUrl());
        McpClientWrapper client = registrar.register(toolkit, tool);
        assertThat(client)
                .as("registration should fail when server requires Authorization but config has none")
                .isNull();
    }

    @Test
    void withoutHeaderOrderFixAuthConfigIsDroppedAndProtectedServerFails() throws Exception {
        // Reproduce pre-fix AgentScope call order: headers() before transport.
        McpClientWrapper client = McpClientBuilder.create("jenkins-mcp-legacy-order")
                .timeout(TIMEOUT)
                .initializationTimeout(TIMEOUT)
                .headers(Map.of("Authorization", AUTH))
                .streamableHttpTransport(server.endpointUrl())
                .buildSync();
        try {
            Exception failure = null;
            try {
                client.initialize().block(TIMEOUT);
            } catch (Exception ex) {
                failure = ex;
            }
            assertThat(failure)
                    .as("old order drops Authorization, so protected Streamable HTTP cannot initialize")
                    .isNotNull();
            assertThat(server.initializeCount.get()).isZero();
            assertThat(server.authHits.get()).isZero();
        } finally {
            client.close();
        }
    }

    @Test
    void withoutHeaderOrderFixUnprotectedStreamableHttpStillWorks() throws Exception {
        server.requireAuth = false;
        // Same broken order, but server needs no Authorization → session + tools still OK.
        McpClientWrapper client = McpClientBuilder.create("jenkins-mcp-legacy-noauth")
                .timeout(TIMEOUT)
                .initializationTimeout(TIMEOUT)
                .headers(Map.of("Authorization", AUTH))
                .streamableHttpTransport(server.endpointUrl())
                .buildSync();
        try {
            client.initialize().block(TIMEOUT);
            List<McpSchema.Tool> tools = client.listTools().block(TIMEOUT);
            assertThat(tools).extracting(McpSchema.Tool::name).contains("jenkins_list_jobs");

            McpSchema.CallToolResult call = client.callTool("jenkins_list_jobs", Map.of()).block(TIMEOUT);
            assertThat(((McpSchema.TextContent) call.content().get(0)).text()).contains("job-a");

            assertThat(server.rejectedMissingSession.get()).isZero();
            assertThat(server.seenSessionIds).isNotEmpty();
        } finally {
            client.close();
        }
    }

    private static AiToolDefinition mcpTool(String endpoint) {
        Instant now = Instant.now();
        String configJson = """
                {
                  "endpoint": "%s",
                  "transport": "STREAMABLE_HTTP",
                  "headers": {"Authorization": "%s"}
                }
                """.formatted(endpoint, AUTH);
        return new AiToolDefinition(
                "jenkins-mcp",
                "jenkins-mcp",
                "远程 MCP",
                "fake jenkins mcp",
                ToolType.MCP,
                endpoint,
                "{}",
                configJson,
                true,
                false,
                1L,
                now,
                now);
    }

    private static AiToolDefinition mcpToolWithoutAuth(String endpoint) {
        Instant now = Instant.now();
        String configJson = """
                {
                  "endpoint": "%s",
                  "transport": "STREAMABLE_HTTP",
                  "headers": {}
                }
                """.formatted(endpoint);
        return new AiToolDefinition(
                "jenkins-mcp-noauth",
                "jenkins-mcp-noauth",
                "远程 MCP",
                "fake jenkins mcp",
                ToolType.MCP,
                endpoint,
                "{}",
                configJson,
                true,
                false,
                1L,
                now,
                now);
    }

    /**
     * Minimal Streamable HTTP MCP server: issues {@code Mcp-Session-Id}, requires it for
     * subsequent JSON-RPC, returns 405 on GET (no out-of-band SSE).
     */
    static final class FakeStreamableMcpServer {
        final HttpServer http;
        volatile boolean requireAuth = true;
        final AtomicInteger initializeCount = new AtomicInteger();
        final AtomicInteger toolsListWithSession = new AtomicInteger();
        final AtomicInteger toolsCallWithSession = new AtomicInteger();
        final AtomicInteger rejectedMissingSession = new AtomicInteger();
        final AtomicInteger authHits = new AtomicInteger();
        final Set<String> seenSessionIds = ConcurrentHashMap.newKeySet();
        final List<String> methods = new CopyOnWriteArrayList<>();
        private final Set<String> sessions = ConcurrentHashMap.newKeySet();

        private FakeStreamableMcpServer(HttpServer http) {
            this.http = http;
        }

        static FakeStreamableMcpServer start() throws IOException {
            HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakeStreamableMcpServer server = new FakeStreamableMcpServer(http);
            http.createContext("/mcp", server::handle);
            http.start();
            return server;
        }

        String endpointUrl() {
            return "http://127.0.0.1:" + http.getAddress().getPort() + "/mcp";
        }

        void stop() {
            http.stop(0);
        }

        private void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            try {
                if ("GET".equalsIgnoreCase(method)) {
                    // Client may open optional SSE stream after session init; we are JSON-only.
                    write(exchange, 405, "text/plain", "Method Not Allowed");
                    return;
                }
                if ("DELETE".equalsIgnoreCase(method)) {
                    String sessionId = header(exchange, "mcp-session-id");
                    if (sessionId != null) {
                        sessions.remove(sessionId);
                    }
                    write(exchange, 200, "text/plain", "");
                    return;
                }
                if (!"POST".equalsIgnoreCase(method)) {
                    write(exchange, 405, "text/plain", "Method Not Allowed");
                    return;
                }

                if (requireAuth) {
                    String auth = header(exchange, "Authorization");
                    if (!AUTH.equals(auth)) {
                        writeJson(exchange, 401, Map.of(
                                "jsonrpc", "2.0",
                                "error", Map.of("code", -32000, "message", "unauthorized")));
                        return;
                    }
                    authHits.incrementAndGet();
                }

                String body = readBody(exchange);
                JsonNode root = body.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(body);
                String rpcMethod = root.path("method").asText("");
                methods.add(rpcMethod);
                Object id = root.has("id") && !root.get("id").isNull()
                        ? MAPPER.treeToValue(root.get("id"), Object.class)
                        : null;

                if ("initialize".equals(rpcMethod)) {
                    String sessionId = "sess-" + UUID.randomUUID();
                    sessions.add(sessionId);
                    seenSessionIds.add(sessionId);
                    initializeCount.incrementAndGet();
                    Headers headers = exchange.getResponseHeaders();
                    headers.add("Mcp-Session-Id", sessionId);
                    writeJson(exchange, 200, Map.of(
                            "jsonrpc", "2.0",
                            "id", id,
                            "result", Map.of(
                                    "protocolVersion", "2025-06-18",
                                    "capabilities", Map.of("tools", Map.of()),
                                    "serverInfo", Map.of("name", "fake-jenkins-mcp", "version", "1.0.0"))));
                    return;
                }

                String sessionId = header(exchange, "mcp-session-id");
                if (sessionId == null || !sessions.contains(sessionId)) {
                    rejectedMissingSession.incrementAndGet();
                    writeJson(exchange, 400, Map.of(
                            "jsonrpc", "2.0",
                            "id", id,
                            "error", Map.of("code", -32000, "message", "Session ID required in mcp-session-id header")));
                    return;
                }

                if ("notifications/initialized".equals(rpcMethod) || rpcMethod.startsWith("notifications/")) {
                    // Prefer empty body for notifications (SDK treats Content-Length 0 as OK).
                    exchange.sendResponseHeaders(202, -1);
                    exchange.close();
                    return;
                }

                if ("tools/list".equals(rpcMethod)) {
                    toolsListWithSession.incrementAndGet();
                    writeJson(exchange, 200, Map.of(
                            "jsonrpc", "2.0",
                            "id", id,
                            "result", Map.of(
                                    "tools", List.of(
                                            Map.of(
                                                    "name", "jenkins_list_jobs",
                                                    "description", "List Jenkins jobs",
                                                    "inputSchema", Map.of(
                                                            "type", "object",
                                                            "properties", Map.of(
                                                                    "folder", Map.of("type", "string")))),
                                            Map.of(
                                                    "name", "echo_ping",
                                                    "description", "Echo a token to prove remote execution",
                                                    "inputSchema", Map.of(
                                                            "type", "object",
                                                            "properties", Map.of(
                                                                    "token", Map.of("type", "string"))))))));
                    return;
                }

                if ("tools/call".equals(rpcMethod)) {
                    toolsCallWithSession.incrementAndGet();
                    String toolName = root.path("params").path("name").asText("");
                    String text;
                    if ("echo_ping".equals(toolName)) {
                        String token = root.path("params").path("arguments").path("token").asText("");
                        text = "echo:" + token + ":streamable-executed";
                    } else if ("jenkins_list_jobs".equals(toolName)) {
                        text = "jobs: job-a, job-b";
                    } else {
                        text = "unknown tool: " + toolName;
                    }
                    writeJson(exchange, 200, Map.of(
                            "jsonrpc", "2.0",
                            "id", id,
                            "result", Map.of(
                                    "content", List.of(Map.of("type", "text", "text", text)),
                                    "isError", false)));
                    return;
                }

                writeJson(exchange, 200, Map.of(
                        "jsonrpc", "2.0",
                        "id", id,
                        "error", Map.of("code", -32601, "message", "Method not found: " + rpcMethod)));
            } catch (Exception ex) {
                writeJson(exchange, 500, Map.of(
                        "jsonrpc", "2.0",
                        "error", Map.of("code", -32603, "message", String.valueOf(ex.getMessage()))));
            }
        }

        private static String header(HttpExchange exchange, String name) {
            return exchange.getRequestHeaders().getFirst(name);
        }

        private static String readBody(HttpExchange exchange) throws IOException {
            try (InputStream in = exchange.getRequestBody()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        private static void writeJson(HttpExchange exchange, int status, Object payload) throws IOException {
            byte[] bytes = MAPPER.writeValueAsBytes(payload);
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }

        private static void write(HttpExchange exchange, int status, String contentType, String body)
                throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            } else {
                exchange.close();
            }
        }
    }
}
