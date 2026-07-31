package com.databuff.apm.web.ai.agent;

import com.databuff.apm.web.ai.AiConfigController;
import com.databuff.apm.web.ai.LlmApiTypes;
import com.databuff.apm.web.ai.LlmChatModelFactory;
import com.databuff.apm.web.ai.OpenAiCompatibleChatClient;
import com.databuff.apm.web.ai.TestAiSupport;
import com.databuff.apm.web.ai.TestBeanSupport;
import com.databuff.apm.web.ai.TestLlmProviderRequest;
import com.databuff.apm.web.ai.TestLlmProviderResult;
import com.databuff.apm.web.ai.UpdateLlmProviderRequest;
import com.databuff.apm.web.ai.platform.BuiltInExpertCatalog;
import com.databuff.apm.web.ai.platform.expert.AiExpertDefinition;
import com.databuff.apm.web.ai.platform.expert.BrainRoutingCatalog;
import com.databuff.apm.web.ai.platform.expert.ExpertManagementService;
import com.databuff.apm.web.ai.platform.task.ExpertMessageConstants;
import com.databuff.apm.web.ai.platform.task.ExpertMessageContractTest;
import com.databuff.apm.web.ai.platform.task.ExpertMessageContext;
import com.databuff.apm.web.ai.platform.task.ExpertTaskContext;
import com.databuff.apm.web.ai.tool.ApmToolkit;
import jakarta.servlet.http.HttpServletRequest;
import com.databuff.apm.web.support.WebTestClusterSupport;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Design Phase 6 integration checks mapped to requirement acceptance criteria.
 */
class AiAcceptanceIntegrationTest {

    private static final byte[] OPENAI_CHAT_OK = """
            {"id":"t","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"pong"},"finish_reason":"stop"}]}
            """.getBytes(StandardCharsets.UTF_8);

    private static final byte[] ANTHROPIC_MESSAGE_OK = """
            {"id":"t","type":"message","role":"assistant","content":[{"type":"text","text":"pong"}],"model":"MiniMax-M3","stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}
            """.getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tempDir;

    @Test
    void builtInExpertsExistAndCannotBeDeleted() {
        ExpertManagementService experts = platformFixture().expertManagementService();
        assertThat(experts.list()).extracting(AiExpertDefinition::expertId)
                .containsExactlyInAnyOrder("brain", "data", "inspection", "ops", "qa");
        assertThat(experts.delete("brain")).isFalse();
        assertThat(experts.delete("data")).isFalse();
        assertThat(experts.delete("inspection")).isFalse();
    }

