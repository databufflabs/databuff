package com.databuff.apm.web.ai.platform.runtime;

import com.databuff.apm.web.ai.platform.expert.AiExpertDefinition;
import com.databuff.apm.web.ai.platform.task.ExpertMessageConstants;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AgentScopeExpertRuntime implements ExpertRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeExpertRuntime.class);

    private final AiExpertDefinition expert;
    private final ReActAgent agent;
    private final RuntimeCacheKey cacheKey;
    private final Instant loadedAt;
    private final AgentScopeSessionHook sessionHook;
    private final SessionWorkspaceService sessionWorkspaceService;
    private final List<McpClientWrapper> mcpClients;

    public AgentScopeExpertRuntime(
            AiExpertDefinition expert,
            ReActAgent agent,
            RuntimeCacheKey cacheKey,
            Instant loadedAt,
            AgentScopeSessionHook sessionHook,
            SessionWorkspaceService sessionWorkspaceService,
            List<McpClientWrapper> mcpClients) {
        this.expert = expert;
        this.agent = agent;
        this.cacheKey = cacheKey;
        this.loadedAt = loadedAt;
        this.sessionHook = sessionHook;
        this.sessionWorkspaceService = sessionWorkspaceService;
        this.mcpClients = mcpClients == null ? List.of() : List.copyOf(mcpClients);
    }

    @Override
    public String expertId() {
        return expert.expertId();
    }

    @Override
    public long version() {
        return expert.version();
    }

    @Override
    public Mono<ExpertChatResult> chat(ExpertChatInput input) {
        if (input == null || input.message() == null || input.message().isBlank()) {
            return Mono.just(ExpertChatResult.failed("message is empty"));
        }
        ExpertChatContext.State context = toContext(input);
        return Mono.fromCallable(() -> ExpertChatContext.run(context, () -> {
                    RuntimeContext runtimeContext = buildRuntimeContext(input);
                    Msg response = agent.call(
                                    List.of(AgentScopeToolConfirmSupport.attachAutoConfirmIfNeeded(
                                            agent, buildUserMessage(input))),
                                    runtimeContext)
                            .block();
                    if (response == null
                            || response.getTextContent() == null
                            || response.getTextContent().isBlank()) {
                        return ExpertChatResult.failed("empty AgentScope response");
                    }
                    return ExpertChatResult.ok(response.getTextContent());
                }))
                .onErrorResume(error -> Mono.just(ExpertChatResult.failed(
                        error.getMessage() == null ? "AgentScope chat failed" : error.getMessage())));
    }

    @Override
    public Flux<ExpertRuntimeEvent> stream(ExpertChatInput input) {
        if (input == null || input.message() == null || input.message().isBlank()) {
            return Flux.just(ExpertRuntimeEvent.error("message is empty"));
        }
        ExpertChatContext.State context = toContext(input);
        RuntimeContext runtimeContext = buildRuntimeContext(input);
        Msg userMessage = AgentScopeToolConfirmSupport.attachAutoConfirmIfNeeded(agent, buildUserMessage(input));
        AgentScopeSessionHook.TraceRecorder recorder = sessionHook.newTraceRecorder(context);
        return Flux.defer(() -> {
            ExpertChatScopeRegistry.register(context);
            Flux<AgentEvent> agentEvents = agent.streamEvents(userMessage, runtimeContext);
            return agentEvents
                    .flatMap(recorder::record)
                    .doOnComplete(recorder::finish)
                    .doOnError(error -> recorder.finish())
                    .doFinally(signal -> {
                        recorder.finish();
                        ExpertChatScopeRegistry.unregister(context.sessionId());
                    });
        }).onErrorResume(error -> Flux.just(ExpertRuntimeEvent.error(
                error.getMessage() == null ? "AgentScope stream failed" : error.getMessage())));
    }

    @Override
    public ExpertRuntimeStatus status() {
        return ExpertRuntimeStatus.loaded(
                expert.expertId(),
                expert.version(),
                cacheKey.fingerprint(),
                loadedAt);
    }

    @Override
    public void close() {
        for (McpClientWrapper client : mcpClients) {
            try {
                client.close();
            } catch (Exception ignored) {
                // Best-effort cleanup for remote MCP sessions.
            }
        }
    }

    ReActAgent agent() {
        return agent;
    }

    RuntimeCacheKey cacheKey() {
        return cacheKey;
    }

    private ExpertChatContext.State toContext(ExpertChatInput input) {
        String taskId = stringMetadata(input.context(), ExpertMessageConstants.META_TASK_ID);
        boolean exposeToolEvents = expert.options().exposeToolEvents();
        if (taskId != null && !taskId.isBlank()) {
            exposeToolEvents = true;
        }
        // ChatScope stays task-scoped so unregister never wipes the parent brain scope.
        return new ExpertChatContext.State(
                resolveChatScopeSessionId(input, taskId),
                input.userName(),
                expert.expertId(),
                input.assistantMessageId(),
                exposeToolEvents,
                null,
                taskId);
    }

    private RuntimeContext buildRuntimeContext(ExpertChatInput input) {
        RuntimeContext.Builder builder = RuntimeContext.builder();
        // AgentScope memory key: logical chat session. Do not use task-scoped ids here —
        // same (session, expert) must share memory across serial dispatches and direct chat.
        String memorySessionId = resolveMemorySessionId(input);
        if (memorySessionId != null && !memorySessionId.isBlank()) {
            builder.sessionId(memorySessionId);
        }
        if (input.userName() != null && !input.userName().isBlank()) {
            builder.userId(input.userName());
            builder.put("userName", input.userName());
        }
        if (input.assistantMessageId() != null && !input.assistantMessageId().isBlank()) {
            builder.put("assistantMessageId", input.assistantMessageId());
        }
        builder.putAll(input.mergedContext());
        String chatScopeSessionId = resolveChatScopeSessionId(
                input, stringMetadata(input.context(), ExpertMessageConstants.META_TASK_ID));
        if (chatScopeSessionId != null
                && !chatScopeSessionId.isBlank()
                && ExpertChatScopeRegistry.isTaskScopedSessionId(chatScopeSessionId)) {
            builder.put(ExpertMessageConstants.META_RUNTIME_SESSION_ID, chatScopeSessionId);
        }
        if (memorySessionId != null && !memorySessionId.isBlank()) {
            builder.put(ExpertMessageConstants.META_SESSION_ID, memorySessionId);
        }
        return builder.build();
    }

    private Msg buildUserMessage(ExpertChatInput input) {
        String memorySessionId = resolveMemorySessionId(input);
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (memorySessionId != null && !memorySessionId.isBlank()) {
            metadata.put("sessionId", memorySessionId);
        }
        if (input.userName() != null && !input.userName().isBlank()) {
            metadata.put("userName", input.userName());
        }
        if (input.assistantMessageId() != null && !input.assistantMessageId().isBlank()) {
            metadata.put("assistantMessageId", input.assistantMessageId());
        }
        metadata.putAll(input.context());
        Msg.Builder builder = Msg.builder()
                .role(MsgRole.USER)
                .name(input.userName() == null || input.userName().isBlank() ? "user" : input.userName())
                .metadata(metadata);
        List<ImageBlock> imageBlocks = WorkspaceImageSupport.readImageAttachments(
                sessionWorkspaceService,
                memorySessionId,
                input.context().get("attachments"));
        if (imageBlocks.isEmpty()) {
            Object rawAttachments = input.context().get("attachments");
            int attachmentCount = rawAttachments instanceof List<?> list ? list.size() : 0;
            if (attachmentCount > 0) {
                log.warn("Session {} has {} attachments but no ImageBlocks were built for the LLM message",
                        memorySessionId, attachmentCount);
            }
            return builder.textContent(input.message().trim()).build();
        }
        List<ContentBlock> contentBlocks = WorkspaceImageSupport.buildUserContentBlocks(
                input.message(),
                imageBlocks);
        log.info("Session {} injecting {} image block(s) into user message for vision",
                memorySessionId, imageBlocks.size());
        return builder.content(contentBlocks).build();
    }

    /**
     * Logical chat session id used for AgentScope {@code stateStore} / workspace paths.
     */
    static String resolveMemorySessionId(ExpertChatInput input) {
        if (input == null) {
            return null;
        }
        String fromMeta = stringMetadata(input.context(), ExpertMessageConstants.META_SESSION_ID);
        if (fromMeta != null && !fromMeta.isBlank()) {
            String parent = ExpertChatScopeRegistry.parentSessionId(fromMeta);
            return parent == null ? fromMeta.trim() : parent;
        }
        if (input.sessionId() != null && !input.sessionId().isBlank()) {
            String parent = ExpertChatScopeRegistry.parentSessionId(input.sessionId());
            return parent == null ? input.sessionId().trim() : parent;
        }
        return null;
    }

    /**
     * ChatScope / tool-trace key: {@code sessionId#task:{taskId}} for subtasks, else logical session.
     */
    static String resolveChatScopeSessionId(ExpertChatInput input, String taskId) {
        String memorySessionId = resolveMemorySessionId(input);
        if (taskId != null && !taskId.isBlank() && memorySessionId != null) {
            String scoped = ExpertChatScopeRegistry.taskScopedSessionId(memorySessionId, taskId);
            if (scoped != null && !scoped.isBlank()) {
                return scoped;
            }
        }
        Object runtimeSessionId = input == null || input.context() == null
                ? null
                : input.context().get(ExpertMessageConstants.META_RUNTIME_SESSION_ID);
        if (runtimeSessionId != null && !String.valueOf(runtimeSessionId).isBlank()) {
            return String.valueOf(runtimeSessionId).trim();
        }
        return memorySessionId;
    }

    private static String stringMetadata(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null) {
            return null;
        }
        Object value = metadata.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
