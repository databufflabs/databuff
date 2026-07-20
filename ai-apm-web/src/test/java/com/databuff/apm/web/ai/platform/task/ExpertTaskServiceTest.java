package com.databuff.apm.web.ai.platform.task;

import com.databuff.apm.web.ai.TestAiSupport;
import com.databuff.apm.web.ai.UpdateLlmProviderRequest;
import com.databuff.apm.web.ai.platform.expert.ExpertManagementService;
import com.databuff.apm.web.ai.agent.AiSessionStore;
import com.databuff.apm.web.ai.platform.runtime.ExpertChatInput;
import com.databuff.apm.web.ai.platform.runtime.ExpertChatResult;
import com.databuff.apm.web.ai.platform.runtime.ExpertRuntime;
import com.databuff.apm.web.ai.platform.runtime.ExpertRuntimeEvent;
import com.databuff.apm.web.ai.platform.runtime.ExpertRuntimeRegistry;
import com.databuff.apm.web.ai.platform.runtime.SessionExpertRuntimeRegistry;
import com.databuff.apm.web.ai.platform.runtime.SessionWorkspaceService;
import com.databuff.apm.web.ai.platform.runtime.TaskGeneratedFileRegistry;
import com.databuff.apm.web.ai.tool.ApmToolkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExpertTaskServiceTest {

    @TempDir
    Path tempDir;

    private ExpertTaskService taskService;
    private AiSessionStore sessionStore;
    private ExpertTaskPendingRegistry pendingRegistry;
    private SessionWorkspaceService workspaceService;
    private TaskGeneratedFileRegistry generatedFileRegistry;

    @BeforeEach
    void setUp() {
        TestAiSupport.AiFixture aiFixture = TestAiSupport.aiFixture();
        aiFixture.agentRuntimeConfig().setCustomSkillsDir(tempDir.toString());
        aiFixture.agentRuntimeConfig().setWorkspaceDir(tempDir.resolve("workspaces").toString());
        aiFixture.store().updateProvider("openai", new UpdateLlmProviderRequest(
                null, "sk-test", null, true));
        TestAiSupport.PlatformRuntimeFixture fixture =
                aiFixture.buildPlatformRuntime(Mockito.mock(ApmToolkit.class));
        ExpertRuntimeRegistry runtimeRegistry = mock(ExpertRuntimeRegistry.class);
        SessionExpertRuntimeRegistry sessionRuntimeRegistry = mock(SessionExpertRuntimeRegistry.class);
        ExpertRuntime runtime = mock(ExpertRuntime.class);
        when(runtime.stream(any(ExpertChatInput.class)))
                .thenReturn(Flux.just(ExpertRuntimeEvent.text("metrics ok")));
        when(runtime.chat(any(ExpertChatInput.class)))
                .thenReturn(Mono.just(ExpertChatResult.ok("metrics ok")));
        when(runtimeRegistry.getOrCreate("data")).thenReturn(runtime);
        when(sessionRuntimeRegistry.getOrCreate(any(String.class), any())).thenReturn(runtime);
        sessionStore = new AiSessionStore();
        pendingRegistry = new ExpertTaskPendingRegistry();
        ExpertTaskTextGuard taskTextGuard = new ExpertTaskTextGuard();
        BrainContinuationService brainContinuationService = new BrainContinuationService(
                emptyContinuerProvider(), pendingRegistry);
        workspaceService = fixture.sessionWorkspaceService();
        generatedFileRegistry = new TaskGeneratedFileRegistry();
        taskService = new ExpertTaskService(
                fixture.expertManagementService(),
                providerOf(runtimeRegistry),
                providerOf(sessionRuntimeRegistry),
                null,
                sessionStore,
                pendingRegistry,
                taskTextGuard,
                brainContinuationService,
                workspaceService,
                generatedFileRegistry);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        taskService.shutdownForTests();
    }

    @Test
    void awaitingBrainTaskCompletionNotificationsUntilBrainNotified() {
        String sessionId = sessionStore.ensureSession(null, "brain", "rk", "web-1", "admin");
        ExpertTask task = new ExpertTask(
                "task-notify",
                null,
                sessionId,
                "brain",
                "data",
                ExpertTaskStatus.SUCCEEDED,
                "input",
                "done",
                null,
                Map.of(ExpertMessageConstants.META_ROUND_INDEX, 1),
                Instant.now(),
                Instant.now(),
                Instant.now());
        taskService.registerTaskForTests(task);
        pendingRegistry.addPending(sessionId, task.taskId());

        assertThat(taskService.awaitingBrainTaskCompletionNotifications(sessionId, 1)).isTrue();

        pendingRegistry.removePending(sessionId, task.taskId());

        assertThat(taskService.awaitingBrainTaskCompletionNotifications(sessionId, 1)).isFalse();
    }

    @Test
    void generatedFilesAreAttachedToOwningTaskMetadata() throws Exception {
        String sessionId = sessionStore.ensureSession(null, "brain", "rk", "web-1", "admin");
        String taskId = "task-report";
        workspaceService.ensureOutputsDir(sessionId);
        Files.writeString(
                workspaceService.resolveRelativePath(sessionId, "outputs/report.html"),
                "<html>report</html>");
        generatedFileRegistry.record(sessionId, taskId, "outputs/report.html");
        ExpertTask task = new ExpertTask(
                taskId,
                null,
                sessionId,
                "brain",
                "inspection",
                ExpertTaskStatus.RUNNING,
                "inspect",
                null,
                null,
                Map.of(ExpertMessageConstants.META_ROUND_INDEX, 1),
                Instant.now(),
                Instant.now(),
                null);

        ExpertTask enriched = taskService.withGeneratedFiles(task);

        assertThat((List<?>) enriched.metadata().get("generatedFiles"))
                .singleElement()
                .satisfies(item -> assertThat(((Map<?, ?>) item).get("filePath"))
                        .isEqualTo("outputs/report.html"));
        assertThat(generatedFileRegistry.paths(sessionId, taskId)).isEmpty();
    }

    @Test
    void cancellingSessionClearsPendingGeneratedFileOwnership() {
        String sessionId = sessionStore.ensureSession(null, "brain", "rk", "web-1", "admin");
        String taskId = "task-cancelled";
        pendingRegistry.addPending(sessionId, taskId);
        generatedFileRegistry.record(sessionId, taskId, "outputs/partial-report.html");

        taskService.cancelSessionTasks(sessionId);

        assertThat(pendingRegistry.hasPending(sessionId)).isFalse();
        assertThat(generatedFileRegistry.paths(sessionId, taskId)).isEmpty();
    }

    @Test
    void awaitingSubExpertResponsesTrueUntilDeliverableInSession() {
        AiSessionStore sessionStore = new AiSessionStore();
        ExpertTaskPendingRegistry pendingRegistry = new ExpertTaskPendingRegistry();
        ExpertTaskService service = new ExpertTaskService(
                Mockito.mock(ExpertManagementService.class),
                providerOf((ExpertRuntimeRegistry) null),
                providerOf((SessionExpertRuntimeRegistry) null),
                null,
                sessionStore,
                pendingRegistry,
                new ExpertTaskTextGuard(),
                Mockito.mock(BrainContinuationService.class),
                workspaceService,
                generatedFileRegistry);
        String sessionId = sessionStore.ensureSession(null, "brain", "rk", "web-1", "admin");
        sessionStore.appendUserMessage(sessionId, "hello", "brain", "admin", java.util.Map.of());
        ExpertTask task = new ExpertTask(
                "task-await",
                null,
                sessionId,
                "brain",
                "data",
                ExpertTaskStatus.RUNNING,
                "input",
                null,
                null,
                java.util.Map.of(ExpertMessageConstants.META_ROUND_INDEX, 1),
                java.time.Instant.now(),
                java.time.Instant.now(),
                null);
        service.registerTaskForTests(task);

        assertThat(service.awaitingSubExpertResponses(sessionId, 1)).isTrue();

        sessionStore.appendExpertDeliverableText(
                sessionId,
                "data",
                1,
                task.taskId(),
                "done",
                java.util.Map.of());

        assertThat(service.awaitingSubExpertResponses(sessionId, 1)).isFalse();
    }

    @Test
    void submitRunsTaskAsynchronously() throws Exception {
        ExpertTask created = taskService.submit(new ExpertTaskRequest(
                "s-1", "brain", "data", "query error rate", null, java.util.Map.of()));

        ExpertTask finished = taskService.waitFor(created.taskId(), Duration.ofSeconds(5));

        assertThat(finished.status()).isEqualTo(ExpertTaskStatus.SUCCEEDED);
        assertThat(finished.output()).isEqualTo("metrics ok");
        assertThat(taskService.listBySession("s-1")).hasSize(1);
    }

    @Test
    void brainDispatchMarksSessionRunning() {
        String sessionId = sessionStore.ensureSession(null, "brain", "rk", "web-1", "admin");
        sessionStore.setRunning(sessionId, false);

        ExpertTask created = taskService.submit(new ExpertTaskRequest(
                sessionId, "brain", "data", "query error rate", null, java.util.Map.of()));

        assertThat(created.sourceExpertId()).isEqualTo("brain");
        assertThat(sessionStore.isRunning(sessionId)).isTrue();
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

    private static ObjectProvider<BrainRoundContinuer> emptyContinuerProvider() {
        return new ObjectProvider<>() {
            @Override
            public BrainRoundContinuer getObject() {
                return null;
            }

            @Override
            public BrainRoundContinuer getObject(Object... args) {
                return null;
            }

            @Override
            public BrainRoundContinuer getIfAvailable() {
                return null;
            }

            @Override
            public BrainRoundContinuer getIfUnique() {
                return null;
            }

            @Override
            public void ifAvailable(Consumer<BrainRoundContinuer> consumer) {
            }
        };
    }
}
