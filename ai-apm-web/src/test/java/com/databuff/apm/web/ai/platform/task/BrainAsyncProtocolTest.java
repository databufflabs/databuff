package com.databuff.apm.web.ai.platform.task;

import com.databuff.apm.web.ai.TestAiSupport;
import com.databuff.apm.web.ai.TestBeanSupport;
import com.databuff.apm.web.ai.OpenAiCompatibleChatClient;
import com.databuff.apm.web.ai.UpdateLlmProviderRequest;
import com.databuff.apm.web.ai.agent.AiChatOrchestrator;
import com.databuff.apm.web.ai.agent.AiRuntimeForwarder;
import com.databuff.apm.web.ai.agent.AiRuntimeRouter;
import com.databuff.apm.web.ai.agent.AiSessionStore;
import com.databuff.apm.web.ai.platform.runtime.ExpertChatInput;
import com.databuff.apm.web.ai.platform.runtime.ExpertRuntime;
import com.databuff.apm.web.ai.platform.runtime.ExpertRuntimeEvent;
import com.databuff.apm.web.ai.platform.runtime.ExpertRuntimeRegistry;
import com.databuff.apm.web.ai.platform.runtime.SessionExpertRuntimeRegistry;
import com.databuff.apm.web.ai.platform.expert.AiExpertDefinition;
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
 * Protocol-level tests for brain ↔ expert async collaboration.
 * Covers failure, mixed success/failure, intermediate demote, and no-peer-cancel.
 */
class BrainAsyncProtocolTest {

    @TempDir
    Path tempDir;

    private AiSessionStore sessionStore;
    private ExpertTaskPendingRegistry pendingRegistry;
    private BrainContinuationService continuationService;
    private ExpertTaskService taskService;
    private AiChatOrchestrator orchestrator;
    private final List<String> brainPrompts = new ArrayList<>();
    private final AtomicInteger brainStreamCalls = new AtomicInteger();
    private final AtomicInteger brainCancelCount = new AtomicInteger();

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
        continuationService = new BrainContinuationService(providerOf(continuerRef), pendingRegistry);

        ExpertRuntimeRegistry registry = mock(ExpertRuntimeRegistry.class);
        ExpertRuntime dataRuntime = mock(ExpertRuntime.class);
        when(dataRuntime.stream(any(ExpertChatInput.class)))
                .thenReturn(Flux.just(ExpertRuntimeEvent.text("data结论：3个服务")));
        ExpertRuntime opsRuntime = mock(ExpertRuntime.class);
        when(opsRuntime.stream(any(ExpertChatInput.class)))
                .thenReturn(Flux.just(ExpertRuntimeEvent.text("ops结论：磁盘正常")));
        ExpertRuntime inspectionRuntime = mock(ExpertRuntime.class);
        when(inspectionRuntime.stream(any(ExpertChatInput.class)))
                .thenReturn(Flux.error(new RuntimeException("inspection工具连接超时")));

        ExpertRuntime brainRuntime = mock(ExpertRuntime.class);
        when(brainRuntime.stream(any(ExpertChatInput.class))).thenAnswer(invocation -> {
            ExpertChatInput input = invocation.getArgument(0);
            String message = input.message() == null ? "" : input.message();
            synchronized (brainPrompts) {
                brainPrompts.add(message);
            }
            brainStreamCalls.incrementAndGet();
            if (message.contains("pending=0") || message.contains("均已结束")) {
                return Flux.just(ExpertRuntimeEvent.text("终稿：汇总完成"));
            }
            if (message.contains("pending>0") || message.contains("仍有未完成")) {
                return Flux.just(ExpertRuntimeEvent.text("中间：等待其余专家"));
            }
            return Flux.empty();
        });
        when(brainRuntime.chat(any(ExpertChatInput.class)))
                .thenReturn(Mono.just(com.databuff.apm.web.ai.platform.runtime.ExpertChatResult.ok("终稿")));
        when(registry.getOrCreate("data")).thenReturn(dataRuntime);
        when(registry.getOrCreate("ops")).thenReturn(opsRuntime);
        when(registry.getOrCreate("inspection")).thenReturn(inspectionRuntime);
        when(registry.getOrCreate("brain")).thenReturn(brainRuntime);

