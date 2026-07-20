package com.databuff.apm.web.ai.platform.task;

import com.databuff.apm.web.ai.TestAiSupport;
import com.databuff.apm.web.ai.UpdateLlmProviderRequest;
import com.databuff.apm.web.ai.agent.AiSessionStore;
import com.databuff.apm.web.ai.platform.expert.ExpertManagementService;
import com.databuff.apm.web.ai.platform.runtime.ExpertChatInput;
import com.databuff.apm.web.ai.platform.runtime.ExpertRuntime;
import com.databuff.apm.web.ai.platform.runtime.ExpertRuntimeEvent;
import com.databuff.apm.web.ai.platform.runtime.ExpertRuntimeRegistry;
import com.databuff.apm.web.ai.platform.runtime.SessionExpertRuntimeRegistry;
import com.databuff.apm.web.ai.tool.ApmToolkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExpertAsyncRoutingIntegrationTest {

    @TempDir
    Path tempDir;

    private AiSessionStore sessionStore;
    private ExpertTaskPendingRegistry pendingRegistry;
    private ExpertTaskService taskService;
    private AtomicReference<ExpertTaskCompletionEvent> continuationEvent;

    @BeforeEach
    void setUp() {
        TestAiSupport.AiFixture aiFixture = TestAiSupport.aiFixture();
        aiFixture.agentRuntimeConfig().setCustomSkillsDir(tempDir.toString());
        aiFixture.store().updateProvider("openai", new UpdateLlmProviderRequest(
                null, "sk-test", null, true));
        ExpertManagementService expertManagementService =
                TestAiSupport.aiFixture().buildPlatformRuntime(Mockito.mock(ApmToolkit.class)).expertManagementService();

        sessionStore = new AiSessionStore();
        pendingRegistry = new ExpertTaskPendingRegistry();
        ExpertTaskTextGuard textGuard = new ExpertTaskTextGuard();
        continuationEvent = new AtomicReference<>();
        CountDownLatch continuationLatch = new CountDownLatch(1);
        BrainRoundContinuer continuer = event -> {
            continuationEvent.set(event);
            pendingRegistry.removePending(event.sessionId(), event.taskId());
            continuationLatch.countDown();
        };
        BrainContinuationService continuationService = new BrainContinuationService(
                continuerProvider(continuer), pendingRegistry);

        ExpertRuntimeRegistry runtimeRegistry = mock(ExpertRuntimeRegistry.class);
        ExpertRuntime runtime = mock(ExpertRuntime.class);
        when(runtime.stream(any(ExpertChatInput.class)))
                .thenReturn(Flux.just(ExpertRuntimeEvent.text("最近1小时共 3 个服务")));
        when(runtimeRegistry.getOrCreate("data")).thenReturn(runtime);

        taskService = new ExpertTaskService(
                expertManagementService,
                providerOf(runtimeRegistry),
                emptySessionRegistryProvider(),
                null,
                sessionStore,
                pendingRegistry,
                textGuard,
                continuationService,
                Mockito.mock(com.databuff.apm.web.ai.platform.runtime.SessionWorkspaceService.class),
                new com.databuff.apm.web.ai.platform.runtime.TaskGeneratedFileRegistry());
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        taskService.shutdownForTests();
    }

    @Test
    void expertDeliverableTextTriggersBrainContinuation() throws Exception {
        String sessionId = sessionStore.ensureSession(null, "brain", "rk", "web-1", "admin");
        sessionStore.appendUserMessage(sessionId, "查询最近1小时的服务列表", "brain", "admin", Map.of());
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

        assertThat(pendingRegistry.hasPending(sessionId)).isTrue();

        ExpertTask finished = taskService.waitFor(task.taskId(), Duration.ofSeconds(5));
        assertThat(finished.status()).isEqualTo(ExpertTaskStatus.SUCCEEDED);
        assertThat(pendingRegistry.hasPending(sessionId)).isFalse();

        assertThat(sessionStore.messages(sessionId))
                .anySatisfy(message -> {
                    assertThat(message.messageType()).isEqualTo("TEXT");
                    assertThat(message.expertId()).isEqualTo("data");
                    assertThat(message.metadata().get(ExpertMessageConstants.META_IS_EXPERT_DELIVERABLE))
                            .isEqualTo(Boolean.TRUE);
                    assertThat(message.metadata().get(ExpertMessageConstants.META_TASK_ID))
                            .isEqualTo(task.taskId());
                });

        assertThat(continuationEvent.get()).isNotNull();
        assertThat(continuationEvent.get().sessionId()).isEqualTo(sessionId);
        assertThat(continuationEvent.get().taskId()).isEqualTo(task.taskId());
        assertThat(continuationEvent.get().text()).contains("3 个服务");
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

    private static ObjectProvider<SessionExpertRuntimeRegistry> emptySessionRegistryProvider() {
        return new ObjectProvider<>() {
            @Override
            public SessionExpertRuntimeRegistry getObject() {
                return null;
            }

            @Override
            public SessionExpertRuntimeRegistry getObject(Object... args) {
                return null;
            }

            @Override
            public SessionExpertRuntimeRegistry getIfAvailable() {
                return null;
            }

            @Override
            public SessionExpertRuntimeRegistry getIfUnique() {
                return null;
            }

            @Override
            public void ifAvailable(Consumer<SessionExpertRuntimeRegistry> consumer) {
            }
        };
    }

    private static ObjectProvider<BrainRoundContinuer> continuerProvider(BrainRoundContinuer continuer) {
        return new ObjectProvider<>() {
            @Override
            public BrainRoundContinuer getObject() {
                return continuer;
            }

            @Override
            public BrainRoundContinuer getObject(Object... args) {
                return continuer;
            }

            @Override
            public BrainRoundContinuer getIfAvailable() {
                return continuer;
            }

            @Override
            public BrainRoundContinuer getIfUnique() {
                return continuer;
            }

            @Override
            public void ifAvailable(Consumer<BrainRoundContinuer> consumer) {
                consumer.accept(continuer);
            }
        };
    }
}
