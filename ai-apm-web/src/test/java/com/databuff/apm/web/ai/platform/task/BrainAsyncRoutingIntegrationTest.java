package com.databuff.apm.web.ai.platform.task;

import com.databuff.apm.web.ai.TestAiSupport;
import com.databuff.apm.web.ai.TestBeanSupport;
import com.databuff.apm.web.ai.OpenAiCompatibleChatClient;
import com.databuff.apm.web.ai.UpdateLlmProviderRequest;
import com.databuff.apm.web.ai.agent.AiChatOrchestrator;
import com.databuff.apm.web.ai.agent.AiRuntimeForwarder;
import com.databuff.apm.web.ai.agent.AiRuntimeRouter;
import com.databuff.apm.web.support.WebTestClusterSupport;
import com.databuff.apm.web.ai.agent.AiSessionStore;
import com.databuff.apm.web.ai.platform.runtime.ExpertChatInput;
import com.databuff.apm.web.ai.platform.runtime.ExpertChatResult;
import com.databuff.apm.web.ai.platform.runtime.ExpertRuntime;
import com.databuff.apm.web.ai.platform.runtime.ExpertRuntimeEvent;
import com.databuff.apm.web.ai.platform.runtime.ExpertRuntimeRegistry;
import com.databuff.apm.web.ai.platform.runtime.SessionExpertRuntimeRegistry;
import com.databuff.apm.web.ai.platform.expert.AiExpertDefinition;
import com.databuff.apm.web.ai.tool.ApmToolkit;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end: expert deliverable TEXT → brain continuation → round-final TEXT.
 * Covers single-expert, parallel multi-expert (same session serial fan-in), and cross-session isolation.
 */
class BrainAsyncRoutingIntegrationTest {

    @TempDir
    Path tempDir;

    private AiSessionStore sessionStore;
    private ExpertTaskPendingRegistry pendingRegistry;
    private BrainContinuationService continuationService;
    private ExpertTaskService taskService;
    private AiChatOrchestrator orchestrator;
    private final AtomicInteger brainStreamCalls = new AtomicInteger();
    private final AtomicInteger brainChatCalls = new AtomicInteger();
    private final List<String> brainPrompts = new ArrayList<>();

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
        continuationService = new BrainContinuationService(continuerRefProvider(continuerRef), pendingRegistry);

        ExpertRuntimeRegistry registry = mock(ExpertRuntimeRegistry.class);
        ExpertRuntime dataRuntime = mock(ExpertRuntime.class);
        when(dataRuntime.stream(any(ExpertChatInput.class)))
                .thenReturn(Flux.just(ExpertRuntimeEvent.text("最近1小时共 3 个服务")));
        ExpertRuntime opsRuntime = mock(ExpertRuntime.class);
        when(opsRuntime.stream(any(ExpertChatInput.class)))
                .thenReturn(Flux.just(ExpertRuntimeEvent.text("ops结论：磁盘正常")));
        ExpertRuntime inspectionRuntime = mock(ExpertRuntime.class);
        when(inspectionRuntime.stream(any(ExpertChatInput.class)))
                .thenReturn(Flux.just(ExpertRuntimeEvent.text("inspection结论：错误率偏高")));

        ExpertRuntime brainRuntime = mock(ExpertRuntime.class);
        when(brainRuntime.stream(any(ExpertChatInput.class))).thenAnswer(invocation -> {
            ExpertChatInput input = invocation.getArgument(0);
            String message = input.message() == null ? "" : input.message();
            synchronized (brainPrompts) {
                brainPrompts.add(message);
            }
            brainStreamCalls.incrementAndGet();
            if (message.contains("数字专家")
                    && (message.contains("已完成") || message.contains("失败"))) {
                if (message.contains("ops") || message.contains("inspection")) {
                    return Flux.just(ExpertRuntimeEvent.text("终稿：已汇总 ops 与 inspection"));
                }
                return Flux.just(ExpertRuntimeEvent.text("整合后的终稿：共 3 个服务"));
            }
            return Flux.empty();
        });
        when(brainRuntime.chat(any(ExpertChatInput.class))).thenAnswer(invocation -> {
            brainChatCalls.incrementAndGet();
            return Mono.just(ExpertChatResult.ok("SECOND_CALL_MUST_NOT_BECOME_FINAL"));
        });
        when(registry.getOrCreate("data")).thenReturn(dataRuntime);
        when(registry.getOrCreate("ops")).thenReturn(opsRuntime);
        when(registry.getOrCreate("inspection")).thenReturn(inspectionRuntime);
        when(registry.getOrCreate("brain")).thenReturn(brainRuntime);

