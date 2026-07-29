package com.databuff.apm.web.ai.platform.runtime;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Conditionally namespaces AgentScope memory by expert without changing the chat
 * {@code sessionId} seen by {@link io.agentscope.core.agent.RuntimeContext} / workspace tools.
 * <p>
 * Isolation rules:
 * <ul>
 *   <li>{@code brain} always uses {@code sessionId#expert:brain} so orchestrator memory
 *       never leaks into peer experts when the user switches away from brain.</li>
 *   <li>Dispatched subtasks (active {@code taskId} chat scope for this expert) use
 *       {@code sessionId#expert:{expertId}} so parallel/serial brain dispatches do not
 *       share AgentState across experts.</li>
 *   <li>Direct user chat with non-brain experts shares the bare {@code sessionId} slot,
 *       so switching e.g. inspection → data keeps conversation memory.</li>
 * </ul>
 */
final class ExpertIsolatedAgentStateStore implements AgentStateStore {

    static final String EXPERT_MEMORY_SUFFIX = "#expert:";
    static final String BRAIN_EXPERT_ID = "brain";

    private final AgentStateStore delegate;
    private final String expertId;

    ExpertIsolatedAgentStateStore(AgentStateStore delegate, String expertId) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (expertId == null || expertId.isBlank()) {
            throw new IllegalArgumentException("expertId is required");
        }
        this.expertId = expertId.trim();
    }

    static String scopedSessionId(String sessionId, String expertId) {
        if (sessionId == null || sessionId.isBlank()) {
            return sessionId;
        }
        String normalized = sessionId.trim();
        String suffix = EXPERT_MEMORY_SUFFIX + expertId.trim();
        if (normalized.endsWith(suffix) || normalized.contains(EXPERT_MEMORY_SUFFIX)) {
            return normalized;
        }
        return normalized + suffix;
    }

    /**
     * Isolate when this is brain, or when a dispatched subtask for this expert is active.
     * Direct multi-expert chat shares the logical session slot.
     */
    static boolean shouldIsolate(String sessionId, String expertId) {
        if (expertId == null || expertId.isBlank()) {
            return false;
        }
        if (BRAIN_EXPERT_ID.equals(expertId.trim())) {
            return true;
        }
        return ExpertChatScopeRegistry.hasActiveTaskForExpert(sessionId, expertId);
    }

    private String scoped(String sessionId) {
        if (shouldIsolate(sessionId, expertId)) {
            return scopedSessionId(sessionId, expertId);
        }
        return sessionId;
    }

    @Override
    public void save(String userId, String sessionId, String key, State state) {
        delegate.save(userId, scoped(sessionId), key, state);
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> states) {
        delegate.save(userId, scoped(sessionId), key, states);
    }

    @Override
    public <T extends State> Optional<T> get(String userId, String sessionId, String key, Class<T> type) {
        return delegate.get(userId, scoped(sessionId), key, type);
    }

    @Override
    public <T extends State> List<T> getList(String userId, String sessionId, String key, Class<T> type) {
        return delegate.getList(userId, scoped(sessionId), key, type);
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        return delegate.exists(userId, scoped(sessionId));
    }

    @Override
    public void delete(String userId, String sessionId) {
        delegate.delete(userId, scoped(sessionId));
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        delegate.delete(userId, scoped(sessionId), key);
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        return delegate.listSessionIds(userId);
    }

    @Override
    public void close() {
        // Shared delegate lifecycle is owned by AgentScopeRuntimeAdapter.
    }
}
