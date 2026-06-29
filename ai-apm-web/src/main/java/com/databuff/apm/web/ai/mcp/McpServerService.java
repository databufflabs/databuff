package com.databuff.apm.web.ai.mcp;

import com.databuff.apm.web.ai.agent.AgentBrainService;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Legacy demo MCP endpoint backing {@code /api/v1/mcp/*}.
 *
 * @deprecated Use the standard MCP Server at {@code POST /mcp} instead.
 */
@Deprecated
@Service
public class McpServerService {

    public Map<String, Object> capabilities() {
        return Map.of(
                "protocol", "databuff-apm-mcp",
                "version", "0.1",
                "transport", List.of("sse", "http"));
    }

    public List<ToolDescriptor> tools() {
        return List.of(
                new ToolDescriptor("query_error_rate", "Query service error rates from store"),
                new ToolDescriptor("query_trace_count", "Count recent spans in trace store"),
                new ToolDescriptor("chat", "Natural language chat via AgentBrainService"));
    }

    public SseEmitter openSession(McpSessionRequest request, AgentBrainService agentBrainService)
            throws IOException {
        SseEmitter emitter = new SseEmitter(300_000L);
        emitter.send(SseEmitter.event()
                .name("capabilities")
                .data(capabilities()));
        emitter.send(SseEmitter.event()
                .name("tools")
                .data(tools()));
        if (request != null && request.prompt() != null && !request.prompt().isBlank()) {
            AgentBrainService.ChatResponse response = agentBrainService.chat(
                    new AgentBrainService.ChatRequest(request.sessionId(), request.prompt()));
            emitter.send(SseEmitter.event().name("message").data(response));
        }
        emitter.send(SseEmitter.event().name("ready").data(Map.of("status", "ok")));
        emitter.complete();
        return emitter;
    }

    public record ToolDescriptor(String name, String description) {
    }

    public record McpSessionRequest(String sessionId, String prompt) {
    }
}
