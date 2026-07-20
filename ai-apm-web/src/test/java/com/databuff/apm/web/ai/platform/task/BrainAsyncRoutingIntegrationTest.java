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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end: expert deliverable TEXT → brain continuation → round-final TEXT.
 */
class BrainAsyncRoutingIntegrationTest {

    @TempDir
    Path tempDir;

    private AiSessionStore sessionStore;
    private ExpertTaskPendingRegistry pendingRegistry;
    private BrainContinuationService continuationService;
    private ExpertTaskService taskService;
    private AiChatOrchestrator orchestrator;

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
        ExpertRuntime brainRuntime = mock(ExpertRuntime.class);
        when(brainRuntime.stream(any(ExpertChatInput.class))).thenAnswer(invocation -> {
            ExpertChatInput input = invocation.getArgument(0);
            String message = input.message() == null ? "" : input.message();
            if (message.contains("已完成")) {
                return Flux.just(ExpertRuntimeEvent.text("整合后的终稿：共 3 个服务"));
            }
            return Flux.empty();
        });
        when(brainRuntime.chat(any(ExpertChatInput.class)))
                .thenReturn(Mono.just(ExpertChatResult.ok("整合后的终稿：共 3 个服务")));
        when(registry.getOrCreate("data")).thenReturn(dataRuntime);
        when(registry.getOrCreate("brain")).thenReturn(brainRuntime);

        SessionExpertRuntimeRegistry sessionRegistry = mock(SessionExpertRuntimeRegistry.class);
        when(sessionRegistry.getOrCreate(any(String.class), any())).thenAnswer(invocation -> {
            AiExpertDefinition expert = invocation.getArgument(1);
            if (expert != null && "data".equals(expert.expertId())) {
                return dataRuntime;
            }
            return brainRuntime;
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

        boolean roundFinalSeen = false;
        for (int i = 0; i < 80; i++) {
            roundFinalSeen = sessionStore.messages(sessionId).stream()
                    .anyMatch(message -> "TEXT".equals(message.messageType())
                            && "brain".equals(message.expertId())
                            && Boolean.TRUE.equals(message.metadata().get(ExpertMessageConstants.META_IS_ROUND_FINAL)));
            if (roundFinalSeen && !sessionStore.isRunning(sessionId)) {
                break;
            }
            Thread.sleep(50L);
        }

        assertThat(roundFinalSeen).isTrue();
        assertThat(sessionStore.isRunning(sessionId)).isFalse();
        assertThat(sessionStore.messages(sessionId))
                .anySatisfy(message -> {
                    assertThat(message.messageType()).isEqualTo("TEXT");
                    assertThat(message.expertId()).isEqualTo("brain");
                    assertThat(message.content()).contains("整合后的终稿");
                    assertThat(message.metadata().get(ExpertMessageConstants.META_IS_ROUND_FINAL))
                            .isEqualTo(Boolean.TRUE);
                });
        assertThat(pendingRegistry.hasPending(sessionId)).isFalse();
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