    @Test
    void platformCrudSurfacesAreAvailable() {
        TestAiSupport.PlatformRuntimeFixture fixture = platformFixture();
        assertThat(fixture.toolManagementService().list()).isNotEmpty();
        assertThat(fixture.skillManagementService().list()).isNotEmpty();
        assertThat(fixture.expertManagementService().list()).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void chatDefaultsToBrainAndRoutesDirectExpert() {
        ApmToolkit toolkit = mock(ApmToolkit.class);
        when(toolkit.countRecentSpans(anyLong())).thenReturn(3);
        AgentBrainService brain = TestAiSupport.aiFixture().agentBrain(toolkit, new AiSessionStore());

        AgentBrainService.ChatResponse brainResponse = brain.chat(
                new AgentBrainService.ChatRequest(null, "最近 trace 有多少"));
        assertThat(brainResponse.expertId()).isEqualTo("brain");
        assertThat(brainResponse.reply()).contains("3");

        AgentBrainService.ChatResponse data = brain.chat(
                new AgentBrainService.ChatRequest(null, "data", "hello", false, Map.of(), null));
        assertThat(data.expertId()).isEqualTo("data");
    }

    @Test
    void submitPollChatFlowWorksWithoutStream() throws Exception {
        ApmToolkit toolkit = mock(ApmToolkit.class);
        when(toolkit.countRecentSpans(anyLong())).thenReturn(9);
        AgentBrainService brain = TestAiSupport.aiFixture().agentBrain(toolkit, new AiSessionStore());
        AgentController controller = TestAiSupport.aiFixture().agentController(brain);
        HttpServletRequest request = mock(HttpServletRequest.class);

        AgentBrainService.ChatSubmitResponse submitted = controller.submit(
                request,
                new AgentBrainService.ChatRequest(null, "最近 trace 有多少"));
        assertThat(submitted.status()).isEqualTo("PROCESSING");
        assertThat(submitted.expertId()).isEqualTo("brain");

        AiSessionStore.MessagePollResponse poll = null;
        for (int i = 0; i < 80; i++) {
            poll = controller.messages(submitted.sessionId(), null);
            if (!poll.running()) {
                break;
            }
            Thread.sleep(50L);
        }
        assertThat(poll).isNotNull();
        assertThat(poll.running()).isFalse();
        assertThat(poll.messages()).anyMatch(message ->
                "assistant".equals(message.role()) && message.content().contains("9"));
    }

    @Test
    void brainVisiblePromptsMustNotLeakAsyncProtocolCoaching() throws Exception {
        TestAiSupport.PlatformRuntimeFixture fixture = platformFixture();
        String brainPrompt = BuiltInExpertCatalog.brainPromptBase();
        String routingSection = new BrainRoutingCatalog(fixture.expertManagementService())
                .buildRoutableExpertsSection();
        Path skillMd = Path.of("../deploy/common/skills/skill.brain.routing/SKILL.md");
        if (!Files.isRegularFile(skillMd)) {
            skillMd = Path.of("deploy/common/skills/skill.brain.routing/SKILL.md");
        }
        String skillBody = Files.readString(skillMd.toAbsolutePath().normalize());

        for (String text : List.of(brainPrompt, routingSection, skillBody)) {
            ExpertMessageContractTest.assertNoBanned(text, ExpertMessageContractTest.PROTOCOL_COACHING_BANNED);
            assertThat(text).doesNotContain("pending=0").doesNotContain("系统注入");
        }
        assertThat(brainPrompt).contains("路由与汇总").contains("不要编造数据").contains("skill.brain.routing");
        assertThat(routingSection).contains("`data`").contains("skill.brain.routing");
        assertThat(skillBody).contains("汇总与回答").contains("协作时序").doesNotContain("派发后行为");
    }

    @Test
    void brainWakeAndDispatchReceiptsMustStayContentOnly() {
        String accepted = ExpertMessageConstants.asyncWaitMessage("t-accept", "data");
        String busy = ExpertMessageConstants.serialDispatchBusyMessage("t-busy", "data");
        String wake = ExpertMessageContext.wrapBrainContinuation(
                "s1", 1, "t1", "data", "交付：告警 3 条", false, true, "分析一下当前的告警");
        String specialist = ExpertMessageContext.wrapTaskInput(
                "s1", 1, "t1", "brain", "查询活跃告警");

        ExpertMessageContractTest.assertNoBanned(accepted, ExpertMessageContractTest.PROTOCOL_COACHING_BANNED);
        ExpertMessageContractTest.assertNoBanned(busy, ExpertMessageContractTest.PROTOCOL_COACHING_BANNED);
        ExpertMessageContractTest.assertNoBanned(wake, ExpertMessageContractTest.PROTOCOL_COACHING_BANNED);
        ExpertMessageContractTest.assertNoBanned(specialist, ExpertMessageContractTest.CONTEXT_HEADER_BANNED);

        assertThat(accepted).isEqualTo("已派出：targetExpertId=data，taskId=t-accept");
        assertThat(busy).isEqualTo("该专家当前占用中：targetExpertId=data，进行中 taskId=t-busy");
        assertThat(specialist).isEqualTo("查询活跃告警");
        assertThat(wake)
                .contains("交付：告警 3 条")
                .contains("[本轮用户原请求]\n分析一下当前的告警")
                .doesNotContain("[Context:");
    }

    @Test
    void brainSubtasksAreTrackedInSessionMessages() {
        TestAiSupport.PlatformRuntimeFixture fixture = platformFixture();
        AiSessionStore store = new AiSessionStore();
        AiChatOrchestrator orchestrator = orchestrator(fixture, store);
        String sessionId = store.ensureSession(null, "brain", "session:key", "web-1");

        ExpertTaskContext.run(sessionId, "brain", null, () -> {
            String contextJson = "{\"sessionId\":\"" + sessionId + "\"}";
            fixture.expertDispatchTool().dispatchExpertTask("data", "health summary", contextJson, null);
            fixture.expertDispatchTool().dispatchExpertTask("inspection", "triage alerts", contextJson, null);
            return null;
        });

        Map<String, Object> metadata = orchestrator.buildAssistantMetadata(sessionId, "brain");
        store.appendAssistantMessage(sessionId, "aggregated reply", "brain", metadata);

        assertThat(store.messages(sessionId))
                .hasSize(1)
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.expertId()).isEqualTo("brain");
                    assertThat(message.metadata()).containsKey("subtasks");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> subtasks =
                            (List<Map<String, Object>>) message.metadata().get("subtasks");
                    assertThat(subtasks).hasSize(2);
                    assertThat(subtasks).extracting(item -> item.get("targetExpertId"))
                            .containsExactlyInAnyOrder("data", "inspection");
                });
        assertThat(fixture.expertTaskService().listBySession(sessionId)).hasSize(2);
    }

    @Test
    void llmConnectivityViaAgentScopeSucceedsForVersionedOpenAiBaseUrl() throws Exception {
        AtomicReference<String> hitPath = new AtomicReference<>();
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            hitPath.set(exchange.getRequestURI().getPath());
            if ("/api/paas/v4/chat/completions".equals(hitPath.get())) {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, OPENAI_CHAT_OK.length);
                exchange.getResponseBody().write(OPENAI_CHAT_OK);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            AiConfigController controller = new AiConfigController(TestAiSupport.configService());
            TestLlmProviderResult result = controller.testProvider(new TestLlmProviderRequest(
                    "http://127.0.0.1:" + port + "/api/paas/v4",
                    "sk-test",
                    LlmApiTypes.OPENAI_COMPLETIONS,
                    "GLM-5",
                    "zhipu"));
            assertThat(result.ok()).as(result.message()).isTrue();
            assertThat(hitPath.get()).isEqualTo("/api/paas/v4/chat/completions");
            assertThat(LlmChatModelFactory.build(
                    new OpenAiCompatibleChatClient.ResolvedLlmProvider(
                            "zhipu",
                            "http://127.0.0.1:" + port + "/api/paas/v4",
                            "GLM-5",
                            "sk-test",
                            LlmApiTypes.OPENAI_COMPLETIONS),
                    "GLM-5",
                    false)).isInstanceOf(OpenAIChatModel.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void llmConnectivityFailsWhenChatCompletionsPathUsedAsBaseUrl() throws Exception {
        AtomicReference<String> hitPath = new AtomicReference<>();
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            hitPath.set(exchange.getRequestURI().getPath());
            if ("/api/paas/v4/chat/completions".equals(hitPath.get())) {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, OPENAI_CHAT_OK.length);
                exchange.getResponseBody().write(OPENAI_CHAT_OK);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            AiConfigController controller = new AiConfigController(TestAiSupport.configService());
            // Same mistake as pasting Zhipu's full endpoint into Base URL — connectivity must fail like Q&A.
            TestLlmProviderResult result = controller.testProvider(new TestLlmProviderRequest(
                    "http://127.0.0.1:" + port + "/api/paas/v4/chat/completions",
                    "sk-test",
                    LlmApiTypes.OPENAI_COMPLETIONS,
                    "GLM-5",
                    "zhipu"));
            assertThat(result.ok()).isFalse();
            assertThat(hitPath.get()).isEqualTo("/api/paas/v4/chat/completions/v1/chat/completions");
            assertThat(LlmChatModelFactory.buildOpenAiChatCompletionsUrl(
                    "http://127.0.0.1:" + port + "/api/paas/v4/chat/completions"))
                    .endsWith("/api/paas/v4/chat/completions/v1/chat/completions");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void llmConnectivityViaAgentScopeSucceedsForAnthropicBaseUrl() throws Exception {
        AtomicReference<String> hitPath = new AtomicReference<>();
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            hitPath.set(exchange.getRequestURI().getPath());
            if ("/anthropic/v1/messages".equals(hitPath.get())) {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, ANTHROPIC_MESSAGE_OK.length);
                exchange.getResponseBody().write(ANTHROPIC_MESSAGE_OK);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            AiConfigController controller = new AiConfigController(TestAiSupport.configService());
            TestLlmProviderResult result = controller.testProvider(new TestLlmProviderRequest(
                    "http://127.0.0.1:" + port + "/anthropic",
                    "sk-test",
                    LlmApiTypes.ANTHROPIC_MESSAGES,
                    "MiniMax-M3",
                    "minimax"));
            assertThat(result.ok()).as(result.message()).isTrue();
            assertThat(hitPath.get()).isEqualTo("/anthropic/v1/messages");
            assertThat(LlmChatModelFactory.build(
                    new OpenAiCompatibleChatClient.ResolvedLlmProvider(
                            "minimax",
                            "http://127.0.0.1:" + port + "/anthropic",
                            "MiniMax-M3",
                            "sk-test",
                            LlmApiTypes.ANTHROPIC_MESSAGES),
                    "MiniMax-M3",
                    false)).isInstanceOf(AnthropicChatModel.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void llmConnectivityAndExpertModelShareAgentScopeFactory() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, OPENAI_CHAT_OK.length);
            exchange.getResponseBody().write(OPENAI_CHAT_OK);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            String baseUrl = "http://127.0.0.1:" + port + "/v1";
            var provider = new OpenAiCompatibleChatClient.ResolvedLlmProvider(
                    "openai", baseUrl, "gpt-test", "sk-test", LlmApiTypes.OPENAI_COMPLETIONS);

            // Connectivity probe and expert Q&A model construction share LlmChatModelFactory.build.
            LlmChatModelFactory.probe(provider, "gpt-test");
            assertThat(LlmChatModelFactory.build(provider, "gpt-test", false))
                    .isInstanceOf(OpenAIChatModel.class);

            AiConfigController controller = new AiConfigController(TestAiSupport.configService());
            assertThat(controller.testProvider(new TestLlmProviderRequest(
                    baseUrl, "sk-test", LlmApiTypes.OPENAI_COMPLETIONS, "gpt-test", "openai")).ok())
                    .isTrue();
        } finally {
            server.stop(0);
        }
    }

    private TestAiSupport.PlatformRuntimeFixture platformFixture() {
        TestAiSupport.AiFixture aiFixture = TestAiSupport.aiFixture();
        aiFixture.agentRuntimeConfig().setCustomSkillsDir(tempDir.toString());
        aiFixture.store().updateProvider("openai", new UpdateLlmProviderRequest(null, "sk-test", null, true));
        return aiFixture.buildPlatformRuntime(mock(ApmToolkit.class));
    }

    private AgentController controller(ApmToolkit toolkit) {
        return TestAiSupport.aiFixture().agentController(
                TestAiSupport.aiFixture().agentBrain(toolkit, new AiSessionStore()));
    }

    private AiChatOrchestrator orchestrator(TestAiSupport.PlatformRuntimeFixture fixture, AiSessionStore store) {
        TestAiSupport.AiFixture aiFixture = TestAiSupport.aiFixture();
        AiChatOrchestrator chatOrchestrator = TestBeanSupport.chatOrchestrator(
                fixture.expertManagementService(),
                fixture.expertRuntimeRegistry(),
                fixture.sessionExpertRuntimeRegistry(),
                store,
                aiFixture.aiConfigService(),
                aiFixture.agentRuntimeConfig(),
                mock(ApmToolkit.class),
                new OpenAiCompatibleChatClient(aiFixture.agentRuntimeConfig()),
                aiFixture.store(),
                WebTestClusterSupport.standaloneAiRouter("web-1"),
                new AiRuntimeForwarder(WebTestClusterSupport.standaloneAiRouter("web-1"), 120L),
                fixture.expertTaskService(),
                fixture.expertTaskPendingRegistry(),
                fixture.expertTaskTextGuard(),
                fixture.sessionWorkspaceService(),
                15);
        fixture.wireBrainContinuer(chatOrchestrator);
        return chatOrchestrator;
    }
}
