package com.databuff.apm.web.ai.platform.runtime;

import com.databuff.apm.web.ai.agent.AgentRuntimeConfig;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.mcp.McpTool;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolResultOverflowServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void savesCompleteOversizedMcpResultAndReturnsBoundedPreview() throws Exception {
        AgentRuntimeConfig config = new AgentRuntimeConfig();
        config.setWorkspaceDir(tempDir.toString());
        config.setMcpToolResultMaxBytes(1024);
        SessionWorkspaceService workspaceService = new SessionWorkspaceService(config);
        McpToolResultOverflowService service = new McpToolResultOverflowService(workspaceService, config);
        Toolkit toolkit = mcpToolkit("fetch_diagnostics");
        String fullResult = "result-start\n" + "中".repeat(800) + "\nUNIQUE_RESULT_TAIL";
        ToolResultBlock original = new ToolResultBlock(
                "call-large-1",
                "fetch_diagnostics",
                List.of(TextBlock.builder().text(fullResult).build()),
                Map.of("source", "fake-mcp"));

        ToolResultBlock limited = service.limit(
                toolkit,
                callParam("session-overflow-test", "fetch_diagnostics", "call-large-1"),
                original);

        String modelText = ((TextBlock) limited.getOutput().get(0)).getText();
        assertThat(modelText.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(1024);
        assertThat(modelText)
                .contains(McpToolResultOverflowService.TRUNCATED_MARKER)
                .contains("workspace_file=tool-results/fetch_diagnostics-call-large-1.txt")
                .contains("readWorkspaceFileChunk")
                .doesNotContain("UNIQUE_RESULT_TAIL");
        assertThat(limited.getMetadata())
                .containsEntry("truncated", true)
                .containsEntry("workspaceFile", "tool-results/fetch_diagnostics-call-large-1.txt");
        Path saved = workspaceService.resolveRelativePath(
                "session-overflow-test", "tool-results/fetch_diagnostics-call-large-1.txt");
        assertThat(Files.readString(saved)).isEqualTo(fullResult);
    }

    @Test
    void leavesShortMcpResultUnchanged() {
        AgentRuntimeConfig config = new AgentRuntimeConfig();
        config.setWorkspaceDir(tempDir.toString());
        config.setMcpToolResultMaxBytes(1024);
        McpToolResultOverflowService service = new McpToolResultOverflowService(
                new SessionWorkspaceService(config), config);
        Toolkit toolkit = mcpToolkit("fetch_diagnostics");
        ToolResultBlock original = ToolResultBlock.text("small result");

        ToolResultBlock limited = service.limit(
                toolkit,
                callParam("session-overflow-test", "fetch_diagnostics", "call-small-1"),
                original);

        assertThat(limited).isSameAs(original);
    }

    private static ToolCallParam callParam(
            String sessionId,
            String toolName,
            String callId) {
        ToolUseBlock toolUse = ToolUseBlock.builder()
                .id(callId)
                .name(toolName)
                .input(Map.of())
                .build();
        return ToolCallParam.builder()
                .toolUseBlock(toolUse)
                .runtimeContext(RuntimeContext.builder().sessionId(sessionId).build())
                .build();
    }

    private static Toolkit mcpToolkit(String toolName) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new McpTool(
                toolName,
                "fake MCP tool",
                Map.of("type", "object", "properties", Map.of()),
                null,
                new NoopMcpClient(),
                null,
                "fake-mcp",
                true));
        return toolkit;
    }

    private static final class NoopMcpClient extends McpClientWrapper {

        private NoopMcpClient() {
            super("fake-mcp");
        }

        @Override
        public Mono<Void> initialize() {
            return Mono.empty();
        }

        @Override
        public Mono<List<McpSchema.Tool>> listTools() {
            return Mono.just(List.of());
        }

        @Override
        public Mono<McpSchema.CallToolResult> callTool(
                String name,
                Map<String, Object> arguments) {
            return Mono.error(new UnsupportedOperationException());
        }

        @Override
        public Mono<McpSchema.CallToolResult> callTool(
                String name,
                Map<String, Object> arguments,
                Map<String, Object> meta) {
            return Mono.error(new UnsupportedOperationException());
        }

        @Override
        public void close() {
        }
    }
}
