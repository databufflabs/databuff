package com.databuff.apm.web.ai.platform.task;

import com.databuff.apm.web.ai.OpenAiCompatibleChatClient;
import com.databuff.apm.web.ai.TestAiSupport;
import com.databuff.apm.web.ai.TestBeanSupport;
import com.databuff.apm.web.ai.UpdateLlmProviderRequest;
import com.databuff.apm.web.ai.agent.AgentBrainService;
import com.databuff.apm.web.ai.agent.AiChatOrchestrator;
import com.databuff.apm.web.ai.agent.AiMessageStatus;
import com.databuff.apm.web.ai.agent.AiMessageType;
import com.databuff.apm.web.ai.agent.AiRuntimeForwarder;
import com.databuff.apm.web.ai.agent.AiRuntimeRouter;
import com.databuff.apm.web.ai.agent.AiSessionStore;
import com.databuff.apm.web.ai.platform.expert.AiExpertDefinition;
import com.databuff.apm.web.ai.platform.runtime.ExpertChatInput;
import com.databuff.apm.web.ai.platform.runtime.ExpertChatResult;
import com.databuff.apm.web.ai.platform.runtime.ExpertRuntime;
import com.databuff.apm.web.ai.platform.runtime.ExpertRuntimeEvent;
import com.databuff.apm.web.ai.platform.runtime.ExpertRuntimeRegistry;
import com.databuff.apm.web.ai.platform.runtime.SessionExpertRuntimeRegistry;
import com.databuff.apm.web.ai.tool.ApmToolkit;
import com.databuff.apm.web.support.WebTestClusterSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression for mid-round model failure (e.g. Volcengine 503 after tools):
 * user must see an explicit failure, not intermediate progress text as a silent "final" answer.
 * Covers single-expert chat and brain multi-expert fan-in.
 */
class ModelFailureUserVisibleIntegrationTest {

    private static final String MODEL_BUSY_503 =
            "OpenAI API request failed with status 503 | "
                    + "{\"error\":{\"code\":10310,\"message\":\"The system is busy, please try again later.\"}}";

    @TempDir
    Path tempDir;

    private AiSessionStore sessionStore;
    private ExpertTaskPendingRegistry pendingRegistry;
    private ExpertTaskService taskService;
    private AiChatOrchestrator orchestrator;
    private final List<String> brainPrompts = new CopyOnWriteArrayList<>();

    private ExpertRuntime opsRuntime;
    private ExpertRuntime dataRuntime;
    private ExpertRuntime inspectionRuntime;

