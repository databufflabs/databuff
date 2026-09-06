package com.databuff.apm.web.ai.mcp.standard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

        ResponseEntity<?> response = controller.post(Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "initialize"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("jsonrpc", "2.0");
        assertThat(body.get("result")).isInstanceOf(Map.class);
    }

    @Test
    void postToolsListReturnsFifteenTools() {
        JavaBeanToolExecutor executor = org.mockito.Mockito.mock(JavaBeanToolExecutor.class);
        McpJsonRpcService realService = new McpJsonRpcService(new McpToolCatalog(), executor);
        when(jsonRpcService.handle(any())).thenAnswer(invocation -> realService.handle(invocation.getArgument(0)));

        ResponseEntity<?> response = controller.post(Map.of(
                "jsonrpc", "2.0",
                "id", "list",
                "method", "tools/list"));

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) body.get("result");
        @SuppressWarnings("unchecked")
        List<?> tools = (List<?>) result.get("tools");
        assertThat(tools).hasSize(15);
    }

    @Test
    void notificationReturnsAcceptedWithoutDispatching() {
        ResponseEntity<?> response = controller.post(Map.of(
                "jsonrpc", "2.0",
                "method", "notifications/initialized"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNull();
        verify(jsonRpcService, never()).handle(any());
    }

    @Test
    void httpTransportNegotiatesNotificationAndUnsupportedSseGet() throws Exception {
        MockMvc http = MockMvcBuilders.standaloneSetup(controller).build();

        http.perform(post("/mcp")
                        .contentType("application/json")
                        .accept("application/json", "text/event-stream")
                        .content("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        http.perform(get("/mcp").accept("text/event-stream"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"))
                .andExpect(content().string(""));
    }

    @Test
    void getReturnsMethodNotAllowed() {
        ResponseEntity<Void> response = controller.get();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getAllow()).containsExactly(HttpMethod.POST);
        assertThat(response.getBody()).isNull();
    }
}
