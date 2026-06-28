package com.databuff.apm.web.ai.platform.task;

import com.databuff.apm.web.ai.agent.AiSessionStore;
import com.databuff.apm.web.ai.platform.runtime.ExpertChatContext;
import com.databuff.apm.web.ai.platform.runtime.ExpertChatScopeRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class ExpertDispatchTool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectProvider<ExpertTaskService> expertTaskService;
    private final ObjectProvider<AiSessionStore> sessionStore;

    public ExpertDispatchTool(
            ObjectProvider<ExpertTaskService> expertTaskService,
            ObjectProvider<AiSessionStore> sessionStore) {
        this.expertTaskService = expertTaskService;
        this.sessionStore = sessionStore;
    }

    @Tool(description = "Dispatch a subtask to another digital expert asynchronously")
    public String dispatchExpertTask(
            @ToolParam(name = "targetExpertId", description = "Target expert id from the brain's available digital experts catalog")
            String targetExpertId,
            @ToolParam(name = "task", description = "Task for the target expert; faithfully restate the user's request only—do not expand scope or add metrics/fields the user did not ask for")
            String task,
            @ToolParam(name = "contextJson", required = false, description = "Optional JSON context")
            String contextJson,
            RuntimeContext runtimeContext) {
        String sessionId = resolveDispatchSessionId(contextJson, runtimeContext);
        String sourceExpertId = ExpertChatScopeRegistry.find(sessionId)
                .map(ExpertChatContext.State::expertId)
                .or(() -> ExpertTaskContext.sourceExpertId())
                .orElse("brain");
        ExpertTask created = expertTaskService.getObject().submit(new ExpertTaskRequest(
                sessionId,
                sourceExpertId,
                targetExpertId,
                task,
                null,
                enrichDispatchContext(contextJson, sessionId, sourceExpertId)));
        return ExpertMessageConstants.asyncWaitMessage(created.taskId(), created.targetExpertId());
    }

    private String resolveDispatchSessionId(String contextJson, RuntimeContext runtimeContext) {
        Map<String, Object> context = parseContext(contextJson);
        Object fromContext = context.get(ExpertMessageConstants.META_SESSION_ID);
        String hint = fromContext == null ? null : String.valueOf(fromContext);
        return ExpertSessionResolver.resolveSessionIdOrThrow(hint, runtimeContext);
    }

    private Map<String, Object> enrichDispatchContext(
            String contextJson,
            String sessionId,
            String sourceExpertId) {
        Map<String, Object> context = parseContext(contextJson);
        Map<String, Object> enriched = new LinkedHashMap<>(context);
        enriched.putIfAbsent(ExpertMessageConstants.META_SESSION_ID, sessionId);
        enriched.putIfAbsent(ExpertMessageConstants.META_SOURCE_EXPERT_ID, sourceExpertId);
        AiSessionStore store = sessionStore.getIfAvailable();
        if (store != null && sessionId != null && !sessionId.isBlank()) {
            enriched.putIfAbsent(ExpertMessageConstants.META_ROUND_INDEX, store.peekCurrentRoundIndex(sessionId));
            enriched.putIfAbsent("userName", store.peekUserName(sessionId));
        }
        return enriched;
    }

    private static Map<String, Object> parseContext(String contextJson) {
        if (contextJson == null || contextJson.isBlank()) {
            return Map.of();
        }
        try {
            return OBJECT_MAPPER.readValue(contextJson, MAP_TYPE);
        } catch (Exception e) {
            return Map.of("rawContext", contextJson);
        }
    }
}
