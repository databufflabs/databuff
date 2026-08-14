package com.databuff.apm.web.ai.platform.runtime;

import com.databuff.apm.web.ai.platform.tool.AiToolDefinition;
import com.databuff.apm.web.ai.platform.tool.ToolType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end SSE coverage: Authorization header, endpoint event, tools/list, tools/call.
 */
class RemoteMcpSseIT {

    private static final String AUTH = "Bearer test-token";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FakeSseMcpServer server;
    private RemoteMcpToolRegistrar registrar;

    @BeforeEach
    void setUp() throws IOException {
        server = FakeSseMcpServer.start();
        registrar = new RemoteMcpToolRegistrar(MAPPER);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void sseRegistersListsAndCallsToolsWithAuthHeader() {
        Toolkit toolkit = new Toolkit();
        McpClientWrapper client = registrar.register(toolkit, mcpTool(server.sseUrl()));
        assertThat(client).as("MCP SSE client should register").isNotNull();
        assertThat(client.isInitialized()).isTrue();

        Map<String, Object> probed = registrar.probe(
                mcpTool(server.sseUrl()),
                "echo_ping",
                Map.of("token", "sse-it"));
        assertThat(probed.get("ok")).as("probe must succeed: %s", probed).isEqualTo(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> call = (Map<String, Object>) probed.get("call");
        assertThat(call.get("isError")).isEqualTo(false);
        assertThat(String.valueOf(call.get("text")))
                .contains("sse-it")
                .contains("sse-executed");
        assertThat(server.toolsCallCount.get()).isGreaterThanOrEqualTo(1);
        assertThat(server.authHits.get()).isGreaterThanOrEqualTo(1);
        if (client != null) {
            client.close();
        }
    }

    @Test
    void sseFailsWhenAuthHeaderMissing() {
        Map<String, Object> probed = registrar.probe(mcpToolWithoutAuth(server.sseUrl()), "echo_ping", Map.of());
        assertThat(probed.get("ok")).as("missing Authorization must fail: %s", probed).isEqualTo(false);
        assertThat(server.toolsCallCount.get()).isZero();
    }

    private static AiToolDefinition mcpTool(String endpoint) {
        Instant now = Instant.now();
        String configJson = """
                {
                  "endpoint": "%s",
                  "transport": "SSE",
                  "headers": {"Authorization": "%s"}
                }
                """.formatted(endpoint, AUTH);
        return new AiToolDefinition(
                "sse-mcp", "sse-mcp", "远程 MCP", "fake sse mcp",
                ToolType.MCP, endpoint, "{}", configJson, true, false, 1L, now, now);
    }

    private static AiToolDefinition mcpToolWithoutAuth(String endpoint) {
        Instant now = Instant.now();
        String configJson = """
                {
                  "endpoint": "%s",
                  "transport": "SSE",
                  "headers": {}
                }
                """.formatted(endpoint);
        return new AiToolDefinition(
                "sse-mcp-noauth", "sse-mcp-noauth", "远程 MCP", "fake sse mcp",
                ToolType.MCP, endpoint, "{}", configJson, true, false, 1L, now, now);
    }

    static final class FakeSseMcpServer {
        final HttpServer http;
        final AtomicBoolean stopping = new AtomicBoolean(false);
        final AtomicInteger authHits = new AtomicInteger();
        final AtomicInteger toolsCallCount = new AtomicInteger();
        final ConcurrentHashMap<String, BlockingQueue<String>> sessions = new ConcurrentHashMap<>();

        private FakeSseMcpServer(HttpServer http) {
            this.http = http;
        }

        static FakeSseMcpServer start() throws IOException {
            HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakeSseMcpServer server = new FakeSseMcpServer(http);
            http.createContext("/sse", server::handleSse);
            http.createContext("/message", server::handleMessage);
            http.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            http.start();
            return server;
        }

        String sseUrl() {
            return "http://127.0.0.1:" + http.getAddress().getPort() + "/sse";
        }

        void stop() {
            stopping.set(true);
            sessions.values().forEach(queue -> queue.offer(""));
            http.stop(0);
        }

        private void handleSse(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                write(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }
            if (!AUTH.equals(header(exchange, "Authorization"))) {
                write(exchange, 401, "application/json", "{\"error\":\"unauthorized\"}");
                return;
            }
            authHits.incrementAndGet();
            String sessionId = "sse-" + UUID.randomUUID();
            BlockingQueue<String> queue = new LinkedBlockingQueue<>();
            sessions.put(sessionId, queue);
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "text/event-stream");
            headers.set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(("event: endpoint\ndata: /message?sessionId=" + sessionId + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
                while (!stopping.get()) {
                    String payload;
                    try {
                        payload = queue.poll(200, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    if (payload == null) {
                        continue;
                    }
                    if (payload.isEmpty()) {
                        break;
                    }
                    out.write(("event: message\ndata: " + payload + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } finally {
                sessions.remove(sessionId);
            }
        }

        private void handleMessage(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                write(exchange, 405, "text/plain", "Method Not Allowed");
                return;
            }
            if (!AUTH.equals(header(exchange, "Authorization"))) {
                write(exchange, 401, "application/json", "{\"error\":\"unauthorized\"}");
                return;
            }
            authHits.incrementAndGet();
            String query = exchange.getRequestURI().getQuery();
            String sessionId = query == null ? "" : query.replace("sessionId=", "");
            BlockingQueue<String> queue = sessions.get(sessionId);
            String body = readBody(exchange);
            JsonNode root = body.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(body);
            String rpcMethod = root.path("method").asText("");
            Object id = root.has("id") && !root.get("id").isNull()
                    ? MAPPER.treeToValue(root.get("id"), Object.class)
                    : null;
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
            if (id == null || queue == null) {
                return;
            }
            try {
                if ("initialize".equals(rpcMethod)) {
                    queue.put(MAPPER.writeValueAsString(Map.of(
                            "jsonrpc", "2.0",
                            "id", id,
                            "result", Map.of(
                                    "protocolVersion", "2024-11-05",
                                    "capabilities", Map.of("tools", Map.of()),
                                    "serverInfo", Map.of("name", "fake-sse-mcp", "version", "1.0.0")))));
                    return;
                }
                if ("tools/list".equals(rpcMethod)) {
                    queue.put(MAPPER.writeValueAsString(Map.of(
                            "jsonrpc", "2.0",
                            "id", id,
                            "result", Map.of(
                                    "tools", List.of(Map.of(
                                            "name", "echo_ping",
                                            "description", "Echo a token to prove remote execution",
                                            "inputSchema", Map.of(
                                                    "type", "object",
                                                    "properties", Map.of(
                                                            "token", Map.of("type", "string")))))))));
                    return;
                }
                if ("tools/call".equals(rpcMethod)) {
                    toolsCallCount.incrementAndGet();
                    String token = root.path("params").path("arguments").path("token").asText("");
                    String text = "echo:" + token + ":sse-executed";
                    queue.put(MAPPER.writeValueAsString(Map.of(
                            "jsonrpc", "2.0",
                            "id", id,
                            "result", Map.of(
                                    "content", List.of(Map.of("type", "text", "text", text)),
                                    "isError", false))));
                    return;
                }
                queue.put(MAPPER.writeValueAsString(Map.of(
                        "jsonrpc", "2.0",
                        "id", id,
                        "error", Map.of("code", -32601, "message", "Method not found: " + rpcMethod))));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
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

        private static void write(HttpExchange exchange, int status, String contentType, String body)
                throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