    @BeforeEach
    void setUp() {
        TestAiSupport.AiFixture aiFixture = TestAiSupport.aiFixture();
        aiFixture.agentRuntimeConfig().setCustomSkillsDir(tempDir.toString());
        aiFixture.store().updateProvider("openai", new UpdateLlmProviderRequest(
                null, "sk-test", null, true));
        TestAiSupport.PlatformRuntimeFixture fixture =
                aiFixture.buildPlatformRuntime(Mockito.mock(ApmToolkit.class));

        sessionStore = new AiSessionStore();
        pendingRegistry = new ExpertTaskPendingRegistry();
        ExpertTaskTextGuard textGuard = new ExpertTaskTextGuard();
        AtomicReference<BrainRoundContinuer> continuerRef = new AtomicReference<>();
        BrainContinuationService continuationService =
                new BrainContinuationService(continuerRefProvider(continuerRef), pendingRegistry);

        opsRuntime = mock(ExpertRuntime.class);
        dataRuntime = mock(ExpertRuntime.class);
        inspectionRuntime = mock(ExpertRuntime.class);
        // Default: specialists succeed unless a test stubs otherwise.
        when(opsRuntime.stream(any(ExpertChatInput.class)))
                .thenReturn(Flux.just(ExpertRuntimeEvent.text("ops结论：环境正常")));
        when(dataRuntime.stream(any(ExpertChatInput.class)))
                .thenReturn(Flux.just(ExpertRuntimeEvent.text("data结论：3 个服务")));
        when(inspectionRuntime.stream(any(ExpertChatInput.class)))
                .thenReturn(Flux.just(ExpertRuntimeEvent.text("inspection结论：健康")));

        ExpertRuntime brainRuntime = mock(ExpertRuntime.class);
        when(brainRuntime.stream(any(ExpertChatInput.class))).thenAnswer(invocation -> {
            ExpertChatInput input = invocation.getArgument(0);
            String message = input.message() == null ? "" : input.message();
            brainPrompts.add(message);
            if (message.contains("数字专家")
                    && (message.contains("已完成") || message.contains("失败"))) {
                return Flux.just(ExpertRuntimeEvent.text("终稿：已汇总专家结果（含失败项）"));
            }
            return Flux.empty();
        });
        when(brainRuntime.chat(any(ExpertChatInput.class)))
                .thenReturn(Mono.just(ExpertChatResult.ok("终稿")));

        ExpertRuntimeRegistry registry = mock(ExpertRuntimeRegistry.class);
        when(registry.getOrCreate("ops")).thenReturn(opsRuntime);
        when(registry.getOrCreate("data")).thenReturn(dataRuntime);
        when(registry.getOrCreate("inspection")).thenReturn(inspectionRuntime);
        when(registry.getOrCreate("brain")).thenReturn(brainRuntime);

        SessionExpertRuntimeRegistry sessionRegistry = mock(SessionExpertRuntimeRegistry.class);
        when(sessionRegistry.getOrCreate(any(String.class), any())).thenAnswer(invocation -> {
            AiExpertDefinition expert = invocation.getArgument(1);
            if (expert == null) {
                return brainRuntime;
            }
            return switch (expert.expertId()) {
                case "ops" -> opsRuntime;
                case "data" -> dataRuntime;
                case "inspection" -> inspectionRuntime;
                default -> brainRuntime;
            };
        });

        taskService = new ExpertTaskService(
                fixture.expertManagementService(),
                providerOf(registry),
                providerOf(sessionRegistry),
                null,
                sessionStore,
                pendingRegistry,
                textGuard,
                continuationService,
                fixture.sessionWorkspaceService(),
                new com.databuff.apm.web.ai.platform.runtime.TaskGeneratedFileRegistry());
        AiRuntimeRouter runtimeRouter = WebTestClusterSupport.standaloneAiRouter("web-1");
        orchestrator = TestBeanSupport.chatOrchestrator(
                fixture.expertManagementService(),
                registry,
                sessionRegistry,
                sessionStore,
                aiFixture.aiConfigService(),
                aiFixture.agentRuntimeConfig(),
                mock(ApmToolkit.class),
                new OpenAiCompatibleChatClient(aiFixture.agentRuntimeConfig()),
                aiFixture.store(),
                runtimeRouter,
                new AiRuntimeForwarder(runtimeRouter, 120L),
                taskService,
                pendingRegistry,
                textGuard,
                fixture.sessionWorkspaceService(),
                15);
        continuerRef.set(orchestrator);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        taskService.shutdownForTests();
    }

    @Test
    void singleExpert_ops_hardErrorAfterTools_showsFailedRoundFinal() throws Exception {
        when(opsRuntime.stream(any(ExpertChatInput.class))).thenReturn(midToolThenHardError());

        AgentBrainService.ChatSubmitResponse submitted = orchestrator.submitChat(
                new AgentBrainService.ChatRequest(
                        null, "ops", "排查 webhook 未上报", false, Map.of(), null));

        assertThat(awaitNotRunning(submitted.sessionId())).isTrue();
        List<AiSessionStore.ChatMessage> messages = sessionStore.messages(submitted.sessionId());

        assertThat(messages).anySatisfy(message -> {
            assertThat(message.role()).isEqualTo("assistant");
            assertThat(message.expertId()).isEqualTo("ops");
            assertThat(message.messageType()).isEqualTo(AiMessageType.TEXT.name());
            assertThat(message.messageStatus()).isEqualTo(AiMessageStatus.FAILED.name());
            assertThat(Boolean.TRUE.equals(message.metadata().get(ExpertMessageConstants.META_IS_ROUND_FINAL)))
                    .isTrue();
            assertThat(message.content()).contains("对话失败").contains("503");
        });
        assertNoSilentProgressFinal(messages, "ops");
    }

    @Test
    void singleExpert_ops_softErrorEventAfterTools_showsFailedRoundFinal() throws Exception {
        when(opsRuntime.stream(any(ExpertChatInput.class))).thenReturn(midToolThenSoftError());

        AgentBrainService.ChatSubmitResponse submitted = orchestrator.submitChat(
                new AgentBrainService.ChatRequest(
                        null, "ops", "连 192.168.50.80 查 agent", false, Map.of(), null));

        assertThat(awaitNotRunning(submitted.sessionId())).isTrue();
        List<AiSessionStore.ChatMessage> messages = sessionStore.messages(submitted.sessionId());

        assertThat(messages).anySatisfy(message -> {
            assertThat(message.expertId()).isEqualTo("ops");
            assertThat(message.messageType()).isEqualTo(AiMessageType.TEXT.name());
            assertThat(message.messageStatus()).isEqualTo(AiMessageStatus.FAILED.name());
            assertThat(Boolean.TRUE.equals(message.metadata().get(ExpertMessageConstants.META_IS_ROUND_FINAL)))
                    .isTrue();
            assertThat(message.content()).contains("对话失败").contains("503");
        });
        assertNoSilentProgressFinal(messages, "ops");
    }

