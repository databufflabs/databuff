package com.databuff.apm.web.ai.agent;

import com.databuff.apm.web.ai.platform.task.BrainContinuationService;
import com.databuff.apm.web.ai.platform.task.ExpertMessageConstants;
import com.databuff.apm.web.ai.platform.task.ExpertTask;
import com.databuff.apm.web.ai.platform.task.ExpertTaskPendingRegistry;
import com.databuff.apm.web.ai.platform.task.ExpertTaskService;
import com.databuff.apm.web.ai.platform.task.ExpertTaskStatus;
import com.databuff.apm.web.ai.platform.task.ExpertTaskTextGuard;
import com.databuff.apm.web.ai.platform.expert.ExpertManagementService;
import com.databuff.apm.web.ai.platform.runtime.ExpertRuntimeRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.databuff.apm.web.persistence.ExpertTaskPersistence;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AiChatOrchestratorRoundFinishTest {

    private AiSessionStore store;
    private ExpertTaskService taskService;
    private ExpertTaskPendingRegistry pendingRegistry;

    @BeforeEach
    void setUp() {
        store = new AiSessionStore();
        pendingRegistry = new ExpertTaskPendingRegistry();
        taskService = new ExpertTaskService(
                Mockito.mock(ExpertManagementService.class),
                emptyRegistryProvider(),
                emptyPersistenceProvider(),
                store,
                pendingRegistry,
                new ExpertTaskTextGuard(),
                Mockito.mock(BrainContinuationService.class));
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (taskService != null) {
            taskService.shutdownForTests();
        }
    }

    @Test
    void brainPrematureTextDemotedWhenTaskStillOutstanding() {
        String sessionId = store.ensureSession(null, "brain", "rk", "web-1", "admin");
        store.appendUserMessage(sessionId, "hello", "brain", "admin", Map.of());
        String assistantId = store.reserveAssistantMessageId(sessionId, "brain");
        ExpertTask task = registerTask(sessionId, 1, "task-1");
        store.appendOrUpdateAssistantText(
                sessionId,
                assistantId,
                "brain",
                "已派发，请等待",
                AiMessageStatus.COMPLETED,
                Map.of());

        RoundFinishHelper.finish(store, taskService, sessionId, "brain", assistantId,
                "", Map.of());

        assertThat(store.messages(sessionId))
                .anySatisfy(message -> {
                    assertThat(message.messageType()).isEqualTo("REASONING");
                    assertThat(message.expertId()).isEqualTo("brain");
                    assertThat(message.content()).contains("请等待");
                })
                .noneMatch(message -> Boolean.TRUE.equals(message.metadata().get(ExpertMessageConstants.META_IS_ROUND_FINAL)));

        store.appendExpertDeliverableText(
                sessionId,
                task.targetExpertId(),
                1,
                task.taskId(),
                "data result",
                Map.of());
        pendingRegistry.removePending(sessionId, task.taskId());

        RoundFinishHelper.finish(store, taskService, sessionId, "brain", assistantId,
                "最终答复", Map.of(ExpertMessageConstants.META_TRIGGER_SOURCE,
                        ExpertMessageConstants.TRIGGER_EXPERT_RESULT));

        assertThat(store.messages(sessionId))
                .anyMatch(message -> "TEXT".equals(message.messageType())
                        && "brain".equals(message.expertId())
                        && Boolean.TRUE.equals(message.metadata().get(ExpertMessageConstants.META_IS_ROUND_FINAL))
                        && "最终答复".equals(message.content()));
    }

    @Test
    void brainDefersUntilTaskCompletionNotifiesBrain() {
        String sessionId = store.ensureSession(null, "brain", "rk", "web-1", "admin");
        store.appendUserMessage(sessionId, "hello", "brain", "admin", Map.of());
        String assistantId = store.reserveAssistantMessageId(sessionId, "brain");
        ExpertTask task = registerTask(sessionId, 1, "task-1");

        RoundFinishHelper.finish(store, taskService, sessionId, "brain", assistantId,
                "已派发，请等待", Map.of());

        assertThat(store.messages(sessionId))
                .noneMatch(message -> "TEXT".equals(message.messageType())
                        && Boolean.TRUE.equals(message.metadata().get(ExpertMessageConstants.META_IS_ROUND_FINAL)));
        assertThat(taskService.awaitingBrainTaskCompletionNotifications(sessionId, 1)).isTrue();

        pendingRegistry.removePending(sessionId, task.taskId());

        RoundFinishHelper.finish(store, taskService, sessionId, "brain", assistantId,
                "最终答复", Map.of(ExpertMessageConstants.META_TRIGGER_SOURCE,
                        ExpertMessageConstants.TRIGGER_EXPERT_RESULT));

        assertThat(store.messages(sessionId))
                .anyMatch(message -> "TEXT".equals(message.messageType())
                        && "brain".equals(message.expertId())
                        && Boolean.TRUE.equals(message.metadata().get(ExpertMessageConstants.META_IS_ROUND_FINAL))
                        && "最终答复".equals(message.content()));
    }

    @Test
    void brainDefersUntilExpertDeliverableAppearsInSession() {
        String sessionId = store.ensureSession(null, "brain", "rk", "web-1", "admin");
        store.appendUserMessage(sessionId, "hello", "brain", "admin", Map.of());
        String assistantId = store.reserveAssistantMessageId(sessionId, "brain");
        ExpertTask task = registerTask(sessionId, 1, "task-1");

        RoundFinishHelper.finish(store, taskService, sessionId, "brain", assistantId,
                "已派发，请等待", Map.of());

        assertThat(store.messages(sessionId))
                .noneMatch(message -> "TEXT".equals(message.messageType())
                        && Boolean.TRUE.equals(message.metadata().get(ExpertMessageConstants.META_IS_ROUND_FINAL)));
        assertThat(store.messages(sessionId))
                .anyMatch(message -> "REASONING".equals(message.messageType())
                        && "brain".equals(message.expertId()));

        store.appendExpertDeliverableText(
                sessionId,
                task.targetExpertId(),
                1,
                task.taskId(),
                "data result",
                Map.of());
        pendingRegistry.removePending(sessionId, task.taskId());

        RoundFinishHelper.finish(store, taskService, sessionId, "brain", assistantId,
                "最终答复", Map.of(ExpertMessageConstants.META_TRIGGER_SOURCE,
                        ExpertMessageConstants.TRIGGER_EXPERT_RESULT));

        assertThat(store.messages(sessionId))
                .anyMatch(message -> "TEXT".equals(message.messageType())
                        && "brain".equals(message.expertId())
                        && Boolean.TRUE.equals(message.metadata().get(ExpertMessageConstants.META_IS_ROUND_FINAL))
                        && "最终答复".equals(message.content()));
    }

    @Test
    void brainFinalizesWhenPendingAlreadyClearedEvenIfTaskRecordRemains() {
        // After pending callback is cleared, Subtasks are done — historical task records must not
        // keep the round open or demote brain text (old hasBrainSubtasksInRound behavior).
        String sessionId = store.ensureSession(null, "brain", "rk", "web-1", "admin");
        store.appendUserMessage(sessionId, "hello", "brain", "admin", Map.of());
        String assistantId = store.reserveAssistantMessageId(sessionId, "brain");
        ExpertTask task = registerTask(sessionId, 1, "task-1");
        pendingRegistry.removePending(sessionId, task.taskId());

        RoundFinishHelper.finish(store, taskService, sessionId, "brain", assistantId,
                "最终答复", Map.of(ExpertMessageConstants.META_TRIGGER_SOURCE,
                        ExpertMessageConstants.TRIGGER_EXPERT_RESULT));

        assertThat(store.messages(sessionId))
                .anyMatch(message -> "TEXT".equals(message.messageType())
                        && "brain".equals(message.expertId())
                        && Boolean.TRUE.equals(message.metadata().get(ExpertMessageConstants.META_IS_ROUND_FINAL))
                        && "最终答复".equals(message.content()));
        assertThat(taskService.awaitingBrainTaskCompletionNotifications(sessionId, 1)).isFalse();
    }

    @Test
    void brainWithoutOutstandingTasksWritesRoundFinalText() {
        String sessionId = store.ensureSession(null, "brain", "rk", "web-1", "admin");
        store.appendUserMessage(sessionId, "hello", "brain", "admin", Map.of());
        String assistantId = store.reserveAssistantMessageId(sessionId, "brain");

        RoundFinishHelper.finish(store, taskService, sessionId, "brain", assistantId,
                "最终答复", Map.of());

        assertThat(store.messages(sessionId))
                .anyMatch(message -> "TEXT".equals(message.messageType())
                        && "brain".equals(message.expertId())
                        && Boolean.TRUE.equals(message.metadata().get(ExpertMessageConstants.META_IS_ROUND_FINAL))
                        && "最终答复".equals(message.content()));
    }

    @Test
    void brainContinuationDefersWhileAnotherExpertResponseIsStillMissing() {
        String sessionId = store.ensureSession(null, "brain", "rk", "web-1", "admin");
        store.appendUserMessage(sessionId, "hello", "brain", "admin", Map.of());
        String assistantId = store.reserveAssistantMessageId(sessionId, "brain");
        ExpertTask first = registerTask(sessionId, 1, "task-a");
        ExpertTask second = registerTask(sessionId, 1, "task-b");

        store.appendExpertDeliverableText(
                sessionId,
                first.targetExpertId(),
                1,
                first.taskId(),
                "first result",
                Map.of());
        pendingRegistry.removePending(sessionId, first.taskId());

        RoundFinishHelper.finish(store, taskService, sessionId, "brain", assistantId,
                "已收到一个专家结果", Map.of(
                        ExpertMessageConstants.META_TRIGGER_SOURCE, ExpertMessageConstants.TRIGGER_EXPERT_RESULT));

        assertThat(store.messages(sessionId))
                .noneMatch(message -> Boolean.TRUE.equals(message.metadata().get(ExpertMessageConstants.META_IS_ROUND_FINAL)));
        assertThat(taskService.awaitingBrainTaskCompletionNotifications(sessionId, 1)).isTrue();

        pendingRegistry.removePending(sessionId, second.taskId());

        assertThat(taskService.awaitingBrainTaskCompletionNotifications(sessionId, 1)).isFalse();
    }

    private ExpertTask registerTask(String sessionId, int roundIndex, String taskId) {
        ExpertTask task = new ExpertTask(
                taskId,
                null,
                sessionId,
                "brain",
                "data",
                ExpertTaskStatus.RUNNING,
                "input",
                null,
                null,
                Map.of(ExpertMessageConstants.META_ROUND_INDEX, roundIndex),
                Instant.now(),
                Instant.now(),
                null);
        taskService.registerTaskForTests(task);
        pendingRegistry.addPending(sessionId, task.taskId());
        return task;
    }

    /**
     * Mirrors {@link AiChatOrchestrator} finishAssistantReply.
     */
    static final class RoundFinishHelper {
        private RoundFinishHelper() {
        }

        static void finish(
                AiSessionStore store,
                ExpertTaskService taskService,
                String sessionId,
                String expertId,
                String assistantMessageId,
                String reply,
                Map<String, Object> metadata) {
            String normalizedReply = reply == null ? "" : reply.trim();

            if ("brain".equals(expertId)) {
                int roundIndex = resolveRoundIndex(store, sessionId, metadata);
                boolean expertResultContinuation = ExpertMessageConstants.TRIGGER_EXPERT_RESULT
                        .equals(triggerSource(metadata));
                if (brainAnswerMustWaitForSubExperts(taskService, sessionId, roundIndex)) {
                    deferBrain(store, sessionId, expertId, roundIndex, normalizedReply, metadata);
                    return;
                }
                if (!expertResultContinuation && hasBrainRoundFinalText(store, sessionId, roundIndex)) {
                    return;
                }
                if (normalizedReply.isEmpty()) {
                    if (brainRoundStillInProgress(store, taskService, sessionId, roundIndex)) {
                        return;
                    }
                    store.completeRound(sessionId, expertId);
                    return;
                }
                store.finalizeBrainRoundText(sessionId, assistantMessageId, expertId, normalizedReply, metadata);
            }
        }

        private static void deferBrain(
                AiSessionStore store,
                String sessionId,
                String expertId,
                int roundIndex,
                String normalizedReply,
                Map<String, Object> metadata) {
            store.demoteBrainNonFinalTextToReasoning(sessionId, expertId, roundIndex);
            if (!normalizedReply.isEmpty()) {
                store.appendBrainIntermediateText(sessionId, expertId, normalizedReply, metadata);
            }
        }

        private static boolean hasBrainRoundFinalText(
                AiSessionStore store,
                String sessionId,
                int roundIndex) {
            return store.messages(sessionId).stream()
                    .anyMatch(message -> "TEXT".equals(message.messageType())
                            && "brain".equals(message.expertId())
                            && message.roundIndex() == roundIndex
                            && Boolean.TRUE.equals(message.metadata().get(ExpertMessageConstants.META_IS_ROUND_FINAL)));
        }

        private static String triggerSource(Map<String, Object> metadata) {
            if (metadata == null) {
                return null;
            }
            Object value = metadata.get(ExpertMessageConstants.META_TRIGGER_SOURCE);
            return value == null ? null : String.valueOf(value);
        }

        private static boolean brainAnswerMustWaitForSubExperts(
                ExpertTaskService taskService,
                String sessionId,
                int roundIndex) {
            return taskService.awaitingBrainTaskCompletionNotifications(sessionId, roundIndex);
        }

        private static boolean brainRoundStillInProgress(
                AiSessionStore store,
                ExpertTaskService taskService,
                String sessionId,
                int roundIndex) {
            if (store.messages(sessionId).stream()
                    .anyMatch(message -> "TEXT".equals(message.messageType())
                            && "brain".equals(message.expertId())
                            && message.roundIndex() == roundIndex
                            && Boolean.TRUE.equals(message.metadata().get(ExpertMessageConstants.META_IS_ROUND_FINAL)))) {
                return false;
            }
            return brainAnswerMustWaitForSubExperts(taskService, sessionId, roundIndex);
        }

        private static int resolveRoundIndex(
                AiSessionStore store,
                String sessionId,
                Map<String, Object> metadata) {
            if (metadata != null) {
                Object value = metadata.get(ExpertMessageConstants.META_ROUND_INDEX);
                if (value instanceof Number number && number.intValue() > 0) {
                    return number.intValue();
                }
            }
            return store.peekCurrentRoundIndex(sessionId);
        }
    }

    private static ObjectProvider<ExpertRuntimeRegistry> emptyRegistryProvider() {
        return new ObjectProvider<>() {
            @Override
            public ExpertRuntimeRegistry getObject() {
                return null;
            }

            @Override
            public ExpertRuntimeRegistry getObject(Object... args) {
                return null;
            }

            @Override
            public ExpertRuntimeRegistry getIfAvailable() {
                return null;
            }

            @Override
            public ExpertRuntimeRegistry getIfUnique() {
                return null;
            }

            @Override
            public void ifAvailable(java.util.function.Consumer<ExpertRuntimeRegistry> consumer) {
            }
        };
    }

    private static ObjectProvider<ExpertTaskPersistence> emptyPersistenceProvider() {
        return new ObjectProvider<>() {
            @Override
            public ExpertTaskPersistence getObject() {
                return null;
            }

            @Override
            public ExpertTaskPersistence getObject(Object... args) {
                return null;
            }

            @Override
            public ExpertTaskPersistence getIfAvailable() {
                return null;
            }

            @Override
            public ExpertTaskPersistence getIfUnique() {
                return null;
            }

            @Override
            public void ifAvailable(java.util.function.Consumer<ExpertTaskPersistence> consumer) {
            }
        };
    }
}
