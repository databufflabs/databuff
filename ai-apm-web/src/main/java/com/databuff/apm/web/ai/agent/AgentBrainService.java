package com.databuff.apm.web.ai.agent;

import com.databuff.apm.web.ai.platform.task.ExpertTask;
import com.databuff.apm.web.persistence.AiSessionPersistence;
import com.databuff.apm.web.ai.platform.task.ExpertTaskService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Facade for AI chat entry; delegates to {@link AiChatOrchestrator}.
 */
@Service
public class AgentBrainService {

    private final AiSessionStore sessionStore;
    private final AiChatOrchestrator chatOrchestrator;
    private final ExpertTaskService expertTaskService;
    private final AiSessionPersistence aiSessionPersistence;

    public AgentBrainService(
            AiSessionStore sessionStore,
            @Lazy AiChatOrchestrator chatOrchestrator,
            @Lazy ExpertTaskService expertTaskService,
            AiSessionPersistence aiSessionPersistence) {
        this.sessionStore = sessionStore;
        this.chatOrchestrator = chatOrchestrator;
        this.expertTaskService = expertTaskService;
        this.aiSessionPersistence = aiSessionPersistence;
    }

    public ChatSubmitResponse submitChat(ChatRequest request) {
        return chatOrchestrator.submitChat(request);
    }

    public AiSessionStore.MessagePollResponse pollMessages(String sessionId, String afterMessageId) {
        return aiSessionPersistence.pollMergedMessages(sessionId, afterMessageId);
    }

    public ChatResponse chat(ChatRequest request) {
        return chatOrchestrator.chat(request);
    }

    public SseEmitter stream(ChatRequest request) {
        return chatOrchestrator.stream(request);
    }

    public ChatAbortResponse abortChat(String sessionId) {
        return chatOrchestrator.abortChat(sessionId);
    }

    public long countSessions() {
        return aiSessionPersistence.countSessions();
    }

    public Map<String, Object> listSessions(int offset, int limit) {
        long total = aiSessionPersistence.countSessions();
        List<AiSessionStore.SessionSummary> rows = aiSessionPersistence.listSessions(offset, limit);
        return com.databuff.apm.web.config.common.CommonResponse.listPage(
                rows,
                total,
                offset,
                rows.size());
    }

    public List<AiSessionStore.ChatMessage> sessionMessages(String sessionId) {
        return sessionStore.messages(sessionId);
    }

    public java.util.Optional<ExpertTask> expertTask(String taskId) {
        return expertTaskService.get(taskId);
    }

    public List<ExpertTask> sessionExpertTasks(String sessionId) {
        return expertTaskService.listBySession(sessionId);
    }

    public record ChatRequest(
            String sessionId,
            String expertId,
            String message,
            Boolean stream,
            Map<String, Object> context,
            String requestId,
            String modelProviderCode,
            String modelName,
            String userName) {

        public ChatRequest(String sessionId, String message) {
            this(sessionId, null, message, false, Map.of(), null, null, null, "admin");
        }

        public ChatRequest(String sessionId, String expertId, String message, Boolean stream,
                           Map<String, Object> context, String requestId) {
            this(sessionId, expertId, message, stream, context, requestId, null, null, "admin");
        }

        public ChatRequest {
            stream = stream != null && stream;
            context = context == null ? Map.of() : Map.copyOf(context);
        }

        public boolean streamEnabled() {
            return stream != null && stream;
        }

        public String resolvedExpertId() {
            return expertId == null || expertId.isBlank() ? "brain" : expertId.trim();
        }

        public boolean hasModelOverride() {
            return (modelProviderCode != null && !modelProviderCode.isBlank())
                    || (modelName != null && !modelName.isBlank());
        }
    }

    public record ChatResponse(
            String sessionId,
            String expertId,
            String reply,
            boolean llmReady) {

        public ChatResponse(String sessionId, String reply, boolean llmReady) {
            this(sessionId, "brain", reply, llmReady);
        }
    }

    public record ChatSubmitResponse(
            String sessionId,
            String expertId,
            String status,
            String assistantMessageId) {
    }

    public record ChatAbortResponse(String sessionId, boolean aborted) {
    }
}