    @Test
    void multiExpert_dataOkOpsHardError_persistsErrorAndBrainStillFinalizes() throws Exception {
        when(opsRuntime.stream(any(ExpertChatInput.class))).thenReturn(midToolThenHardError());

        String sessionId = startBrainRound("并行排查服务与环境");
        int roundIndex = sessionStore.peekCurrentRoundIndex(sessionId);
        Map<String, Object> meta = Map.of(
                ExpertMessageConstants.META_ROUND_INDEX, roundIndex, "userName", "admin");

        ExpertTask data = taskService.submit(new ExpertTaskRequest(
                sessionId, "brain", "data", "查最近服务列表", null, meta));
        ExpertTask ops = taskService.submit(new ExpertTaskRequest(
                sessionId, "brain", "ops", "SSH 查 agent 与 webhook", null, meta));

        assertThat(taskService.waitFor(data.taskId(), Duration.ofSeconds(5)).status())
                .isEqualTo(ExpertTaskStatus.SUCCEEDED);
        ExpertTask finishedOps = taskService.waitFor(ops.taskId(), Duration.ofSeconds(5));
        assertThat(finishedOps.status()).isEqualTo(ExpertTaskStatus.FAILED);
        assertThat(finishedOps.error()).contains("503");

        assertThat(awaitRoundFinal(sessionId)).isTrue();
        assertThat(pendingRegistry.hasPending(sessionId)).isFalse();

        List<AiSessionStore.ChatMessage> messages = sessionStore.messages(sessionId);
        assertThat(messages)
                .anyMatch(message -> "data".equals(message.expertId())
                        && Boolean.TRUE.equals(message.metadata()
                        .get(ExpertMessageConstants.META_IS_EXPERT_DELIVERABLE)))
                .anySatisfy(message -> {
                    assertThat(message.expertId()).isEqualTo("ops");
                    assertThat(message.messageType()).isEqualTo(AiMessageType.ERROR.name());
                    assertThat(message.messageStatus()).isEqualTo(AiMessageStatus.FAILED.name());
                    assertThat(message.content()).contains("503");
                })
                .anySatisfy(message -> {
                    assertThat(message.expertId()).isEqualTo("brain");
                    assertThat(message.messageType()).isEqualTo(AiMessageType.TEXT.name());
                    assertThat(Boolean.TRUE.equals(message.metadata()
                            .get(ExpertMessageConstants.META_IS_ROUND_FINAL))).isTrue();
                    assertThat(message.content()).contains("终稿");
                });

        assertThat(brainPrompts.stream().anyMatch(p -> p.contains("数字专家") && p.contains("失败")))
                .isTrue();
        assertThat(brainPrompts.stream().anyMatch(p -> p.contains("数字专家") && p.contains("已完成")))
                .isTrue();
    }

    @Test
    void multiExpert_bothSoftError_persistsErrorsAndBrainStillFinalizes() throws Exception {
        when(dataRuntime.stream(any(ExpertChatInput.class))).thenReturn(Flux.just(
                ExpertRuntimeEvent.text("正在聚合指标…"),
                ExpertRuntimeEvent.error(MODEL_BUSY_503)));
        when(opsRuntime.stream(any(ExpertChatInput.class))).thenReturn(midToolThenSoftError());

        String sessionId = startBrainRound("同时问数与运维排查");
        int roundIndex = sessionStore.peekCurrentRoundIndex(sessionId);
        Map<String, Object> meta = Map.of(
                ExpertMessageConstants.META_ROUND_INDEX, roundIndex, "userName", "admin");

        ExpertTask data = taskService.submit(new ExpertTaskRequest(
                sessionId, "brain", "data", "查告警", null, meta));
        ExpertTask ops = taskService.submit(new ExpertTaskRequest(
                sessionId, "brain", "ops", "查容器", null, meta));

        assertThat(taskService.waitFor(data.taskId(), Duration.ofSeconds(5)).status())
                .isEqualTo(ExpertTaskStatus.FAILED);
        assertThat(taskService.waitFor(ops.taskId(), Duration.ofSeconds(5)).status())
                .isEqualTo(ExpertTaskStatus.FAILED);

        assertThat(awaitRoundFinal(sessionId)).isTrue();
        assertThat(pendingRegistry.hasPending(sessionId)).isFalse();

        List<AiSessionStore.ChatMessage> messages = sessionStore.messages(sessionId);
        assertThat(messages)
                .filteredOn(message -> AiMessageType.ERROR.name().equals(message.messageType()))
                .hasSizeGreaterThanOrEqualTo(2)
                .allSatisfy(message -> assertThat(message.content()).contains("503"));
        assertThat(messages).anySatisfy(message -> {
            assertThat(message.expertId()).isEqualTo("brain");
            assertThat(Boolean.TRUE.equals(message.metadata()
                    .get(ExpertMessageConstants.META_IS_ROUND_FINAL))).isTrue();
            assertThat(message.content()).contains("终稿");
        });
        assertThat(messages).noneMatch(message ->
                ("data".equals(message.expertId()) || "ops".equals(message.expertId()))
                        && Boolean.TRUE.equals(message.metadata()
                        .get(ExpertMessageConstants.META_IS_EXPERT_DELIVERABLE)));

        assertThat(brainPrompts.stream().anyMatch(p -> p.contains("数字专家") && p.contains("失败")))
                .isTrue();
    }

