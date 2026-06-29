package com.databuff.apm.web.ai.mcp.standard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpJsonRpcServiceTest {

    @Mock
    private JavaBeanToolExecutor executor;

    private McpJsonRpcService service;

    @BeforeEach
    void setUp() {
        service = new McpJsonRpcService(new McpToolCatalog(), executor);
    }

    @Test
    void initializeReturnsCapabilities() {
        Map<String, Object> response = service.handle(Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "initialize",
                "params", Map.of()));

        assertThat(response).containsEntry("jsonrpc", "2.0");
        assertThat(response).containsEntry("id", 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        assertThat(result).containsEntry("protocolVersion", McpJsonRpcService.PROTOCOL_VERSION);
        assertThat(result).containsKey("serverInfo");
        assertThat(result).containsKey("capabilities");
    }

    @Test
    void toolsListReturnsElevenTools() {
        Map<String, Object> response = service.handle(Map.of(
                "jsonrpc", "2.0",
                "id", "list-1",
                "method", "tools/list"));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
        assertThat(tools).hasSize(11);
        assertThat(tools.get(0)).containsKeys("name", "description", "inputSchema");
    }

    @Test
    void toolsCallInvokesExecutor() {
        when(executor.invoke(eq("dataTools.queryServicesAll"), any()))
                .thenReturn("{\"services\":[]}");

        Map<String, Object> response = service.handle(Map.of(
                "jsonrpc", "2.0",
                "id", 2,
                "method", "tools/call",
                "params", Map.of(
                        "name", "queryServicesAll",
                        "arguments", Map.of("keyword", "checkout"))));

        verify(executor).invoke(eq("dataTools.queryServicesAll"), any());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0)).containsEntry("type", "text");
        assertThat(content.get(0)).containsEntry("text", "{\"services\":[]}");
        assertThat(result).containsEntry("isError", false);
    }

    @Test
    void unknownMethodReturnsJsonRpcError() {
        Map<String, Object> response = service.handle(Map.of(
                "jsonrpc", "2.0",
                "id", 3,
                "method", "resources/list"));

        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) response.get("error");
        assertThat(error).containsEntry("code", -32601);
        assertThat(error.get("message")).asString().contains("Method not found");
    }

    @Test
    void unknownToolReturnsJsonRpcError() {
        Map<String, Object> response = service.handle(Map.of(
                "jsonrpc", "2.0",
                "id", 4,
                "method", "tools/call",
                "params", Map.of("name", "missingTool")));

        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) response.get("error");
        assertThat(error).containsEntry("code", -32602);
        assertThat(error.get("message")).asString().contains("Unknown tool");
    }
}
