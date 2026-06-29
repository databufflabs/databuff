package com.databuff.apm.web.ai.mcp.standard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpStreamableHttpControllerTest {

    @Mock
    private McpJsonRpcService jsonRpcService;

    private McpStreamableHttpController controller;

    @BeforeEach
    void setUp() {
        controller = new McpStreamableHttpController(jsonRpcService);
    }

    @Test
    void postDelegatesToJsonRpcService() {
        when(jsonRpcService.handle(any())).thenReturn(Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "result", Map.of("protocolVersion", McpJsonRpcService.PROTOCOL_VERSION)));

        Map<String, Object> response = controller.post(Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "initialize"));

        assertThat(response).containsEntry("jsonrpc", "2.0");
        assertThat(response.get("result")).isInstanceOf(Map.class);
    }

    @Test
    void postToolsListReturnsElevenTools() {
        JavaBeanToolExecutor executor = org.mockito.Mockito.mock(JavaBeanToolExecutor.class);
        McpJsonRpcService realService = new McpJsonRpcService(new McpToolCatalog(), executor);
        when(jsonRpcService.handle(any())).thenAnswer(invocation -> realService.handle(invocation.getArgument(0)));

        Map<String, Object> response = controller.post(Map.of(
                "jsonrpc", "2.0",
                "id", "list",
                "method", "tools/list"));

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        @SuppressWarnings("unchecked")
        List<?> tools = (List<?>) result.get("tools");
        assertThat(tools).hasSize(11);
    }

    @Test
    void getReturnsMethodNotAllowed() {
        ResponseEntity<Map<String, String>> response = controller.get();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).containsEntry("error", "Use POST with application/json for JSON-RPC requests");
    }
}