    @Test
    void multiExpert_serialDataThenOpsSoftError_opsFailureVisibleBrainFinalizes() throws Exception {
        when(opsRuntime.stream(any(ExpertChatInput.class))).thenReturn(midToolThenSoftError());

        String sessionId = startBrainRound("先问数再查运维");
        int roundIndex = sessionStore.peekCurrentRoundIndex(sessionId);
        Map<String, Object> meta = Map.of(
                ExpertMessageConstants.META_ROUND_INDEX, roundIndex, "userName", "admin");

        ExpertTask data = taskService.submit(new ExpertTaskRequest(
                sessionId, "brain", "data", "查服务错误率", null, meta));
        assertThat(taskService.waitFor(data.taskId(), Duration.ofSeconds(5)).status())
                .isEqualTo(ExpertTaskStatus.SUCCEEDED);

        ExpertTask ops = taskService.submit(new ExpertTaskRequest(
                sessionId, "brain", "ops", "查 webhook 日志", null, meta));
        assertThat(taskService.waitFor(ops.taskId(), Duration.ofSeconds(5)).status())
                .isEqualTo(ExpertTaskStatus.FAILED);

        assertThat(awaitRoundFinal(sessionId)).isTrue();

        List<AiSessionStore.ChatMessage> messages = sessionStore.messages(sessionId);
        assertThat(messages)
                .anyMatch(message -> "data".equals(message.expertId())
                        && Boolean.TRUE.equals(message.metadata()
                        .get(ExpertMessageConstants.META_IS_EXPERT_DELIVERABLE)))
                .anyMatch(message -> "ops".equals(message.expertId())
                        && AiMessageType.ERROR.name().equals(message.messageType())
                        && message.content() != null
                        && message.content().contains("503"))
                .anyMatch(message -> "brain".equals(message.expertId())
                        && Boolean.TRUE.equals(message.metadata()
                        .get(ExpertMessageConstants.META_IS_ROUND_FINAL)));
    }

    private static Flux<ExpertRuntimeEvent> midToolThenHardError() {
        return Flux.just(
                        ExpertRuntimeEvent.text("连接成功。现在让我检查 DataBuff Agent 状态。"),
                        ExpertRuntimeEvent.toolCall("工具调用开始：Bash"),
                        ExpertRuntimeEvent.toolResult("工具调用结果：Bash"),
                        ExpertRuntimeEvent.text("现在看到新的 databuff-agent Pod 正在运行，让我深入检查："),
                        ExpertRuntimeEvent.toolCall("工具调用开始：Bash"),
                        ExpertRuntimeEvent.toolResult("工具调用结果：Bash"))
                .concatWith(Flux.error(new RuntimeException(MODEL_BUSY_503)));
    }

    private static Flux<ExpertRuntimeEvent> midToolThenSoftError() {
        return Flux.just(
                ExpertRuntimeEvent.text("连接成功。现在让我检查 DataBuff Agent 状态。"),
                ExpertRuntimeEvent.toolCall("工具调用开始：Bash"),
                ExpertRuntimeEvent.toolResult("工具调用结果：Bash"),
                ExpertRuntimeEvent.text("现在看到新的 databuff-agent Pod 正在运行，让我深入检查："),
                ExpertRuntimeEvent.toolCall("工具调用开始：Bash"),
                ExpertRuntimeEvent.toolResult("工具调用结果：Bash"),
                ExpertRuntimeEvent.error(MODEL_BUSY_503));
    }