        SessionExpertRuntimeRegistry sessionRegistry = mock(SessionExpertRuntimeRegistry.class);
        when(sessionRegistry.getOrCreate(any(String.class), any())).thenAnswer(invocation -> {
            AiExpertDefinition expert = invocation.getArgument(1);
            if (expert == null) {
                return brainRuntime;
            }
            return switch (expert.expertId()) {
                case "data" -> dataRuntime;
                case "ops" -> opsRuntime;
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
    void expertDeliverableTriggersBrainRoundFinalText() throws Exception {
        String sessionId = sessionStore.ensureSession(null, "brain", "rk", "web-1", "admin");
        sessionStore.appendUserMessage(sessionId, "查询最近1小时的服务列表", "brain", "admin", Map.of());
        sessionStore.reserveAssistantMessageId(sessionId, "brain");
        sessionStore.setRunning(sessionId, true);
        int roundIndex = sessionStore.peekCurrentRoundIndex(sessionId);

        ExpertTask task = taskService.submit(new ExpertTaskRequest(
                sessionId,
                "brain",
                "data",
                "查询最近1小时的服务列表",
                null,
                Map.of(
                        ExpertMessageConstants.META_ROUND_INDEX, roundIndex,
                        "userName", "admin")));

        ExpertTask finished = taskService.waitFor(task.taskId(), Duration.ofSeconds(5));
        assertThat(finished.status()).isEqualTo(ExpertTaskStatus.SUCCEEDED);

        assertThat(awaitRoundFinal(sessionId)).isTrue();
        assertThat(sessionStore.isRunning(sessionId)).isFalse();
        assertThat(sessionStore.messages(sessionId))
                .anySatisfy(message -> {
                    assertThat(message.messageType()).isEqualTo("TEXT");
                    assertThat(message.expertId()).isEqualTo("brain");
                    assertThat(message.content()).isEqualTo("整合后的终稿：共 3 个服务");
                    assertThat(message.metadata().get(ExpertMessageConstants.META_IS_ROUND_FINAL))
                            .isEqualTo(Boolean.TRUE);
                    assertThat(message.metadata().get(ExpertMessageConstants.META_TRIGGER_SOURCE))
                            .isEqualTo(ExpertMessageConstants.TRIGGER_EXPERT_RESULT);
                });
        assertThat(brainStreamCalls.get()).isEqualTo(1);
        assertThat(brainChatCalls.get())
                .as("one expert completion must invoke the brain exactly once")
                .isZero();
        assertThat(pendingRegistry.hasPending(sessionId)).isFalse();
    }

    @Test
    void parallelExpertsSameSessionAreConsumedSeriallyWithoutCancel() throws Exception {
        String sessionId = sessionStore.ensureSession(null, "brain", "rk", "web-1", "admin");
        sessionStore.appendUserMessage(sessionId, "并行排查 mysql", "brain", "admin", Map.of());
        sessionStore.reserveAssistantMessageId(sessionId, "brain");
        sessionStore.setRunning(sessionId, true);
        int roundIndex = sessionStore.peekCurrentRoundIndex(sessionId);
        Map<String, Object> meta = Map.of(
                ExpertMessageConstants.META_ROUND_INDEX, roundIndex,
                "userName", "admin");

        ExpertTask ops = taskService.submit(new ExpertTaskRequest(
                sessionId, "brain", "ops", "排查运行环境", null, meta));
        ExpertTask inspection = taskService.submit(new ExpertTaskRequest(
                sessionId, "brain", "inspection", "健康巡检", null, meta));

        assertThat(taskService.waitFor(ops.taskId(), Duration.ofSeconds(5)).status())
                .isEqualTo(ExpertTaskStatus.SUCCEEDED);
        assertThat(taskService.waitFor(inspection.taskId(), Duration.ofSeconds(5)).status())
                .isEqualTo(ExpertTaskStatus.SUCCEEDED);

        assertThat(awaitRoundFinal(sessionId)).isTrue();
        assertThat(sessionStore.isRunning(sessionId)).isFalse();
        assertThat(pendingRegistry.hasPending(sessionId)).isFalse();

        assertThat(sessionStore.messages(sessionId))
                .anyMatch(message -> "ops".equals(message.expertId())
                        && Boolean.TRUE.equals(message.metadata().get(ExpertMessageConstants.META_IS_EXPERT_DELIVERABLE)))
                .anyMatch(message -> "inspection".equals(message.expertId())
                        && Boolean.TRUE.equals(message.metadata().get(ExpertMessageConstants.META_IS_EXPERT_DELIVERABLE)));

        assertThat(sessionStore.messages(sessionId))
                .anySatisfy(message -> {
                    assertThat(message.expertId()).isEqualTo("brain");
                    assertThat(message.messageType()).isEqualTo("TEXT");
                    assertThat(Boolean.TRUE.equals(message.metadata().get(ExpertMessageConstants.META_IS_ROUND_FINAL)))
                            .isTrue();
                    assertThat(message.content()).contains("终稿");
                    assertThat(message.content()).doesNotContain("继续等待");
                });

        assertThat(brainStreamCalls.get()).isEqualTo(2);
        assertThat(brainChatCalls.get())
                .as("two expert completions must produce two brain invocations, not a third recovery call")
                .isZero();
        synchronized (brainPrompts) {
            assertThat(brainPrompts.stream().anyMatch(p -> p.contains("数字专家") && p.contains("已完成")))
                    .isTrue();
            assertThat(brainPrompts.stream().anyMatch(p -> p.contains("本轮用户原请求")))
                    .isTrue();
            assertThat(brainPrompts.stream().noneMatch(p -> p.contains("pending=") || p.contains("[系统]")))
                    .isTrue();
            assertThat(brainPrompts.stream().noneMatch(p -> p.contains("[Context:")))
                    .isTrue();
            assertThat(brainPrompts).allSatisfy(prompt -> {
                ExpertMessageContractTest.assertNoBanned(
                        prompt, ExpertMessageContractTest.PROTOCOL_COACHING_BANNED);
                ExpertMessageContractTest.assertNoBanned(
                        prompt, ExpertMessageContractTest.CONTEXT_HEADER_BANNED);
            });
        }
    }

    @Test
    void differentSessionsDrainConcurrentlyAndStayIsolated() throws Exception {
        String sessionA = sessionStore.ensureSession(null, "brain", "rk", "web-1", "admin");
        String sessionB = sessionStore.ensureSession(null, "brain", "rk", "web-1", "admin");
        sessionStore.appendUserMessage(sessionA, "session-A", "brain", "admin", Map.of());
        sessionStore.appendUserMessage(sessionB, "session-B", "brain", "admin", Map.of());
        sessionStore.reserveAssistantMessageId(sessionA, "brain");
        sessionStore.reserveAssistantMessageId(sessionB, "brain");
        sessionStore.setRunning(sessionA, true);
        sessionStore.setRunning(sessionB, true);

        int roundA = sessionStore.peekCurrentRoundIndex(sessionA);
        int roundB = sessionStore.peekCurrentRoundIndex(sessionB);

        ExpertTask taskA = taskService.submit(new ExpertTaskRequest(
                sessionA, "brain", "data", "A任务", null,
                Map.of(ExpertMessageConstants.META_ROUND_INDEX, roundA, "userName", "admin")));
        ExpertTask taskB = taskService.submit(new ExpertTaskRequest(
                sessionB, "brain", "data", "B任务", null,
                Map.of(ExpertMessageConstants.META_ROUND_INDEX, roundB, "userName", "admin")));

        assertThat(taskService.waitFor(taskA.taskId(), Duration.ofSeconds(5)).status())
                .isEqualTo(ExpertTaskStatus.SUCCEEDED);
        assertThat(taskService.waitFor(taskB.taskId(), Duration.ofSeconds(5)).status())
                .isEqualTo(ExpertTaskStatus.SUCCEEDED);

        assertThat(awaitRoundFinal(sessionA)).isTrue();
        assertThat(awaitRoundFinal(sessionB)).isTrue();

        assertThat(sessionStore.messages(sessionA))
                .noneMatch(message -> message.content() != null && message.content().contains("session-B"));
        assertThat(sessionStore.messages(sessionB))
                .noneMatch(message -> message.content() != null && message.content().contains("session-A"));
        assertThat(pendingRegistry.hasPending(sessionA)).isFalse();
        assertThat(pendingRegistry.hasPending(sessionB)).isFalse();
    }

    private boolean awaitRoundFinal(String sessionId) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            boolean roundFinalSeen = sessionStore.messages(sessionId).stream()
                    .anyMatch(message -> "TEXT".equals(message.messageType())
                            && "brain".equals(message.expertId())
                            && Boolean.TRUE.equals(message.metadata().get(ExpertMessageConstants.META_IS_ROUND_FINAL)));
            if (roundFinalSeen && !sessionStore.isRunning(sessionId)) {
                return true;
            }
            Thread.sleep(50L);
        }
        return sessionStore.messages(sessionId).stream()
                .anyMatch(message -> "TEXT".equals(message.messageType())
                        && "brain".equals(message.expertId())
                        && Boolean.TRUE.equals(message.metadata().get(ExpertMessageConstants.META_IS_ROUND_FINAL)));
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
