package com.databuff.apm.web.ai.agent;

import com.databuff.apm.web.ai.platform.task.ExpertMessageConstants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

@Component
public class AiSessionStore {

    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> streamingMessageIds = new ConcurrentHashMap<>();
    private BiConsumer<String, List<ChatMessage>> roundFlushListener;
    private BiConsumer<String, ChatMessage> messageAppendListener;

    @Value("${apm.ai.session.ttl-seconds:86400}")
    private long sessionTtlSeconds;

    @Value("${apm.ai.session.max-sessions:500}")
    private int maxSessions;

    /**
     * Periodically evict idle in-memory sessions whose updatedAt is older than the TTL.
     * Running sessions are spared so active chat turns are never dropped mid-flight.
     * Evicted sessions are recoverable from Doris via lazy hydration.
     */
    @Scheduled(fixedDelayString = "${apm.ai.session.cleanup-interval-ms:600000}")
    public void evictIdleSessions() {
        if (sessionTtlSeconds <= 0) {
            return;
        }
        Instant cutoff = Instant.now().minusSeconds(sessionTtlSeconds);
        sessions.values().removeIf(state -> !state.running.get() && state.updatedAt.isBefore(cutoff));
    }

    public void setRoundFlushListener(BiConsumer<String, List<ChatMessage>> roundFlushListener) {
        this.roundFlushListener = roundFlushListener;
    }

    public void setMessageAppendListener(BiConsumer<String, ChatMessage> messageAppendListener) {
        this.messageAppendListener = messageAppendListener;
    }

    /** @deprecated use {@link #setRoundFlushListener(BiConsumer)} */
    @Deprecated
    public void setMessagePersistListener(BiConsumer<String, ChatMessage> messagePersistListener) {
        if (messagePersistListener == null) {
            this.roundFlushListener = null;
            return;
        }
        this.roundFlushListener = (sessionId, messages) -> {
            for (ChatMessage message : messages) {
                messagePersistListener.accept(sessionId, message);
            }
        };
    }

    public String ensureSession(String sessionId) {
        return ensureSession(sessionId, null, null, null, "admin");
    }

    public String ensureSession(String sessionId, String expertId, String routeKey, String ownerNodeId) {
        return ensureSession(sessionId, expertId, routeKey, ownerNodeId, "admin");
    }

    public String ensureSession(
            String sessionId,
            String expertId,
            String routeKey,
            String ownerNodeId,
            String userName) {
        if (sessionId != null && !sessionId.isBlank() && sessions.containsKey(sessionId)) {
            SessionState existing = sessions.get(sessionId);
            if (expertId != null && !expertId.isBlank()) {
                existing.expertId = expertId;
            }
            if (routeKey != null && !routeKey.isBlank()) {
                existing.routeKey = routeKey;
            }
            if (ownerNodeId != null && !ownerNodeId.isBlank()) {
                existing.ownerNodeId = ownerNodeId;
            }
            if (userName != null && !userName.isBlank()) {
                existing.userName = userName;
            }
            existing.touch();
            return sessionId;
        }
        String id = sessionId == null || sessionId.isBlank() ? UUID.randomUUID().toString() : sessionId;
        SessionState state = new SessionState(id);
        state.expertId = expertId;
        state.routeKey = routeKey;
        state.ownerNodeId = ownerNodeId;
        state.userName = userName == null || userName.isBlank() ? "admin" : userName;
        sessions.put(id, state);
        enforceSessionCap();
        return id;
    }

