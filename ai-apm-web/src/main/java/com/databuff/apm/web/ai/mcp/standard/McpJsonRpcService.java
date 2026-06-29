package com.databuff.apm.web.ai.mcp.standard;

import com.databuff.apm.web.ai.platform.AiPlatformApiException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class McpJsonRpcService {

    static final String JSONRPC_VERSION = "2.0";
    static final String PROTOCOL_VERSION = "2024-11-05";

    private final McpToolCatalog catalog;
    private final JavaBeanToolExecutor executor;

    public McpJsonRpcService(McpToolCatalog catalog, JavaBeanToolExecutor executor) {
        this.catalog = catalog;
        this.executor = executor;
    }

    public Map<String, Object> handle(Map<String, Object> request) {
        if (request == null) {
            return errorResponse(null, -32600, "Invalid Request");
        }
        Object id = request.get("id");
        Object jsonrpc = request.get("jsonrpc");
        if (jsonrpc != null && !JSONRPC_VERSION.equals(jsonrpc)) {
            return errorResponse(id, -32600, "Invalid JSON-RPC version");
        }
        Object methodValue = request.get("method");
        if (!(methodValue instanceof String method) || method.isBlank()) {
            return errorResponse(id, -32600, "method is required");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> params = request.get("params") instanceof Map<?, ?> rawParams
                ? (Map<String, Object>) rawParams
                : Map.of();
        try {
            Object result = dispatch(method, params);
            return successResponse(id, result);
        } catch (McpJsonRpcException ex) {
            return errorResponse(id, ex.code(), ex.getMessage());
        } catch (AiPlatformApiException ex) {
            return errorResponse(id, -32000, ex.getMessage());
        } catch (RuntimeException ex) {
            return errorResponse(id, -32603, ex.getMessage() == null ? "Internal error" : ex.getMessage());
        }
    }

    private Object dispatch(String method, Map<String, Object> params) {
        return switch (method) {
            case "initialize" -> initialize(params);
            case "tools/list" -> toolsList();
            case "tools/call" -> toolsCall(params);
            default -> throw new McpJsonRpcException(-32601, "Method not found: " + method);
        };
    }

    private Map<String, Object> initialize(Map<String, Object> params) {
        return Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of("tools", Map.of()),
                "serverInfo", Map.of(
                        "name", "databuff-apm",
                        "version", "1.0.0"));
    }

    private Map<String, Object> toolsList() {
        List<Map<String, Object>> tools = catalog.listTools().stream()
                .map(tool -> Map.<String, Object>of(
                        "name", tool.name(),
                        "description", tool.description(),
                        "inputSchema", tool.inputSchema()))
                .toList();
        return Map.of("tools", tools);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toolsCall(Map<String, Object> params) {
        Object nameValue = params.get("name");
        if (!(nameValue instanceof String name) || name.isBlank()) {
            throw new McpJsonRpcException(-32602, "tools/call requires name");
        }
        McpToolCatalog.McpToolDefinition tool = catalog.findByName(name)
                .orElseThrow(() -> new McpJsonRpcException(-32602, "Unknown tool: " + name));
        Map<String, Object> arguments = params.get("arguments") instanceof Map<?, ?> rawArguments
                ? (Map<String, Object>) rawArguments
                : Map.of();
        JavaBeanToolExecutor.TestToolRequest request = executor.fromArguments(arguments);
        String output = executor.invoke(tool.implementation(), request);
        return Map.of(
                "content", List.of(Map.of("type", "text", "text", output)),
                "isError", false);
    }

    static Map<String, Object> successResponse(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", JSONRPC_VERSION);
        response.put("result", result);
        if (id != null) {
            response.put("id", id);
        }
        return response;
    }

    static Map<String, Object> errorResponse(Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", JSONRPC_VERSION);
        response.put("error", Map.of("code", code, "message", message));
        if (id != null) {
            response.put("id", id);
        }
        return response;
    }

    static final class McpJsonRpcException extends RuntimeException {
        private final int code;

        McpJsonRpcException(int code, String message) {
            super(message);
            this.code = code;
        }

        int code() {
            return code;
        }
    }
}