        SessionExpertRuntimeRegistry sessionRegistry = mock(SessionExpertRuntimeRegistry.class);
        when(sessionRegistry.getOrCreate(any(String.class), any())).thenAnswer(invocation -> {
            AiExpertDefinition expert = invocation.getArgument(1);
            if (expert == null) return brainRuntime;
            return switch (expert.expertId()) {
                case "data" -> dataRuntime;
                case "ops" -> opsRuntime;
                case "inspection" -> inspectionRuntime;
                default -> brainRuntime;
            };
        });

        taskService = new ExpertTaskService(
                fixture.expertManagementService(),
                registryProviderOf(registry),
                sessionRegistryProviderOf(sessionRegistry),
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
    void expertFailureStillTriggersBrainFinalText() throws Exception {
        String sessionId = sessionStore.ensureSession(null, "brain", "rk", "web-1", "admin");
        sessionStore.appendUserMessage(sessionId, "巡检服务", "brain", "admin", Map.of());
        sessionStore.reserveAssistantMessageId(sessionId, "brain");
        sessionStore.setRunning(sessionId, true);
        int roundIndex = sessionStore.peekCurrentRoundIndex(sessionId);
        Map<String, Object> meta = Map.of(
                ExpertMessageConstants.META_ROUND_INDEX, roundIndex, "userName", "admin");

        ExpertTask task = taskService.submit(new ExpertTaskRequest(
                sessionId, "brain", "inspection", "巡检", null, meta));

        ExpertTask finished = taskService.waitFor(task.taskId(), Duration.ofSeconds(5));
        assertThat(finished.status()).isEqualTo(ExpertTaskStatus.FAILED);

        assertThat(awaitRoundFinal(sessionId)).isTrue();
        assertThat(pendingRegistry.hasPending(sessionId)).isFalse();

        assertThat(sessionStore.messages(sessionId))
                .anySatisfy(message -> {
                    assertThat(message.expertId()).isEqualTo("brain");
                    assertThat(message.messageType()).isEqualTo("TEXT");
                    assertThat(Boolean.TRUE.equals(message.metadata().get(ExpertMessageConstants.META_IS_ROUND_FINAL)))
                            .isTrue();
                    assertThat(message.content()).contains("终稿");
                });
    }

    @Test
    void mixedSuccessAndFailureBrainFinalizesWithBothResults() throws Exception {
        String sessionId = sessionStore.ensureSession(null, "brain", "rk", "web-1", "admin");
        sessionStore.appendUserMessage(sessionId, "并行排查", "brain", "admin", Map.of());
        sessionStore.reserveAssistantMessageId(sessionId, "brain");
        sessionStore.setRunning(sessionId, true);
        int roundIndex = sessionStore.peekCurrentRoundIndex(sessionId);
        Map<String, Object> meta = Map.of(
                ExpertMessageConstants.META_ROUND_INDEX, roundIndex, "userName", "admin");

        ExpertTask ops = taskService.submit(new ExpertTaskRequest(
                sessionId, "brain", "ops", "排查环境", null, meta));
        ExpertTask inspection = taskService.submit(new ExpertTaskRequest(
                sessionId, "brain", "inspection", "巡检", null, meta));

        assertThat(taskService.waitFor(ops.taskId(), Duration.ofSeconds(5)).status())
                .isEqualTo(ExpertTaskStatus.SUCCEEDED);
        assertThat(taskService.waitFor(inspection.taskId(), Duration.ofSeconds(5)).status())
                .isEqualTo(ExpertTaskStatus.FAILED);

        assertThat(awaitRoundFinal(sessionId)).isTrue();
        assertThat(pendingRegistry.hasPending(sessionId)).isFalse();

        assertThat(sessionStore.messages(sessionId))
                .anyMatch(message -> "ops".equals(message.expertId())
                        && Boolean.TRUE.equals(message.metadata().get(ExpertMessageConstants.META_IS_EXPERT_DELIVERABLE)))
                .anyMatch(message -> "inspection".equals(message.expertId())
                        && "ERROR".equals(message.messageType()));

        assertThat(sessionStore.messages(sessionId))
                .anySatisfy(message -> {
                    assertThat(message.expertId()).isEqualTo("brain");
                    assertThat(Boolean.TRUE.equals(message.metadata().get(ExpertMessageConstants.META_IS_ROUND_FINAL)))
                            .isTrue();
                    assertThat(message.content()).contains("终稿");
                });

        synchronized (brainPrompts) {
            assertThat(brainPrompts.stream().anyMatch(p -> p.contains("pending>0") || p.contains("仍有未完成")))
                    .isTrue();
            assertThat(brainPrompts.stream().anyMatch(p -> p.contains("pending=0") || p.contains("均已结束")))
                    .isTrue();
        }
    }

    @Test
    void intermediateBrainTextDemotedToReasoningWhilePending() throws Exception {
        String sessionId = sessionStore.ensureSession(null, "brain", "rk", "web-1", "admin");
        sessionStore.appendUserMessage(sessionId, "并行排查", "brain", "admin", Map.of());
        sessionStore.reserveAssistantMessageId(sessionId, "brain");
        sessionStore.setRunning(sessionId, true);
        int roundIndex = sessionStore.peekCurrentRoundIndex(sessionId);
        Map<String, Object> meta = Map.of(
                ExpertMessageConstants.META_ROUND_INDEX, roundIndex, "userName", "admin");

        ExpertTask ops = taskService.submit(new ExpertTaskRequest(
                sessionId, "brain", "ops", "排查", null, meta));
        ExpertTask inspection = taskService.submit(new ExpertTaskRequest(
                sessionId, "brain", "inspection", "巡检", null, meta));

        taskService.waitFor(ops.taskId(), Duration.ofSeconds(5));
        taskService.waitFor(inspection.taskId(), Duration.ofSeconds(5));

        assertThat(awaitRoundFinal(sessionId)).isTrue();

        List<AiSessionStore.ChatMessage> messages = sessionStore.messages(sessionId);
        boolean hasIntermediateReasoning = messages.stream()
                .anyMatch(message -> "brain".equals(message.expertId())
                        && "REASONING".equals(message.messageType())
                        && message.content() != null
                        && message.content().contains("中间"));
        assertThat(hasIntermediateReasoning).isTrue();

        long roundFinalCount = messages.stream()
                .filter(message -> "brain".equals(message.expertId())
                        && "TEXT".equals(message.messageType())
                        && Boolean.TRUE.equals(message.metadata().get(ExpertMessageConstants.META_IS_ROUND_FINAL)))
                .count();
        assertThat(roundFinalCount).isEqualTo(1L);
    }

    private boolean awaitRoundFinal(String sessionId) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            boolean seen = sessionStore.messages(sessionId).stream()
                    .anyMatch(message -> "TEXT".equals(message.messageType())
                            && "brain".equals(message.expertId())
                            && Boolean.TRUE.equals(message.metadata().get(ExpertMessageConstants.META_IS_ROUND_FINAL)));
            if (seen && !sessionStore.isRunning(sessionId)) return true;
            Thread.sleep(50L);
        }
        return sessionStore.messages(sessionId).stream()
                .anyMatch(message -> "TEXT".equals(message.messageType())
                        && "brain".equals(message.expertId())
                        && Boolean.TRUE.equals(message.metadata().get(ExpertMessageConstants.META_IS_ROUND_FINAL)));
    }

    private static <T> ObjectProvider<T> providerOf(AtomicReference<T> ref) {
        return new ObjectProvider<>() {
            @Override public T getObject() { return ref.get(); }
            @Override public T getObject(Object... args) { return ref.get(); }
            @Override public T getIfAvailable() { return ref.get(); }
            @Override public T getIfUnique() { return ref.get(); }
            @Override public void ifAvailable(Consumer<T> c) { T v = ref.get(); if (v != null) c.accept(v); }
        };
    }

    private static ObjectProvider<ExpertRuntimeRegistry> registryProviderOf(ExpertRuntimeRegistry registry) {
        return new ObjectProvider<>() {
            @Override public ExpertRuntimeRegistry getObject() { return registry; }
            @Override public ExpertRuntimeRegistry getObject(Object... args) { return registry; }
            @Override public ExpertRuntimeRegistry getIfAvailable() { return registry; }
            @Override public ExpertRuntimeRegistry getIfUnique() { return registry; }
            @Override public void ifAvailable(Consumer<ExpertRuntimeRegistry> c) { c.accept(registry); }
        };
    }

    private static ObjectProvider<SessionExpertRuntimeRegistry> sessionRegistryProviderOf(
            SessionExpertRuntimeRegistry registry) {
        return new ObjectProvider<>() {
            @Override public SessionExpertRuntimeRegistry getObject() { return registry; }
            @Override public SessionExpertRuntimeRegistry getObject(Object... args) { return registry; }
            @Override public SessionExpertRuntimeRegistry getIfAvailable() { return registry; }
            @Override public SessionExpertRuntimeRegistry getIfUnique() { return registry; }
            @Override public void ifAvailable(Consumer<SessionExpertRuntimeRegistry> c) { c.accept(registry); }
        };
    }
}