    public void append(String sessionId, String role, String content) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return;
        }
        if ("user".equalsIgnoreCase(role)) {
            appendUserMessage(sessionId, content);
            return;
        }
        appendAssistantMessage(sessionId, content);
    }

    public void appendUserMessage(String sessionId, String content) {
        appendUserMessage(sessionId, content, null, "admin", Map.of());
    }

    public void appendUserMessage(
            String sessionId,
            String content,
            String expertId,
            String userName,
            Map<String, Object> context) {
        SessionState state = requireSession(sessionId);
        if (expertId != null && !expertId.isBlank()) {
            state.expertId = expertId;
        }
        if (userName != null && !userName.isBlank()) {
            state.userName = userName;
        }
        if (state.title == null || state.title.isBlank()) {
            state.title = summarizeTitle(content);
        }
        flushActiveRoundIfPresent(sessionId, state);
        Map<String, Object> metadata = context == null ? Map.of() : new LinkedHashMap<>(context);
        MessageIndices indices = nextIndices(state, true);
        appendActiveMessage(sessionId, state, new ChatMessage(
                UUID.randomUUID().toString(),
                "user",
                content,
                state.expertId,
                AiMessageType.USER.name(),
                AiMessageStatus.COMPLETED.name(),
                metadata,
                indices.roundIndex(),
                indices.messageIndex(),
                Instant.now(),
                Instant.now()));
    }

    public int peekCurrentRoundIndex(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return 1;
        }
        synchronized (state) {
            return currentRoundIndex(state);
        }
    }

    public String peekUserName(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null || state.userName == null || state.userName.isBlank()) {
            return "admin";
        }
        return state.userName;
    }

    public void appendExpertDeliverableText(
            String sessionId,
            String expertId,
            int roundIndex,
            String taskId,
            String content,
            Map<String, Object> extraMetadata) {
        if (sessionId == null || sessionId.isBlank() || content == null || content.isBlank()) {
            return;
        }
        SessionState state = requireSession(sessionId);
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (extraMetadata != null) {
            metadata.putAll(extraMetadata);
        }
        metadata.put(ExpertMessageConstants.META_IS_EXPERT_DELIVERABLE, true);
        metadata.put(ExpertMessageConstants.META_IS_ROUND_FINAL, false);
        metadata.put(ExpertMessageConstants.META_TASK_ID, taskId);
        metadata.put(ExpertMessageConstants.META_ROUND_INDEX, roundIndex);
        MessageIndices indices = nextIndices(state, false);
        int effectiveRound = roundIndex > 0 ? roundIndex : indices.roundIndex();
        appendActiveMessage(sessionId, state, new ChatMessage(
                UUID.randomUUID().toString(),
                "assistant",
                content,
                expertId,
                AiMessageType.TEXT.name(),
                AiMessageStatus.COMPLETED.name(),
                Map.copyOf(metadata),
                effectiveRound,
                indices.messageIndex(),
                Instant.now(),
                Instant.now()));
    }

    public void appendBrainIntermediateText(
            String sessionId,
            String expertId,
            String content,
            Map<String, Object> extraMetadata) {
        if (sessionId == null || sessionId.isBlank() || content == null || content.isBlank()) {
            return;
        }
        String normalized = content.trim();
        if (hasDuplicateReasoning(sessionId, expertId, normalized)) {
            return;
        }
        Map<String, Object> metadata = extraMetadata == null ? Map.of() : new LinkedHashMap<>(extraMetadata);
        appendTraceMessage(
                sessionId,
                expertId,
                peekUserName(sessionId),
                AiMessageType.REASONING,
                normalized,
                AiMessageStatus.COMPLETED,
                metadata);
    }

    /**
     * While sub-expert tasks are still outstanding, brain TEXT that is not round-final
     * must appear as thinking (REASONING), not as the user-visible answer.
     */
    public void demoteBrainNonFinalTextToReasoning(String sessionId, String expertId, int roundIndex) {
        if (sessionId == null || sessionId.isBlank() || expertId == null || expertId.isBlank() || roundIndex <= 0) {
            return;
        }
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            for (int index = 0; index < state.activeRoundMessages.size(); index++) {
                ChatMessage message = state.activeRoundMessages.get(index);
                if (!expertId.equals(message.expertId()) || message.roundIndex() != roundIndex) {
                    continue;
                }
                if (!AiMessageType.TEXT.name().equals(message.messageType())) {
                    continue;
                }
                if (Boolean.TRUE.equals(message.metadata().get(ExpertMessageConstants.META_IS_EXPERT_DELIVERABLE))) {
                    continue;
                }
                if (Boolean.TRUE.equals(message.metadata().get(ExpertMessageConstants.META_IS_ROUND_FINAL))) {
                    continue;
                }
                String content = message.content();
                if (content == null || content.isBlank()) {
                    continue;
                }
                ChatMessage reasoning = new ChatMessage(
                        message.messageId(),
                        message.role(),
                        content.trim(),
                        message.expertId(),
                        AiMessageType.REASONING.name(),
                        AiMessageStatus.COMPLETED.name(),
                        message.metadata(),
                        message.roundIndex(),
                        message.messageIndex(),
                        message.ts(),
                        Instant.now());
                state.activeRoundMessages.set(index, reasoning);
                state.touch();
            }
        }
        streamingMessageIds.remove(sessionId + ":" + expertId + ":" + AiMessageType.TEXT.name());
    }

    private boolean hasDuplicateReasoning(String sessionId, String expertId, String content) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            for (int index = state.activeRoundMessages.size() - 1; index >= 0; index--) {
                ChatMessage message = state.activeRoundMessages.get(index);
                if (!AiMessageType.REASONING.name().equals(message.messageType())) {
                    continue;
                }
                if (expertId != null && !expertId.equals(message.expertId())) {
                    continue;
                }
                String existing = message.content() == null ? "" : message.content().trim();
                return content.equals(existing);
            }
        }
        return false;
    }

    public void finalizeBrainRoundText(
            String sessionId,
            String messageId,
            String expertId,
            String content,
            Map<String, Object> metadata) {
        Map<String, Object> merged = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
        merged.put(ExpertMessageConstants.META_IS_ROUND_FINAL, true);
        merged.put(ExpertMessageConstants.META_IS_EXPERT_DELIVERABLE, false);
        appendOrUpdateAssistantText(
                sessionId,
                messageId,
                expertId,
                content,
                AiMessageStatus.COMPLETED,
                Map.copyOf(merged));
        completeRound(sessionId, expertId);
    }

    public String reserveAssistantMessageId(String sessionId, String expertId) {
        SessionState state = requireSession(sessionId);
        if (expertId != null && !expertId.isBlank()) {
            state.expertId = expertId;
            state.reservedAssistantExpertId = expertId;
        }
        String messageId = UUID.randomUUID().toString();
        state.reservedAssistantMessageId = messageId;
        return messageId;
    }

    public String peekReservedAssistantMessageId(String sessionId) {
        SessionState state = sessions.get(sessionId);
        return state == null ? null : state.reservedAssistantMessageId;
    }

    /** @deprecated use {@link #reserveAssistantMessageId(String, String)} */
    @Deprecated
    public String appendAssistantPlaceholder(String sessionId, String expertId) {
        return reserveAssistantMessageId(sessionId, expertId);
    }

    public void appendAssistantMessage(String sessionId, String content) {
        appendAssistantMessage(sessionId, content, null, Map.of());
    }

    public void appendAssistantMessage(
            String sessionId,
            String content,
            String expertId,
            Map<String, Object> metadata) {
        SessionState state = requireSession(sessionId);
        String messageId = state.reservedAssistantMessageId == null
                ? UUID.randomUUID().toString()
                : state.reservedAssistantMessageId;
        appendOrUpdateAssistantText(
                sessionId,
                messageId,
                expertId == null ? state.expertId : expertId,
                content,
                AiMessageStatus.COMPLETED,
                metadata == null ? Map.of() : metadata);
    }

    public void appendOrUpdateAssistantText(
            String sessionId,
            String messageId,
            String expertId,
            String content,
            AiMessageStatus status,
            Map<String, Object> metadata) {
        if (sessionId == null || sessionId.isBlank() || messageId == null || messageId.isBlank()) {
            return;
        }
        SessionState state = requireSession(sessionId);
        if (expertId != null && !expertId.isBlank()) {
            state.expertId = expertId;
        }
        ChatMessage existing = findActiveMessage(state, messageId);
        Instant now = Instant.now();
        if (existing != null) {
            if (!AiMessageType.TEXT.name().equals(existing.messageType())) {
                // REASONING / TOOL rows must stay separate from the final answer TEXT row.
                existing = null;
                if (messageId.equals(state.reservedAssistantMessageId)) {
                    messageId = UUID.randomUUID().toString();
                }
            } else {
                if (AiMessageStatus.CANCELLED.name().equals(existing.messageStatus())
                        && status == AiMessageStatus.FAILED) {
                    return;
                }
                replaceActiveMessage(state, existing, new ChatMessage(
                        existing.messageId(),
                        existing.role(),
                        content == null ? existing.content() : content,
                        expertId == null ? existing.expertId() : expertId,
                        AiMessageType.TEXT.name(),
                        status == null ? existing.messageStatus() : status.name(),
                        metadata == null || metadata.isEmpty() ? existing.metadata() : metadata,
                        existing.roundIndex(),
                        existing.messageIndex(),
                        existing.ts(),
                        now));
                return;
            }
        }
        MessageIndices indices = nextIndices(state, false);
        appendActiveMessage(sessionId, state, new ChatMessage(
                messageId,
                "assistant",
                content == null ? "" : content,
                expertId == null ? state.expertId : expertId,
                AiMessageType.TEXT.name(),
                status == null ? AiMessageStatus.COMPLETED.name() : status.name(),
                metadata == null ? Map.of() : metadata,
                indices.roundIndex(),
                indices.messageIndex(),
                now,
                now));
    }

    public void updateMessage(
            String sessionId,
            String messageId,
            String content,
            AiMessageStatus status,
            Map<String, Object> metadata) {
        SessionState state = requireSession(sessionId);
        ChatMessage existing = findActiveMessage(state, messageId);
        if (existing == null) {
            existing = findCommittedMessage(state, messageId);
        }
        if (existing == null) {
            if (messageId.equals(state.reservedAssistantMessageId)) {
                appendOrUpdateAssistantText(
                        sessionId,
                        messageId,
                        state.reservedAssistantExpertId == null ? state.expertId : state.reservedAssistantExpertId,
                        content,
                        status,
                        metadata);
            }
            return;
        }
        if (AiMessageType.TEXT.name().equals(existing.messageType())) {
            appendOrUpdateAssistantText(
                    sessionId,
                    messageId,
                    existing.expertId(),
                    content,
                    status,
                    metadata);
            return;
        }
        ChatMessage updated = new ChatMessage(
                existing.messageId(),
                existing.role(),
                content == null ? existing.content() : content,
                existing.expertId(),
                existing.messageType(),
                status == null ? existing.messageStatus() : status.name(),
                metadata == null || metadata.isEmpty() ? existing.metadata() : metadata,
                existing.roundIndex(),
                existing.messageIndex(),
                existing.ts(),
                Instant.now());
        replaceKnownMessage(state, existing, updated);
    }

    public void updateToolCallInput(String sessionId, String toolCallId, String toolInput) {
        if (sessionId == null || sessionId.isBlank() || toolCallId == null || toolCallId.isBlank()) {
            return;
        }
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return;
        }
        for (ChatMessage message : state.activeRoundMessages) {
            if (!AiMessageType.TOOL_CALL.name().equals(message.messageType())) {
                continue;
            }
            Object existingCallId = message.metadata().get("toolCallId");
            if (existingCallId == null || !toolCallId.equals(String.valueOf(existingCallId))) {
                continue;
            }
            Map<String, Object> metadata = new LinkedHashMap<>(message.metadata());
            metadata.put("toolInput", toolInput == null ? "" : toolInput);
            updateMessage(sessionId, message.messageId(), null, null, metadata);
            return;
        }
    }

    public boolean isAbortRequested(String sessionId) {
        SessionState state = sessions.get(sessionId);
        return state != null && state.abortRequested.get();
    }

    public void clearAbortRequest(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state != null) {
            state.abortRequested.set(false);
        }
    }

    public boolean abortSession(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        synchronized (state) {
            boolean hasActiveWork = state.running.get() || !state.activeRoundMessages.isEmpty();
            if (!hasActiveWork) {
                return false;
            }
            state.abortRequested.set(true);
            state.running.set(false);
            String expertId = state.reservedAssistantExpertId != null && !state.reservedAssistantExpertId.isBlank()
                    ? state.reservedAssistantExpertId
                    : state.expertId;
            if (expertId == null || expertId.isBlank()) {
                expertId = "brain";
            }
            finalizeRoundStreaming(sessionId, expertId);
            String assistantMessageId = state.reservedAssistantMessageId;
            if (assistantMessageId != null) {
                appendOrUpdateAssistantText(
                        sessionId,
                        assistantMessageId,
                        expertId,
                        resolveCancelledAssistantContent(state, assistantMessageId),
                        AiMessageStatus.CANCELLED,
                        Map.of("aborted", true));
            }
            completeRound(sessionId, expertId);
            state.touch();
            return true;
        }
    }

    public void appendTraceMessage(
            String sessionId,
            String expertId,
            String userName,
            AiMessageType messageType,
            String content,
            AiMessageStatus status,
            Map<String, Object> metadata) {
        if (sessionId == null || sessionId.isBlank() || content == null || content.isBlank()) {
            return;
        }
        if (isAbortRequested(sessionId)) {
            return;
        }
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return;
        }
        if (userName != null && !userName.isBlank()) {
            state.userName = userName;
        }
        String normalizedType = messageType == null ? AiMessageType.TEXT.name() : messageType.name();
        boolean streaming = status == AiMessageStatus.STREAMING;
        if (streaming && AiMessageType.TEXT.name().equals(normalizedType)) {
            appendStreamingText(sessionId, expertId, content, metadata, normalizedType, status);
            return;
        }
        if (streaming && AiMessageType.REASONING.name().equals(normalizedType)) {
            appendStreamingReasoning(sessionId, expertId, content, metadata, status);
            return;
        }
        MessageIndices indices = nextIndices(state, false);
        appendActiveMessage(sessionId, state, new ChatMessage(
                UUID.randomUUID().toString(),
                roleFromMessageType(normalizedType),
                content,
                expertId == null ? state.expertId : expertId,
                normalizedType,
                status == null ? AiMessageStatus.COMPLETED.name() : status.name(),
                metadata == null ? Map.of() : metadata,
                indices.roundIndex(),
                indices.messageIndex(),
                Instant.now(),
                Instant.now()));
    }

    private void appendStreamingText(
            String sessionId,
            String expertId,
            String content,
            Map<String, Object> metadata,
            String messageType,
            AiMessageStatus status) {
        SessionState state = requireSession(sessionId);
        String streamKey = sessionId + ":" + expertId + ":" + messageType;
        String existingId = streamingMessageIds.get(streamKey);
        if (existingId != null) {
            ChatMessage existing = findActiveMessage(state, existingId);
            if (existing != null) {
                String merged = mergeStreamingContent(existing.content(), content);
                replaceActiveMessage(state, existing, new ChatMessage(
                        existing.messageId(),
                        existing.role(),
                        merged,
                        expertId == null ? state.expertId : expertId,
                        messageType,
                        status.name(),
                        metadata == null ? existing.metadata() : metadata,
                        existing.roundIndex(),
                        existing.messageIndex(),
                        existing.ts(),
                        Instant.now()));
                return;
            }
        }
        String messageId = AiMessageType.TEXT.name().equals(messageType)
                && state.reservedAssistantMessageId != null
                ? state.reservedAssistantMessageId
                : UUID.randomUUID().toString();
        if (AiMessageType.REASONING.name().equals(messageType)
                && messageId.equals(state.reservedAssistantMessageId)) {
            messageId = UUID.randomUUID().toString();
        }
        MessageIndices indices = nextIndices(state, false);
        ChatMessage message = new ChatMessage(
                messageId,
                "assistant",
                content,
                expertId == null ? state.expertId : expertId,
                messageType,
                status.name(),
                metadata == null ? Map.of() : metadata,
                indices.roundIndex(),
                indices.messageIndex(),
                Instant.now(),
                Instant.now());
        streamingMessageIds.put(streamKey, message.messageId());
        appendActiveMessage(sessionId, state, message);
    }

    private void appendStreamingReasoning(
            String sessionId,
            String expertId,
            String content,
            Map<String, Object> metadata,
            AiMessageStatus status) {
        appendStreamingText(sessionId, expertId, content, metadata, AiMessageType.REASONING.name(), status);
    }

    public void finalizeStreamingText(String sessionId, String expertId, String content) {
        finalizeStreaming(sessionId, expertId, AiMessageType.TEXT.name(), content);
    }

    public void endReasoningSegment(String sessionId, String expertId) {
        finalizeStreaming(sessionId, expertId, AiMessageType.REASONING.name(), null);
    }

    public void finalizeStreaming(String sessionId, String expertId, String messageType, String content) {
        String streamKey = sessionId + ":" + expertId + ":" + messageType;
        String messageId = streamingMessageIds.remove(streamKey);
        if (messageId == null) {
            return;
        }
        updateMessage(sessionId, messageId, content, AiMessageStatus.COMPLETED, null);
    }

    public void finalizeRoundStreaming(String sessionId, String expertId) {
        finalizeStreaming(sessionId, expertId, AiMessageType.REASONING.name(), null);
        finalizeStreaming(sessionId, expertId, AiMessageType.TEXT.name(), null);
    }

    public void completeRound(String sessionId, String expertId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return;
        }
        synchronized (state) {
            finalizeRoundStreaming(sessionId, expertId);
            clearStreamingMessageIds(sessionId);
            if (state.activeRoundMessages.isEmpty()) {
                state.reservedAssistantMessageId = null;
                state.reservedAssistantExpertId = null;
                return;
            }
            List<ChatMessage> roundSnapshot = sortedCopy(state.activeRoundMessages);
            state.committedMessages.addAll(roundSnapshot);
            state.activeRoundMessages.clear();
            state.reservedAssistantMessageId = null;
            state.reservedAssistantExpertId = null;
            state.touch();
            notifyRoundFlushed(sessionId, roundSnapshot);
        }
    }

    public void appendMessage(String sessionId, ChatMessage message) {
        SessionState state = requireSession(sessionId);
        appendActiveMessage(sessionId, state, message);
    }

    public void setRunning(String sessionId, boolean running) {
        SessionState state = requireSession(sessionId);
        state.running.set(running);
        state.touch();
    }

    public boolean isRunning(String sessionId) {
        SessionState state = sessions.get(sessionId);
        return state != null && state.running.get();
    }

    public boolean hasSession(String sessionId) {
        return sessionId != null && sessions.containsKey(sessionId);
    }

    public List<ChatMessage> messages(String sessionId) {
        return listMessages(sessionId);
    }

    public List<ChatMessage> listMessages(String sessionId) {
        SessionState state = sessions.get(sessionId);
        return state == null ? List.of() : mergedMessages(state);
    }

    public List<ChatMessage> committedMessages(String sessionId) {
        SessionState state = sessions.get(sessionId);
        return state == null ? List.of() : List.copyOf(state.committedMessages);
    }

    public List<ChatMessage> activeRoundMessages(String sessionId) {
        SessionState state = sessions.get(sessionId);
        return state == null ? List.of() : sortedCopy(state.activeRoundMessages);
    }

    public List<ChatMessage> messagesAfter(String sessionId, String afterMessageId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return List.of();
        }
        List<ChatMessage> all = mergedMessages(state);
        if (afterMessageId == null || afterMessageId.isBlank()) {
            return all;
        }
        List<ChatMessage> result = new ArrayList<>();
        boolean afterFound = false;
        for (ChatMessage message : all) {
            if (!afterFound) {
                if (afterMessageId.equals(message.messageId())) {
                    afterFound = true;
                }
                continue;
            }
            result.add(message);
        }
        return result;
    }

    public MessagePollResponse pollMessages(String sessionId, String afterMessageId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return new MessagePollResponse(false, List.of());
        }
        return new MessagePollResponse(state.running.get(), messagesAfter(sessionId, afterMessageId));
    }

    public List<SessionSummary> listSessions() {
        return sessions.values().stream()
                .map(state -> new SessionSummary(
                        state.id,
                        state.expertId,
                        state.entryType,
                        state.title,
                        state.routeKey,
                        state.ownerNodeId,
                        state.userName,
                        state.updatedAt,
                        state.committedMessages.size() + state.activeRoundMessages.size()))
                .sorted((left, right) -> right.updatedAt().compareTo(left.updatedAt()))
                .toList();
    }

    public SessionSummary getSession(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return null;
        }
        return new SessionSummary(
                state.id,
                state.expertId,
                state.entryType,
                state.title,
                state.routeKey,
                state.ownerNodeId,
                state.userName,
                state.updatedAt,
                state.committedMessages.size() + state.activeRoundMessages.size());
    }

    public void restore(String sessionId, Instant updatedAt, List<ChatMessage> messages) {
        hydrateSession(sessionId, null, "USER", null, null, null, "admin", messages);
        SessionState state = sessions.get(sessionId);
        if (state != null && updatedAt != null) {
            state.updatedAt = updatedAt;
        }
    }

    public void hydrateSession(
            String sessionId,
            String expertId,
            String entryType,
            String title,
            String routeKey,
            String ownerNodeId,
            String userName,
            List<ChatMessage> messages) {
        SessionState state = new SessionState(sessionId);
        state.expertId = expertId;
        state.entryType = entryType;
        state.title = title;
        state.routeKey = routeKey;
        state.ownerNodeId = ownerNodeId;
        state.userName = userName == null || userName.isBlank() ? "admin" : userName;
        if (messages != null && !messages.isEmpty()) {
            state.committedMessages.addAll(sortedCopy(messages));
            state.nextRoundIndex = messages.stream().mapToInt(ChatMessage::roundIndex).max().orElse(0);
            state.nextMessageIndex = messages.stream().mapToInt(ChatMessage::messageIndex).max().orElse(0);
            state.updatedAt = messages.get(messages.size() - 1).updatedAt();
            if (state.title == null || state.title.isBlank()) {
                state.title = messages.stream()
                        .filter(message -> AiMessageType.USER.name().equals(message.messageType())
                                || "user".equalsIgnoreCase(message.role()))
                        .map(ChatMessage::content)
                        .filter(content -> content != null && !content.isBlank())
                        .findFirst()
                        .map(AiSessionStore::summarizeTitle)
                        .orElse(null);
            }
        }
        sessions.put(sessionId, state);
        enforceSessionCap();
    }

    public void updateSessionMeta(String sessionId, String title, String routeKey) {
        SessionState state = requireSession(sessionId);
        if (title != null && !title.isBlank()) {
            state.title = title;
        }
        if (routeKey != null && !routeKey.isBlank()) {
            state.routeKey = routeKey;
        }
        state.touch();
    }

    public void removeSession(String sessionId) {
        sessions.remove(sessionId);
        clearStreamingMessageIds(sessionId);
    }

    /**
     * Keep the in-memory session map bounded. When over the cap, evict the
     * non-running session with the oldest updatedAt (LRU by activity time).
     * Evicted sessions remain in Doris and are re-hydrated on next access.
     */
    private void enforceSessionCap() {
        if (maxSessions <= 0 || sessions.size() <= maxSessions) {
            return;
        }
        SessionState oldest = null;
        for (SessionState state : sessions.values()) {
            if (state.running.get()) {
                continue;
            }
            if (oldest == null || state.updatedAt.isBefore(oldest.updatedAt)) {
                oldest = state;
            }
        }
        if (oldest != null) {
            sessions.remove(oldest.id);
            clearStreamingMessageIds(oldest.id);
        }
    }

    private void clearStreamingMessageIds(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String prefix = sessionId + ":";
        streamingMessageIds.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private static int nextRoundIndex(SessionState state) {
        return ++state.nextRoundIndex;
    }

    private static int currentRoundIndex(SessionState state) {
        return Math.max(state.nextRoundIndex, 1);
    }

    private void appendActiveMessage(String sessionId, SessionState state, ChatMessage message) {
        synchronized (state) {
            state.activeRoundMessages.add(message);
            state.touch();
        }
        if (messageAppendListener != null) {
            messageAppendListener.accept(sessionId, message);
        }
    }

    private record MessageIndices(int roundIndex, int messageIndex) {
    }

    private MessageIndices nextIndices(SessionState state, boolean incrementRound) {
        synchronized (state) {
            int round = incrementRound ? nextRoundIndex(state) : currentRoundIndex(state);
            return new MessageIndices(round, ++state.nextMessageIndex);
        }
    }

    private void flushActiveRoundIfPresent(String sessionId, SessionState state) {
        if (state.activeRoundMessages.isEmpty()) {
            return;
        }
        completeRound(sessionId, state.reservedAssistantExpertId == null ? state.expertId : state.reservedAssistantExpertId);
    }

    private static ChatMessage findActiveMessage(SessionState state, String messageId) {
        for (ChatMessage message : state.activeRoundMessages) {
            if (messageId.equals(message.messageId())) {
                return message;
            }
        }
        return null;
    }

    private static ChatMessage findCommittedMessage(SessionState state, String messageId) {
        for (ChatMessage message : state.committedMessages) {
            if (messageId.equals(message.messageId())) {
                return message;
            }
        }
        return null;
    }

    private static void replaceActiveMessage(SessionState state, ChatMessage existing, ChatMessage updated) {
        for (int i = 0; i < state.activeRoundMessages.size(); i++) {
            if (existing.messageId().equals(state.activeRoundMessages.get(i).messageId())) {
                state.activeRoundMessages.set(i, updated);
                state.touch();
                return;
            }
        }
    }

    private static void replaceKnownMessage(SessionState state, ChatMessage existing, ChatMessage updated) {
        if (replaceInList(state.activeRoundMessages, existing.messageId(), updated)) {
            state.touch();
            return;
        }
        replaceInList(state.committedMessages, existing.messageId(), updated);
        state.touch();
    }

    private static boolean replaceInList(List<ChatMessage> messages, String messageId, ChatMessage updated) {
        for (int i = 0; i < messages.size(); i++) {
            if (messageId.equals(messages.get(i).messageId())) {
                messages.set(i, updated);
                return true;
            }
        }
        return false;
    }

    private static List<ChatMessage> mergedMessages(SessionState state) {
        if (state.activeRoundMessages.isEmpty()) {
            return sortedCopy(state.committedMessages);
        }
        List<ChatMessage> merged = new ArrayList<>(state.committedMessages.size() + state.activeRoundMessages.size());
        merged.addAll(state.committedMessages);
        merged.addAll(state.activeRoundMessages);
        merged.sort(messageOrder());
        return List.copyOf(merged);
    }

    private static List<ChatMessage> sortedCopy(List<ChatMessage> messages) {
        List<ChatMessage> copy = new ArrayList<>(messages);
        copy.sort(messageOrder());
        return copy;
    }

    private static Comparator<ChatMessage> messageOrder() {
        return Comparator
                .comparingInt(ChatMessage::roundIndex)
                .thenComparingInt(ChatMessage::messageIndex)
                .thenComparing(ChatMessage::ts, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ChatMessage::messageId, Comparator.nullsLast(Comparator.naturalOrder()));
    }

    private void notifyRoundFlushed(String sessionId, List<ChatMessage> messages) {
        if (roundFlushListener != null && messages != null && !messages.isEmpty()) {
            roundFlushListener.accept(sessionId, sortedCopy(messages));
        }
    }

    private SessionState requireSession(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        return state;
    }

    public static String roleFromMessageType(String messageType) {
        if (AiMessageType.USER.name().equals(messageType)) {
            return "user";
        }
        return "assistant";
    }

    private static String summarizeTitle(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String trimmed = content.trim().replaceAll("\\s+", " ");
        return trimmed.length() <= 48 ? trimmed : trimmed.substring(0, 48) + "...";
    }

    private static String resolveCancelledAssistantContent(SessionState state, String assistantMessageId) {
        if (assistantMessageId != null) {
            ChatMessage existing = findActiveMessage(state, assistantMessageId);
            if (existing != null && existing.content() != null && !existing.content().isBlank()) {
                return existing.content();
            }
        }
        for (ChatMessage message : state.activeRoundMessages) {
            if (AiMessageType.TEXT.name().equals(message.messageType())
                    && message.content() != null
                    && !message.content().isBlank()) {
                return message.content();
            }
        }
        return "已中止";
    }

    private static String mergeStreamingContent(String existing, String chunk) {
        if (chunk == null || chunk.isBlank()) {
            return existing == null ? "" : existing;
        }
        if (existing == null || existing.isBlank()) {
            return chunk;
        }
        if (chunk.startsWith(existing) || existing.endsWith(chunk)) {
            return chunk.startsWith(existing) ? chunk : existing;
        }
        return existing + chunk;
    }

    public record SessionSummary(
            String sessionId,
            String expertId,
            String entryType,
            String title,
            String routeKey,
            String ownerNodeId,
            String userName,
            Instant updatedAt,
            int messageCount) {
    }

    public record ChatMessage(
            String messageId,
            String role,
            String content,
            String expertId,
            String messageType,
            String messageStatus,
            Map<String, Object> metadata,
            int roundIndex,
            int messageIndex,
            Instant ts,
            Instant updatedAt) {

        public ChatMessage(String role, String content, Instant ts) {
            this(UUID.randomUUID().toString(), role, content, null, AiMessageType.TEXT.name(),
                    AiMessageStatus.COMPLETED.name(), Map.of(), 1, 1, ts, ts);
        }

        public ChatMessage(String role, String content, String expertId, String messageType, Map<String, Object> metadata, Instant ts) {
            this(UUID.randomUUID().toString(), role, content, expertId,
                    messageType == null ? AiMessageType.TEXT.name() : messageType,
                    AiMessageStatus.COMPLETED.name(),
                    metadata,
                    1,
                    1,
                    ts,
                    ts);
        }

        public ChatMessage {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    public record MessagePollResponse(boolean running, List<ChatMessage> messages) {
    }

    private static final class SessionState {
        private final String id;
        private final List<ChatMessage> committedMessages = new CopyOnWriteArrayList<>();
        private final List<ChatMessage> activeRoundMessages = new CopyOnWriteArrayList<>();
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final AtomicBoolean abortRequested = new AtomicBoolean(false);
        private Instant updatedAt = Instant.now();
        private int nextRoundIndex = 0;
        private int nextMessageIndex = 0;
        private String expertId;
        private String entryType;
        private String title;
        private String routeKey;
        private String ownerNodeId;
        private String userName;
        private String reservedAssistantMessageId;
        private String reservedAssistantExpertId;

        private SessionState(String id) {
            this.id = id;
        }

        private void touch() {
            updatedAt = Instant.now();
        }
    }
}
