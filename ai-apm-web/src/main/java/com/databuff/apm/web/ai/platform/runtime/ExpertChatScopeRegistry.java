package com.databuff.apm.web.ai.platform.runtime;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Active chat scopes keyed by session id.
 * <p>
 * Parent chat (brain) uses the logical {@code sessionId}. Subtasks must register under
 * {@code sessionId#task:{taskId}} so they never overwrite the parent brain scope.
 */
public final class ExpertChatScopeRegistry {

    public static final String TASK_SESSION_SUFFIX = "#task:";

    private static final ConcurrentMap<String, ExpertChatContext.State> ACTIVE = new ConcurrentHashMap<>();

    private ExpertChatScopeRegistry() {
    }

    public static String taskScopedSessionId(String sessionId, String taskId) {
        String parent = parentSessionId(sessionId);
        if (parent == null || taskId == null || taskId.isBlank()) {
            return parent;
        }
        return parent + TASK_SESSION_SUFFIX + taskId.trim();
    }

    /** Strip {@code #task:…} suffix; returns null if blank/invalid. */
    public static String parentSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String normalized = sessionId.trim();
        int taskSuffix = normalized.indexOf(TASK_SESSION_SUFFIX);
        if (taskSuffix > 0) {
            normalized = normalized.substring(0, taskSuffix).trim();
        }
        return validSessionId(normalized) ? normalized : null;
    }

    public static boolean isTaskScopedSessionId(String sessionId) {
        return sessionId != null && sessionId.contains(TASK_SESSION_SUFFIX);
    }

    public static void register(ExpertChatContext.State state) {
        if (state == null || state.sessionId() == null || state.sessionId().isBlank()) {
            return;
        }
        ExpertChatContext.State toStore = ensureTaskScopedKey(state);
        String key = toStore.sessionId();
        // Never let a task-scoped state overwrite a parent (brain) scope under the logical sessionId.
        if (!isTaskScopedSessionId(key)) {
            ExpertChatContext.State existing = ACTIVE.get(key);
            if (existing != null
                    && (existing.taskId() == null || existing.taskId().isBlank())
                    && toStore.taskId() != null
                    && !toStore.taskId().isBlank()) {
                toStore = new ExpertChatContext.State(
                        taskScopedSessionId(key, toStore.taskId()),
                        toStore.userName(),
                        toStore.expertId(),
                        toStore.assistantMessageId(),
                        toStore.exposeToolEvents(),
                        toStore.streamSink(),
                        toStore.taskId());
                key = toStore.sessionId();
            }
        }
        ACTIVE.put(key, toStore);
    }

    public static void unregister(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            ACTIVE.remove(sessionId.trim());
        }
    }

    public static Optional<ExpertChatContext.State> find(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(ACTIVE.get(sessionId.trim()));
    }

    /** Parent (non-{@code #task:}) chat scope for a logical or task-scoped session id. */
    public static Optional<ExpertChatContext.State> findParent(String sessionId) {
        String parent = parentSessionId(sessionId);
        if (parent == null) {
            return Optional.empty();
        }
        return find(parent).filter(state -> !isTaskScopedSessionId(state.sessionId()));
    }

    /**
     * True when a dispatched subtask for {@code expertId} is active under the logical session.
     * Used to isolate AgentScope memory across experts during brain (or other) dispatches,
     * without relying on ThreadLocal (store access may hop reactor threads).
     */
    public static boolean hasActiveTaskForExpert(String sessionId, String expertId) {
        String parent = parentSessionId(sessionId);
        if (parent == null || expertId == null || expertId.isBlank()) {
            return false;
        }
        String prefix = parent + TASK_SESSION_SUFFIX;
        String wantExpert = expertId.trim();
        for (var entry : ACTIVE.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) {
                continue;
            }
            ExpertChatContext.State state = entry.getValue();
            if (state != null
                    && wantExpert.equals(state.expertId())
                    && state.taskId() != null
                    && !state.taskId().isBlank()) {
                return true;
            }
        }
        return false;
    }

    public static boolean validSessionId(String sessionId) {
        return sessionId != null
                && !sessionId.isBlank()
                && !"anonymous".equalsIgnoreCase(sessionId.trim());
    }

    /** Clears all active scopes; for unit tests only. */
    public static void clearForTests() {
        ACTIVE.clear();
    }

    private static ExpertChatContext.State ensureTaskScopedKey(ExpertChatContext.State state) {
        String sessionId = state.sessionId().trim();
        String taskId = state.taskId();
        if (taskId == null || taskId.isBlank() || isTaskScopedSessionId(sessionId)) {
            if (sessionId.equals(state.sessionId())) {
                return state;
            }
            return new ExpertChatContext.State(
                    sessionId,
                    state.userName(),
                    state.expertId(),
                    state.assistantMessageId(),
                    state.exposeToolEvents(),
                    state.streamSink(),
                    state.taskId());
        }
        String scoped = taskScopedSessionId(sessionId, taskId);
        if (scoped == null || scoped.equals(sessionId)) {
            return state;
        }
        return new ExpertChatContext.State(
                scoped,
                state.userName(),
                state.expertId(),
                state.assistantMessageId(),
                state.exposeToolEvents(),
                state.streamSink(),
                taskId);
    }
}