    private String startBrainRound(String userMessage) {
        String sessionId = sessionStore.ensureSession(null, "brain", "rk", "web-1", "admin");
        sessionStore.appendUserMessage(sessionId, userMessage, "brain", "admin", Map.of());
        sessionStore.reserveAssistantMessageId(sessionId, "brain");
        sessionStore.setRunning(sessionId, true);
        return sessionId;
    }

    private boolean awaitNotRunning(String sessionId) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (!sessionStore.isRunning(sessionId)) {
                return true;
            }
            Thread.sleep(50L);
        }
        return !sessionStore.isRunning(sessionId);
    }

    private boolean awaitRoundFinal(String sessionId) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            boolean roundFinal = sessionStore.messages(sessionId).stream()
                    .anyMatch(message -> "brain".equals(message.expertId())
                            && AiMessageType.TEXT.name().equals(message.messageType())
                            && Boolean.TRUE.equals(message.metadata()
                            .get(ExpertMessageConstants.META_IS_ROUND_FINAL)));
            if (roundFinal && !sessionStore.isRunning(sessionId) && !pendingRegistry.hasPending(sessionId)) {
                return true;
            }
            Thread.sleep(50L);
        }
        return sessionStore.messages(sessionId).stream()
                .anyMatch(message -> "brain".equals(message.expertId())
                        && Boolean.TRUE.equals(message.metadata()
                        .get(ExpertMessageConstants.META_IS_ROUND_FINAL)));
    }

    /**
     * Guard against the original bug: intermediate progress text marked COMPLETED + isRoundFinal
     * without any failure hint.
     */
    private static void assertNoSilentProgressFinal(
            List<AiSessionStore.ChatMessage> messages,
            String expertId) {
        assertThat(messages).noneMatch(message ->
                expertId.equals(message.expertId())
                        && AiMessageType.TEXT.name().equals(message.messageType())
                        && AiMessageStatus.COMPLETED.name().equals(message.messageStatus())
                        && Boolean.TRUE.equals(message.metadata()
                        .get(ExpertMessageConstants.META_IS_ROUND_FINAL))
                        && message.content() != null
                        && message.content().contains("让我深入检查")
                        && !message.content().contains("对话失败"));
    }

    private static ObjectProvider<ExpertRuntimeRegistry> providerOf(ExpertRuntimeRegistry registry) {
        return new ObjectProvider<>() {
            @Override
            public ExpertRuntimeRegistry getObject() {
                return registry;
            }

            @Override
            public ExpertRuntimeRegistry getObject(Object... args) {
                return registry;
            }

            @Override
            public ExpertRuntimeRegistry getIfAvailable() {
                return registry;
            }

            @Override
            public ExpertRuntimeRegistry getIfUnique() {
                return registry;
            }

            @Override
            public void ifAvailable(Consumer<ExpertRuntimeRegistry> consumer) {
                consumer.accept(registry);
            }
        };
    }

    private static ObjectProvider<SessionExpertRuntimeRegistry> providerOf(
            SessionExpertRuntimeRegistry registry) {
        return new ObjectProvider<>() {
            @Override
            public SessionExpertRuntimeRegistry getObject() {
                return registry;
            }

            @Override
            public SessionExpertRuntimeRegistry getObject(Object... args) {
                return registry;
            }

            @Override
            public SessionExpertRuntimeRegistry getIfAvailable() {
                return registry;
            }

            @Override
            public SessionExpertRuntimeRegistry getIfUnique() {
                return registry;
            }

            @Override
            public void ifAvailable(Consumer<SessionExpertRuntimeRegistry> consumer) {
                consumer.accept(registry);
            }
        };
    }

    private static ObjectProvider<BrainRoundContinuer> continuerRefProvider(
            AtomicReference<BrainRoundContinuer> holder) {
        return new ObjectProvider<>() {
            @Override
            public BrainRoundContinuer getObject() {
                return holder.get();
            }

            @Override
            public BrainRoundContinuer getObject(Object... args) {
                return holder.get();
            }

            @Override
            public BrainRoundContinuer getIfAvailable() {
                return holder.get();
            }

            @Override
            public BrainRoundContinuer getIfUnique() {
                return holder.get();
            }

            @Override
            public void ifAvailable(Consumer<BrainRoundContinuer> consumer) {
                BrainRoundContinuer continuer = holder.get();
                if (continuer != null) {
                    consumer.accept(continuer);
                }
            }
        };
    }
}
