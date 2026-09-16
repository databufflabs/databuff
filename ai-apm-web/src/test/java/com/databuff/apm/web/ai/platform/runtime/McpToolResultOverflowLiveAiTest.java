package com.databuff.apm.web.ai.platform.runtime;

import com.databuff.apm.web.ai.LlmApiTypes;
import com.databuff.apm.web.ai.LlmChatModelFactory;
import com.databuff.apm.web.ai.OpenAiCompatibleChatClient;
import com.databuff.apm.web.ai.agent.AgentRuntimeConfig;
import com.databuff.apm.web.ai.agent.AiSessionStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tool.mcp.McpTool;
import io.agentscope.core.tracing.TracerRegistry;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "RUN_OPENCODE_GO_AI_TEST", matches = "true")
class McpToolResultOverflowLiveAiTest {

    private static final String MODEL = "deepseek-v4.1-flash";
    private static final String EXPECTED_CODE = "ORANGE-417";

    @TempDir
    Path tempDir;

    @AfterEach
    void resetGlobalTracer() {
        TracerRegistry.resetToNoop();
    }

    @Test
    @Timeout(180)
    void deepSeekReadsSavedMcpResultOnlyWhenPreviewIsInsufficient() throws Exception {
        String apiKey = opencodeGoApiKey();
        assertThat(apiKey)
                .as("OpenCode Go credential from OPENCODE_API_KEY or the local opencode auth.json")
                .isNotBlank();

        AgentRuntimeConfig config = new AgentRuntimeConfig();
        config.setWorkspaceDir(tempDir.toString());
        config.setMcpToolResultMaxBytes(1024);
        config.setLlmHttpTimeoutSeconds(120);
        SessionWorkspaceService workspaceService = new SessionWorkspaceService(config);
        SessionWorkspaceTools workspaceTools = new SessionWorkspaceTools(
                workspaceService, config, new TaskGeneratedFileRegistry());
        McpToolResultOverflowService overflowService = new McpToolResultOverflowService(
                workspaceService, config);
        AgentScopeToolResultTracer tracer = new AgentScopeToolResultTracer(
                new AgentScopeSessionHook(new AiSessionStore()), overflowService);
        tracer.registerTracer();

        String fullMcpResult = "DIAGNOSTIC_BEGIN\n"
                + "A".repeat(840)
                + "\nSECRET_CODE=" + EXPECTED_CODE + "\n"
                + "B".repeat(640)
                + "\nDIAGNOSTIC_END";
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new McpTool(
                "fetch_diagnostics",
                "Return the complete diagnostic payload. Call once before answering.",
                Map.of("type", "object", "properties", Map.of()),
                null,
                new FixedResultMcpClient(fullMcpResult),
                null,
                "fake-diagnostics-mcp",
                true));
        toolkit.registerTool(workspaceTools);

        OpenAiCompatibleChatClient.ResolvedLlmProvider provider =
                new OpenAiCompatibleChatClient.ResolvedLlmProvider(
                        "opencode",
                        "https://opencode.ai/zen/go/v1",
                        MODEL,
                        apiKey,
                        LlmApiTypes.OPENAI_COMPLETIONS,
                        512);
        Model model = LlmChatModelFactory.build(provider, MODEL, false);
        String sessionId = "session-opencode-overflow-live";
        GenerateOptions generateOptions = GenerateOptions.builder()
                .maxTokens(512)
                .additionalHeader("x-opencode-session", sessionId)
                .additionalHeader("User-Agent", "DataBuff-AI-Integration-Test/0.1.9")
                .build();
        ReActAgent agent = ReActAgent.builder()
                .name("mcp-overflow-live-ai-test")
                .sysPrompt("""
                        You are a deterministic integration-test agent.
                        Always use the requested tools. Never guess a value that is absent from the visible result.
                        When an MCP result is truncated and names a workspace file, follow its instruction and use
                        readWorkspaceFileChunk to inspect only the content needed to answer.
                        """)
                .model(model)
                .generateOptions(generateOptions)
                .modelExecutionConfig(ExecutionConfig.builder()
                        .timeout(Duration.ofSeconds(120))
                        .maxAttempts(1)
                        .build())
                .toolkit(toolkit)
                .maxIters(6)
                .checkRunning(false)
                .permissionContext(AgentScopePermissionSupport.autoAllowContext())
                .build();

        RuntimeContext runtimeContext = RuntimeContext.builder().sessionId(sessionId).build();
        Msg response = agent.call("""
                        Call fetch_diagnostics exactly once, then find the exact SECRET_CODE in its complete result.
                        The preview is intentionally too short to contain it. If the result is truncated, use
                        readWorkspaceFileChunk as instructed. Answer with the code only.
                        """, runtimeContext)
                .block(Duration.ofSeconds(150));

        assertThat(response).isNotNull();
        assertThat(response.getTextContent()).contains(EXPECTED_CODE);
        List<String> calledTools = agent.getAgentState(runtimeContext).getContext().stream()
                .flatMap(message -> message.getContentBlocks(ToolUseBlock.class).stream())
                .map(ToolUseBlock::getName)
                .toList();
        assertThat(calledTools)
                .contains("fetch_diagnostics", "readWorkspaceFileChunk");
        assertThat(calledTools.stream().filter("fetch_diagnostics"::equals).count()).isEqualTo(1L);

        Path saved = workspaceService.sessionDir(sessionId).resolve("tool-results");
        assertThat(saved).isDirectory();
        try (var files = Files.list(saved)) {
            Path resultFile = files.findFirst().orElseThrow();
            assertThat(Files.readString(resultFile)).isEqualTo(fullMcpResult);
        }
    }

    private static String opencodeGoApiKey() throws Exception {
        String fromEnvironment = System.getenv("OPENCODE_API_KEY");
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment.trim();
        }
        String configured = System.getenv("OPENCODE_AUTH_FILE");
        Path authFile = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".local", "share", "opencode", "auth.json")
                : Path.of(configured);
        if (!Files.isRegularFile(authFile)) {
            return "";
        }
        JsonNode root = new ObjectMapper().readTree(authFile.toFile());
        return root.path("opencode-go").path("key").asText("").trim();
    }

    private static final class FixedResultMcpClient extends McpClientWrapper {

        private final String result;

        private FixedResultMcpClient(String result) {
            super("fake-diagnostics-mcp");
            this.result = result;
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
            return Mono.just(result());
        }

        @Override
        public Mono<McpSchema.CallToolResult> callTool(
                String name,
                Map<String, Object> arguments,
                Map<String, Object> meta) {
            return Mono.just(result());
        }

        private McpSchema.CallToolResult result() {
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(result)), false);
        }

        @Override
        public void close() {
        }
    }
}
