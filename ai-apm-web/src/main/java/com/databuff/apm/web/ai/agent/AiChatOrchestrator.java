package com.databuff.apm.web.ai.agent;

import com.databuff.apm.web.ai.platform.task.ExpertTask;
import com.databuff.apm.web.ai.platform.task.ExpertTaskPendingRegistry;
import com.databuff.apm.web.ai.tool.ApmToolkit;
import com.databuff.apm.web.ai.AiConfigService;
import com.databuff.apm.web.ai.InMemoryLlmProviderStore;
import com.databuff.apm.web.ai.OpenAiCompatibleChatClient;
import com.databuff.apm.web.ai.platform.expert.AiExpertDefinition;
import com.databuff.apm.web.ai.platform.expert.ExpertManagementService;
import com.databuff.apm.web.ai.platform.runtime.ExpertChatInput;
import com.databuff.apm.web.ai.platform.runtime.ExpertChatResult;
import com.databuff.apm.web.ai.platform.runtime.ExpertRuntime;
import com.databuff.apm.web.ai.platform.runtime.ExpertRuntimeEvent;
import com.databuff.apm.web.ai.platform.runtime.AgentScopeRuntimeAdapter;
import com.databuff.apm.web.ai.platform.runtime.ExpertRuntimeRegistry;
import com.databuff.apm.web.ai.platform.runtime.SessionExpertRuntimeRegistry;
import com.databuff.apm.web.ai.platform.runtime.SessionWorkspaceService;
import com.databuff.apm.web.ai.platform.task.ExpertTaskContext;
import com.databuff.apm.web.ai.platform.task.ExpertTaskService;
import com.databuff.apm.web.ai.platform.task.BrainRoundContinuer;
import com.databuff.apm.web.ai.platform.task.ExpertMessageConstants;
import com.databuff.apm.web.ai.platform.task.ExpertMessageContext;
import com.databuff.apm.web.ai.platform.task.ExpertTaskCompletionEvent;
import com.databuff.apm.web.ai.platform.task.ExpertTaskTextGuard;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
public class AiChatOrchestrator implements BrainRoundContinuer {

    private static final Logger log = LoggerFactory.getLogger(AiChatOrchestrator.class);

    @Autowired
    private ExpertManagementService expertManagementService;
    @Autowired
    @Lazy
    private ExpertRuntimeRegistry expertRuntimeRegistry;
    @Autowired
    @Lazy
    private SessionExpertRuntimeRegistry sessionExpertRuntimeRegistry;
    @Autowired
    @Lazy
    private AgentScopeRuntimeAdapter runtimeAdapter;
    @Autowired
    private AiSessionStore sessionStore;
    @Autowired
    private AiConfigService aiConfigService;
    @Autowired
    private AgentRuntimeConfig agentRuntimeConfig;
    @Autowired
    private ApmToolkit apmToolkit;
    @Autowired
    private OpenAiCompatibleChatClient chatClient;
    @Autowired
    private InMemoryLlmProviderStore llmProviderStore;
    @Autowired
    private AiRuntimeRouter runtimeRouter;
    @Autowired
    private AiRuntimeForwarder runtimeForwarder;
    @Autowired
    private ExpertTaskService expertTaskService;
    @Autowired
    private ExpertTaskPendingRegistry expertTaskPendingRegistry;
    @Autowired
    private ExpertTaskTextGuard expertTaskTextGuard;
    @Autowired
    private SessionWorkspaceService sessionWorkspaceService;
    @Value("${apm.ai.lookback-minutes:15}")
    private long lookbackMinutes;
    private long lookbackMillis;
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();
    private final ConcurrentMap<String, Future<?>> activeChatTasks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, SseEmitter> pendingBrainStreamEmitters = new ConcurrentHashMap<>();

    @PostConstruct
    void initLookback() {
        lookbackMillis = lookbackMinutes * 60_000L;
    }

    public AgentBrainService.ChatSubmitResponse submitChat(AgentBrainService.ChatRequest request) {
        validateRequest(request);
        String expertId = request.resolvedExpertId();
        AiExpertDefinition expert = resolveExpert(expertId);
        AiRuntimeRouter.RouteDecision route = runtimeRouter.route(
                request, request.sessionId(), expertId, "admin");
        if (!route.localOwner()) {
            AgentBrainService.ChatResponse forwarded = runtimeForwarder.forwardChat(route, request);
            return new AgentBrainService.ChatSubmitResponse(
                    forwarded.sessionId(), forwarded.expertId(), "COMPLETED", null);
        }

        String userName = requireUserName(request);
        String sessionId = sessionStore.ensureSession(
                request.sessionId(), expertId, route.routeKey(), route.ownerNodeId(), userName);
        ChatMessageContext chatContext = prepareChatMessageContext(sessionId, request);
        sessionStore.appendUserMessage(
                sessionId, chatContext.message(), expertId, userName, chatContext.context());
        String assistantMessageId = sessionStore.reserveAssistantMessageId(sessionId, expertId);
        sessionStore.setRunning(sessionId, true);
        AiExpertDefinition runtimeExpert = withRequestModel(expert, request);
        submitAssistantReply(
                sessionId, expertId, assistantMessageId, userName, chatContext, runtimeExpert, request.hasModelOverride());
        return new AgentBrainService.ChatSubmitResponse(sessionId, expertId, "PROCESSING", assistantMessageId);
    }

    public AgentBrainService.ChatAbortResponse abortChat(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw AiPlatformChatException.sessionNotFound(sessionId);
        }
        if (!sessionStore.hasSession(sessionId)) {
            throw AiPlatformChatException.sessionNotFound(sessionId);
        }
        boolean aborted = sessionStore.abortSession(sessionId);
        sessionExpertRuntimeRegistry.release(sessionId);
        expertTaskPendingRegistry.cancelSessionTasks(sessionId);
        expertTaskTextGuard.clearSession(sessionId);
        Future<?> future = activeChatTasks.remove(sessionId);
        if (future != null) {
            future.cancel(true);
        }
        completePendingBrainStream(sessionId, null);
        return new AgentBrainService.ChatAbortResponse(sessionId, aborted);
    }

    public AiSessionStore.MessagePollResponse pollMessages(String sessionId, String afterMessageId) {
        boolean running = sessionStore.isRunning(sessionId);
        List<AiSessionStore.ChatMessage> messages = sessionStore.messagesAfter(sessionId, afterMessageId);
        return new AiSessionStore.MessagePollResponse(running, messages);
    }

    public AgentBrainService.ChatResponse chat(AgentBrainService.ChatRequest request) {
        AgentBrainService.ChatSubmitResponse submitted = submitChat(request);
        if ("COMPLETED".equals(submitted.status())) {
            return new AgentBrainService.ChatResponse(
                    submitted.sessionId(),
                    submitted.expertId(),
                    latestAssistantReply(submitted.sessionId()),
                    aiConfigService.aiReady());
        }
        waitForCompletion(submitted.sessionId(), agentRuntimeConfig.expertRoundTimeoutMillis());
        return new AgentBrainService.ChatResponse(
                submitted.sessionId(),
                submitted.expertId(),
                latestAssistantReply(submitted.sessionId()),
                aiConfigService.aiReady());
    }

    private void waitForCompletion(String sessionId, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (!sessionStore.isRunning(sessionId)) {
                return;
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private String latestAssistantReply(String sessionId) {
        List<AiSessionStore.ChatMessage> messages = sessionStore.messages(sessionId);
        for (int i = messages.size() - 1; i >= 0; i--) {
            AiSessionStore.ChatMessage message = messages.get(i);
            if (!"assistant".equals(message.role())
                    || !AiMessageType.TEXT.name().equals(message.messageType())
                    || message.content() == null) {
                continue;
            }
            if (isExpertDeliverableText(message.metadata())) {
                continue;
            }
            return message.content();
        }
        return "";
    }

    private static boolean isExpertDeliverableText(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return false;
        }
        Object flag = metadata.get(ExpertMessageConstants.META_IS_EXPERT_DELIVERABLE);
        return Boolean.TRUE.equals(flag);
    }

    @Override
    public void continueBrainRound(ExpertTaskCompletionEvent event) {
        if (event == null || event.sessionId() == null || event.sessionId().isBlank()) {
            return;
        }
        if (sessionStore.isAbortRequested(event.sessionId())) {
            expertTaskPendingRegistry.removePending(event.sessionId(), event.taskId());
            return;
        }
        sessionStore.setRunning(event.sessionId(), true);
        String assistantMessageId = sessionStore.peekReservedAssistantMessageId(event.sessionId());
        if (assistantMessageId == null || assistantMessageId.isBlank()) {
            assistantMessageId = sessionStore.reserveAssistantMessageId(event.sessionId(), "brain");
        }
        // Clear pending only after brain continuation is opened (running + reserved assistant slot).
        expertTaskPendingRegistry.removePending(event.sessionId(), event.taskId());
        AiExpertDefinition expert = resolveExpert("brain");
        String continuation = ExpertMessageContext.wrapBrainContinuation(
                event.sessionId(),
                event.roundIndex(),
                event.taskId(),
                event.targetExpertId(),
                event.text(),
                event.failure());
        ChatMessageContext chatContext = new ChatMessageContext(continuation, Map.of(
                ExpertMessageConstants.META_TRIGGER_SOURCE, ExpertMessageConstants.TRIGGER_EXPERT_RESULT,
                ExpertMessageConstants.META_TASK_ID, event.taskId(),
                ExpertMessageConstants.META_ROUND_INDEX, event.roundIndex(),
                ExpertMessageConstants.META_SESSION_ID, event.sessionId()));
        String finalAssistantMessageId = assistantMessageId;
        Future<?> previous = activeChatTasks.remove(event.sessionId());
        if (previous != null) {
            previous.cancel(true);
        }
        Future<?> future = streamExecutor.submit(() -> executeAssistantReply(
                event.sessionId(),
                "brain",
                finalAssistantMessageId,
                event.userName() == null ? sessionStore.peekUserName(event.sessionId()) : event.userName(),
                chatContext,
                expert,
                false));
        activeChatTasks.put(event.sessionId(), future);
    }

    private void submitAssistantReply(
            String sessionId,
            String expertId,
            String assistantMessageId,
            String userName,
            ChatMessageContext chatContext,
            AiExpertDefinition expert,
            boolean transientRuntime) {
        Future<?> previous = activeChatTasks.remove(sessionId);
        if (previous != null) {
            previous.cancel(true);
        }
        Future<?> future = streamExecutor.submit(() -> executeAssistantReply(
                sessionId, expertId, assistantMessageId, userName, chatContext, expert, transientRuntime));
        activeChatTasks.put(sessionId, future);
    }

    private void executeAssistantReply(
            String sessionId,
            String expertId,
            String assistantMessageId,
            String userName,
            ChatMessageContext chatContext,
            AiExpertDefinition expert,
            boolean transientRuntime) {
        try {
            ExpertTaskContext.run(sessionId, expertId, null, () -> {
                if (sessionStore.isAbortRequested(sessionId)) {
                    return null;
                }
                Set<String> outputsBefore = sessionWorkspaceService.snapshotOutputPaths(sessionId);
                String reply;
                if (canUseExpertRuntime()) {
                    reply = replyWithRuntimeEvents(
                            sessionId, expertId, assistantMessageId, userName, chatContext, expert, transientRuntime);
                } else {
                    reply = dispatchReply(sessionId, userName, assistantMessageId, chatContext, expert, transientRuntime);
                }
                if (sessionStore.isAbortRequested(sessionId)) {
                    return null;
                }
                Map<String, Object> metadata = withModelMetadata(
                        buildAssistantMetadata(sessionId, expertId, outputsBefore), chatContext);
                finishAssistantReply(
                        sessionId,
                        expertId,
                        assistantMessageId,
                        reply,
                        metadata);
                return null;
            });
        } catch (Exception e) {
            if (isChatCancellation(e) || sessionStore.isAbortRequested(sessionId)) {
                return;
            }
            Map<String, Object> failedMetadata = Map.of(ExpertMessageConstants.META_IS_ROUND_FINAL, true);
            sessionStore.appendOrUpdateAssistantText(
                    sessionId,
                    assistantMessageId,
                    expertId,
                    "对话失败：" + (e.getMessage() == null ? "unknown" : e.getMessage()),
                    AiMessageStatus.FAILED,
                    failedMetadata);
            sessionStore.completeRound(sessionId, expertId);
            releaseSessionBrainRuntime(sessionId, expertId);
        } finally {
            activeChatTasks.remove(sessionId);
            boolean keepRunning = shouldKeepSessionRunning(sessionId);
            if (!sessionStore.isAbortRequested(sessionId) && !keepRunning) {
                sessionStore.setRunning(sessionId, false);
            }
            sessionStore.clearAbortRequest(sessionId);
        }
    }

    private void finishAssistantReply(
            String sessionId,
            String expertId,
            String assistantMessageId,
            String reply,
            Map<String, Object> metadata) {
        String normalizedReply = reply == null ? "" : reply.trim();

        if ("brain".equals(expertId)) {
            int roundIndex = resolveRoundIndex(sessionId, metadata);
            boolean expertResultContinuation = ExpertMessageConstants.TRIGGER_EXPERT_RESULT
                    .equals(triggerSourceFromMetadata(metadata));
            if (brainAnswerMustWaitForSubExperts(sessionId, roundIndex)) {
                deferBrainDispatchPhaseReply(sessionId, expertId, roundIndex, assistantMessageId,
                        normalizedReply, metadata, "defer-wait-sub");
                return;
            }
            if (!expertResultContinuation && hasBrainRoundFinalText(sessionId, roundIndex)) {
                return;
            }
            if (normalizedReply.isEmpty()) {
                if (brainRoundStillInProgress(sessionId)) {
                    return;
                }
                sessionStore.completeRound(sessionId, expertId);
                releaseSessionBrainRuntime(sessionId, expertId);
                return;
            }
            sessionStore.finalizeBrainRoundText(
                    sessionId, assistantMessageId, expertId, normalizedReply, metadata);
            completePendingBrainStream(sessionId, normalizedReply);
            releaseSessionBrainRuntime(sessionId, expertId);
            return;
        }
        Map<String, Object> finalMetadata = new LinkedHashMap<>(metadata);
        finalMetadata.put(ExpertMessageConstants.META_IS_ROUND_FINAL, true);
        finalMetadata.put(ExpertMessageConstants.META_IS_EXPERT_DELIVERABLE, false);
        sessionStore.appendOrUpdateAssistantText(
                sessionId,
                assistantMessageId,
                expertId,
                normalizedReply,
                AiMessageStatus.COMPLETED,
                Map.copyOf(finalMetadata));
        sessionStore.completeRound(sessionId, expertId);
        releaseSessionBrainRuntime(sessionId, expertId);
    }

    private void deferBrainDispatchPhaseReply(
            String sessionId,
            String expertId,
            int roundIndex,
            String assistantMessageId,
            String normalizedReply,
            Map<String, Object> metadata,
            String action) {
        sessionStore.demoteBrainNonFinalTextToReasoning(sessionId, expertId, roundIndex);
        if (!normalizedReply.isEmpty()) {
            sessionStore.appendBrainIntermediateText(sessionId, expertId, normalizedReply, metadata);
        }
    }

    private String replyWithRuntimeEvents(
            String sessionId,
            String expertId,
            String assistantMessageId,
            String userName,
            ChatMessageContext chatContext) {
        return replyWithRuntimeEvents(sessionId, expertId, assistantMessageId, userName, chatContext,
                resolveExpert(expertId), false);
    }

    private String replyWithRuntimeEvents(
            String sessionId,
            String expertId,
            String assistantMessageId,
            String userName,
            ChatMessageContext chatContext,
            AiExpertDefinition expert,
            boolean transientRuntime) {
        try {
            StringBuilder content = new StringBuilder();
            StringBuilder textBeforeFirstTool = new StringBuilder();
            boolean[] toolActivitySeen = new boolean[] { false };
            ExpertRuntime runtime = expertRuntime(sessionId, expert, transientRuntime);
            ExpertChatInput input = toExpertChatInput(sessionId, userName, assistantMessageId, chatContext);
            try {
                Flux<ExpertRuntimeEvent> events = runtime.stream(input);
                events.doOnNext(event -> {
                        String type = event.type();
                        if ("tool_call".equals(type) || "tool_result".equals(type)) {
                            toolActivitySeen[0] = true;
                            textBeforeFirstTool.setLength(0);
                            return;
                        }
                        if (!"text".equals(type) || event.content() == null) {
                            return;
                        }
                        if (content.isEmpty() && textBeforeFirstTool.isEmpty()) {
                            sessionStore.endReasoningSegment(sessionId, expertId);
                        }
                        if (toolActivitySeen[0]) {
                            content.append(event.content());
                        } else {
                            textBeforeFirstTool.append(event.content());
                        }
                        })
                        .blockLast(agentRuntimeConfig.expertRoundTimeout());
                if (!content.isEmpty()) {
                    return content.toString();
                }
                if ("brain".equals(expertId)
                        && !toolActivitySeen[0]
                        && !textBeforeFirstTool.isEmpty()
                        && !brainRoundStillInProgress(sessionId)) {
                    ExpertChatResult recovery = runtime.chat(input).block(agentRuntimeConfig.expertRoundTimeout());
                    if (recovery != null && recovery.ok() && recovery.content() != null && !recovery.content().isBlank()) {
                        content.append(recovery.content());
                    }
                    if (!content.isEmpty()) {
                        return content.toString();
                    }
                    if (brainRoundStillInProgress(sessionId)) {
                        return textBeforeFirstTool.toString();
                    }
                }
                if ("brain".equals(expertId) && brainRoundStillInProgress(sessionId)) {
                    if (!toolActivitySeen[0] && !textBeforeFirstTool.isEmpty()) {
                        return textBeforeFirstTool.toString();
                    }
                    return "";
                }
                if (!toolActivitySeen[0] && !textBeforeFirstTool.isEmpty()) {
                    return textBeforeFirstTool.toString();
                }
                if ("brain".equals(expertId) && expertTaskService.awaitingBrainTaskCompletionNotifications(
                        sessionId, sessionStore.peekCurrentRoundIndex(sessionId))) {
                    return "";
                }
                ExpertChatResult result = runtime.chat(input).block(agentRuntimeConfig.expertRoundTimeout());
                if (result != null && result.ok()) {
                    return result.content();
                }
                return runtimeFailureReply(
                        expertId,
                        chatContext.message(),
                        result == null ? "empty runtime response" : result.error());
            } finally {
                if (shouldCloseTransientRuntime(expertId, sessionId, transientRuntime)) {
                    runtime.close();
                }
            }
        } catch (RuntimeException e) {
            if (isChatCancellation(e) || sessionStore.isAbortRequested(sessionId)) {
                return "";
            }
            return runtimeFailureReply(expertId, chatContext.message(), e.getMessage());
        }
    }

    public SseEmitter stream(AgentBrainService.ChatRequest request) {
        validateRequest(request);
        String expertId = request.resolvedExpertId();
        resolveExpert(expertId);
        if (!canUseExpertRuntime()) {
            throw AiPlatformChatException.runtimeUnavailable(expertId);
        }

        AiRuntimeRouter.RouteDecision route = runtimeRouter.route(
                request, request.sessionId(), expertId, "admin");
        if (!route.localOwner()) {
            throw new AiPlatformChatException(
                    "forward_required",
                    501,
                    "remote owner streaming is not implemented yet: " + route.ownerNodeId());
        }

        String sessionId = sessionStore.ensureSession(
                request.sessionId(), expertId, route.routeKey(), route.ownerNodeId(), requireUserName(request));
        ChatMessageContext chatContext = prepareChatMessageContext(sessionId, request);
        sessionStore.appendUserMessage(
                sessionId,
                chatContext.message(),
                expertId,
                requireUserName(request),
                chatContext.context());
        String assistantMessageId = sessionStore.reserveAssistantMessageId(sessionId, expertId);
        sessionStore.setRunning(sessionId, true);

        SseEmitter emitter = new SseEmitter(agentRuntimeConfig.expertRoundTimeoutMillis());
        String userName = requireUserName(request);
        Set<String> outputsBefore = sessionWorkspaceService.snapshotOutputPaths(sessionId);
        ExpertChatInput input = new ExpertChatInput(
                chatContext.modelMessage(), sessionId, userName, assistantMessageId, chatContext.context());
        submitStreamReply(sessionId, expertId, assistantMessageId, request, chatContext, input, outputsBefore, emitter);
        return emitter;
    }

    private void submitStreamReply(
            String sessionId,
            String expertId,
            String assistantMessageId,
            AgentBrainService.ChatRequest request,
            ChatMessageContext chatContext,
            ExpertChatInput input,
            Set<String> outputsBefore,
            SseEmitter emitter) {
        Future<?> previous = activeChatTasks.remove(sessionId);
        if (previous != null) {
            previous.cancel(true);
        }
        Future<?> future = streamExecutor.submit(() -> ExpertTaskContext.runVoid(sessionId, expertId, event -> {
            try {
                emitter.send(SseEmitter.event()
                        .name(event.type())
                        .data(event.taskId() + ":" + event.payload()));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, () -> {
            String userName = requireUserName(request);
            StringBuilder content = new StringBuilder();
            try {
                AiExpertDefinition expert = withRequestModel(resolveExpert(expertId), request);
                boolean transientRuntime = request.hasModelOverride();
                ExpertRuntime runtime = expertRuntime(sessionId, expert, transientRuntime);
                Flux<ExpertRuntimeEvent> events = runtime.stream(input);
                events.doOnNext(event -> {
                            if ("text".equals(event.type()) && event.content() != null) {
                                if (content.isEmpty()) {
                                    sessionStore.endReasoningSegment(sessionId, expertId);
                                }
                                content.append(event.content());
                            }
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("message." + event.type())
                                        .data(event.content()));
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .doOnError(error -> {
                            if (shouldCloseTransientRuntime(expertId, sessionId, transientRuntime)) {
                                runtime.close();
                            }
                            if (!isChatCancellation(error) && !sessionStore.isAbortRequested(sessionId)) {
                                sendStreamError(emitter, error);
                                if (!shouldKeepSessionRunning(sessionId)) {
                                    sessionStore.setRunning(sessionId, false);
                                }
                            }
                            activeChatTasks.remove(sessionId);
                            sessionStore.clearAbortRequest(sessionId);
                        })
                        .doOnComplete(() -> {
                            if (shouldCloseTransientRuntime(expertId, sessionId, transientRuntime)) {
                                runtime.close();
                            }
                            completeStream(sessionId, expertId, assistantMessageId, userName, content, outputsBefore, emitter);
                            activeChatTasks.remove(sessionId);
                            if (!sessionStore.isAbortRequested(sessionId) && !shouldKeepSessionRunning(sessionId)) {
                                sessionStore.setRunning(sessionId, false);
                            }
                            sessionStore.clearAbortRequest(sessionId);
                        })
                        .subscribe();
            } catch (Exception e) {
                if (!isChatCancellation(e) && !sessionStore.isAbortRequested(sessionId)) {
                    sendStreamError(emitter, e);
                    if (!shouldKeepSessionRunning(sessionId)) {
                        sessionStore.setRunning(sessionId, false);
                    }
                }
                activeChatTasks.remove(sessionId);
                sessionStore.clearAbortRequest(sessionId);
            }
        }));
        activeChatTasks.put(sessionId, future);
    }

    private void completeStream(
            String sessionId,
            String expertId,
            String assistantMessageId,
            String userName,
            StringBuilder content,
            Set<String> outputsBefore,
            SseEmitter emitter) {
        if (sessionStore.isAbortRequested(sessionId)) {
            return;
        }
        try {
            String reply = content.toString();
            Map<String, Object> metadata = new LinkedHashMap<>(buildAssistantMetadata(
                    sessionId, expertId, outputsBefore));
            metadata.put("stream", true);
            finishAssistantReply(sessionId, expertId, assistantMessageId, reply, metadata);
            if ("brain".equals(expertId) && brainRoundStillInProgress(sessionId)) {
                emitter.send(SseEmitter.event()
                        .name("message.pending")
                        .data("waiting for async expert tasks"));
                pendingBrainStreamEmitters.put(sessionId, emitter);
                return;
            }
            String payload = latestRoundFinalText(sessionId).orElse(reply);
            emitter.send(SseEmitter.event().name("message.completed").data(payload));
            emitter.complete();
        } catch (IOException e) {
            sessionStore.setRunning(sessionId, false);
            emitter.completeWithError(e);
        }
    }

    private java.util.Optional<String> latestRoundFinalText(String sessionId) {
        List<AiSessionStore.ChatMessage> messages = sessionStore.messages(sessionId);
        for (int i = messages.size() - 1; i >= 0; i--) {
            AiSessionStore.ChatMessage message = messages.get(i);
            if (!"assistant".equals(message.role())
                    || !AiMessageType.TEXT.name().equals(message.messageType())) {
                continue;
            }
            if (isExpertDeliverableText(message.metadata())) {
                continue;
            }
            Object roundFinal = message.metadata().get(ExpertMessageConstants.META_IS_ROUND_FINAL);
            if (Boolean.FALSE.equals(roundFinal)) {
                continue;
            }
            return java.util.Optional.ofNullable(message.content());
        }
        return java.util.Optional.empty();
    }

    private void sendStreamError(SseEmitter emitter, Throwable error) {
        try {
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(error.getMessage() == null ? "stream failed" : error.getMessage()));
        } catch (IOException ignored) {
            // ignore secondary failure
        }
        emitter.completeWithError(error);
    }

    Map<String, Object> buildAssistantMetadata(
            String sessionId,
            String expertId,
            Set<String> outputsBefore) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if ("brain".equals(expertId)) {
            List<ExpertTask> tasks =
                    expertTaskService.listBySession(sessionId);
            if (!tasks.isEmpty()) {
                List<Map<String, Object>> subtasks = tasks.stream()
                        .map(task -> Map.<String, Object>of(
                                "taskId", task.taskId(),
                                "targetExpertId", task.targetExpertId(),
                                "status", task.status().name(),
                                "sourceExpertId", task.sourceExpertId()))
                        .toList();
                metadata.put("subtasks", subtasks);
            }
        }
        List<SessionWorkspaceService.WorkspaceFileInfo> generated = sessionWorkspaceService.listNewOutputFiles(sessionId, outputsBefore);
        if (!generated.isEmpty()) {
            metadata.put("generatedFiles", generated.stream()
                    .map(SessionWorkspaceService.WorkspaceFileInfo::toMetadata)
                    .toList());
        }
        return Map.copyOf(metadata);
    }

    Map<String, Object> buildAssistantMetadata(String sessionId, String expertId) {
        return buildAssistantMetadata(sessionId, expertId, Set.of());
    }

    private String appendSubtaskSummary(String sessionId, String reply) {
        List<ExpertTask> tasks = expertTaskService.listBySession(sessionId);
        if (tasks.isEmpty()) {
            return reply;
        }
        StringBuilder builder = new StringBuilder(reply == null ? "" : reply);
        builder.append("\n\n---\nSubtasks:\n");
        for (ExpertTask task : tasks) {
            builder.append("- ")
                    .append(task.targetExpertId())
                    .append(" [")
                    .append(task.status())
                    .append("] taskId=")
                    .append(task.taskId());
            if (task.output() != null && !task.output().isBlank()) {
                builder.append("\n  ").append(task.output());
            } else if (task.error() != null && !task.error().isBlank()) {
                builder.append("\n  error=").append(task.error());
            }
            builder.append('\n');
        }
        return builder.toString().trim();
    }

    private ChatMessageContext prepareChatMessageContext(
            String sessionId,
            AgentBrainService.ChatRequest request) {
        List<SessionWorkspaceService.SavedAttachment> saved = sessionWorkspaceService.saveAttachments(sessionId, request.context());
        Map<String, Object> context = sessionWorkspaceService.buildPersistedContext(request.context(), saved);
        String userVisible = request.message() == null ? "" : request.message();
        String modelMessage = sessionWorkspaceService.enrichMessage(userVisible, saved);
        Map<String, Object> enriched = new LinkedHashMap<>(context);
        putIfNotBlank(enriched, "modelProviderCode", request.modelProviderCode());
        putIfNotBlank(enriched, "modelName", request.modelName());
        return new ChatMessageContext(userVisible, modelMessage, Map.copyOf(enriched));
    }

    private void validateRequest(AgentBrainService.ChatRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw AiPlatformChatException.messageRequired();
        }
        requireUserName(request);
    }

    private String requireUserName(AgentBrainService.ChatRequest request) {
        if (request == null || request.userName() == null || request.userName().isBlank()) {
            throw new AiPlatformChatException("user_name_required", 400, "userName is required");
        }
        return request.userName().trim();
    }

    private ExpertChatInput toExpertChatInput(
            String sessionId,
            String userName,
            String assistantMessageId,
            ChatMessageContext chatContext) {
        return new ExpertChatInput(
                chatContext.modelMessage(),
                sessionId,
                userName,
                assistantMessageId,
                chatContext.context());
    }

    private AiExpertDefinition resolveExpert(String expertId) {
        AiExpertDefinition expert = expertManagementService.find(expertId)
                .orElseThrow(() -> AiPlatformChatException.expertNotFound(expertId));
        if (!expert.enabled()) {
            throw AiPlatformChatException.expertDisabled(expertId);
        }
        return expert;
    }

    private String dispatchReply(
            String sessionId,
            String userName,
            String assistantMessageId,
            ChatMessageContext chatContext,
            AiExpertDefinition expert,
            boolean transientRuntime) {
        if (canUseExpertRuntime()) {
            return replyWithExpertRuntime(
                    sessionId, userName, assistantMessageId, chatContext, expert, transientRuntime);
        }
        if (!"brain".equals(expert.expertId())) {
            return "专家「" + expert.name() + "」需要配置大模型。"
                    + " 请前往「配置管理 → 模型配置」填写 API Key 并启用。";
        }
        return legacyBrainReply(chatContext.message());
    }

    private boolean canUseExpertRuntime() {
        return aiConfigService.aiReady();
    }

    private String replyWithExpertRuntime(
            String sessionId,
            String userName,
            String assistantMessageId,
            ChatMessageContext chatContext,
            AiExpertDefinition expert,
            boolean transientRuntime) {
        String expertId = expert.expertId();
        try {
            ExpertRuntime runtime = expertRuntime(sessionId, expert, transientRuntime);
            try {
                ExpertChatResult result = runtime.chat(
                                toExpertChatInput(sessionId, userName, assistantMessageId, chatContext))
                        .block();
                if (result == null) {
                    return runtimeFailureReply(expertId, chatContext.message(), "empty runtime response");
                }
                if (result.ok()) {
                    return result.content();
                }
                return runtimeFailureReply(expertId, chatContext.message(), result.error());
            } finally {
                if (shouldCloseTransientRuntime(expertId, sessionId, transientRuntime)) {
                    runtime.close();
                }
            }
        } catch (RuntimeException e) {
            if (isChatCancellation(e) || sessionStore.isAbortRequested(sessionId)) {
                return "";
            }
            return runtimeFailureReply(expertId, chatContext.message(), e.getMessage());
        }
    }

    private static boolean isChatCancellation(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof InterruptedException || current instanceof CancellationException) {
                return true;
            }
            current = current.getCause();
        }
        return Thread.currentThread().isInterrupted();
    }

    private String runtimeFailureReply(String expertId, String message, String error) {
        if ("brain".equals(expertId)) {
            return legacyBrainReply(message);
        }
        return "专家运行时调用失败：" + (error == null ? "unknown" : error);
    }

    private String legacyBrainReply(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "错误率", "error rate", "error", "异常")) {
            return formatServiceHealth();
        }
        if (containsAny(lower, "trace", "链路", "span")) {
            int count = apmToolkit.countRecentSpans(lookbackMillis);
            return "最近 " + (lookbackMillis / 60_000L) + " 分钟内共查询到 "
                    + count + " 条 Span。可在「Trace 列表」查看详情。";
        }
        if (containsAny(lower, "帮助", "help", "能做什么")) {
            return helpText();
        }
        if (!aiConfigService.aiReady()) {
            return "AI 大模型尚未配置（请前往「配置管理 → 模型配置」填写 API Key 并启用）。"
                    + " 当前可用工具问答：输入「错误率」或「trace」查询 APM 数据。\n\n"
                    + helpText();
        }
        return llmProviderStore.firstEnabledProvider()
                .map(provider -> replyWithLlm(provider, message))
                .orElseGet(() -> "已收到您的问题。可通过 expertId 指定数字专家对话。"
                        + " 当前可先问：「demo-order 错误率」「最近 trace 有多少」。\n\n"
                        + "（问题摘要：" + truncate(message, 120) + "）");
    }

    private String replyWithLlm(OpenAiCompatibleChatClient.ResolvedLlmProvider provider, String message) {
        OpenAiCompatibleChatClient.ChatResult result = chatClient.chat(provider, message);
        if (result.ok()) {
            return result.content();
        }
        return "LLM 调用失败：" + result.error() + "\n\n" + helpText();
    }

    private String formatServiceHealth() {
        List<ApmToolkit.ServiceHealthSummary> summaries = apmToolkit.listServiceHealth(lookbackMillis);
        if (summaries.isEmpty()) {
            return "暂无服务指标数据。请先向 ingest 发送 OTLP Trace。";
        }
        StringBuilder builder = new StringBuilder("最近服务错误率（Top 5）：\n");
        summaries.stream().limit(5).forEach(item -> builder.append("- ")
                .append(item.service())
                .append(": ")
                .append(String.format(Locale.ROOT, "%.2f%%", item.errorRate() * 100))
                .append(" (")
                .append(item.errorCount())
                .append("/")
                .append(item.totalCount())
                .append(")\n"));
        return builder.toString().trim();
    }

    private static String helpText() {
        return """
                我是 DataBuff APM 助手。
                - 问「错误率 / 异常」：汇总 databuff.service
                - 问「trace / 链路」：统计最近 Span 数量
                - 配置大模型：侧栏「配置管理 → 模型配置」
                - 指定专家：POST /api/v1/ai/chat 传 expertId=data 或 inspection
                """;
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    private record ChatMessageContext(String message, String modelMessage, Map<String, Object> context) {
        private ChatMessageContext(String message, Map<String, Object> context) {
            this(message, message, context);
        }
    }

    private static AiExpertDefinition withRequestModel(AiExpertDefinition expert, AgentBrainService.ChatRequest request) {
        if (expert == null || request == null || !request.hasModelOverride()) {
            return expert;
        }
        return new AiExpertDefinition(
                expert.expertId(), expert.name(), expert.category(), expert.description(), expert.type(),
                trimToNull(request.modelProviderCode()), trimToNull(request.modelName()), expert.systemPrompt(),
                expert.toolIds(), expert.skillIds(), expert.options(), expert.enabled(), expert.builtIn(),
                expert.version(), expert.createdAt(), expert.updatedAt());
    }

    private ExpertRuntime expertRuntime(String sessionId, AiExpertDefinition expert, boolean transientRuntime) {
        if ("brain".equals(expert.expertId()) && sessionId != null && !sessionId.isBlank()) {
            return sessionExpertRuntimeRegistry.getOrCreate(sessionId, expert);
        }
        if (transientRuntime && runtimeAdapter != null) {
            return runtimeAdapter.buildRuntime(expert);
        }
        return expertRuntimeRegistry.getOrCreate(expert.expertId());
    }

    private boolean shouldCloseTransientRuntime(String expertId, String sessionId, boolean transientRuntime) {
        if (!transientRuntime || runtimeAdapter == null) {
            return false;
        }
        return !("brain".equals(expertId) && sessionId != null && !sessionId.isBlank());
    }

    private void releaseSessionBrainRuntime(String sessionId, String expertId) {
        if ("brain".equals(expertId) && sessionId != null && !sessionId.isBlank()) {
            sessionExpertRuntimeRegistry.release(sessionId);
        }
    }

    private static Map<String, Object> withModelMetadata(Map<String, Object> metadata, ChatMessageContext context) {
        Map<String, Object> merged = new LinkedHashMap<>(metadata);
        if (context != null && context.context() != null) {
            putIfNotBlank(merged, "modelProviderCode", stringValue(context.context().get("modelProviderCode")));
            putIfNotBlank(merged, "modelName", stringValue(context.context().get("modelName")));
        }
        return Map.copyOf(merged);
    }

    private boolean shouldKeepSessionRunning(String sessionId) {
        return brainRoundStillInProgress(sessionId);
    }

    private boolean brainRoundStillInProgress(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        int roundIndex = sessionStore.peekCurrentRoundIndex(sessionId);
        if (hasBrainRoundFinalText(sessionId, roundIndex)) {
            return false;
        }
        // Subtasks are in-progress only while pending callback to brain is outstanding (not merely
        // because a historical task record exists for this round).
        return brainAnswerMustWaitForSubExperts(sessionId, roundIndex);
    }

    private boolean brainAnswerMustWaitForSubExperts(String sessionId, int roundIndex) {
        return expertTaskService.awaitingBrainTaskCompletionNotifications(sessionId, roundIndex);
    }

    private void completePendingBrainStream(String sessionId, String payload) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        SseEmitter emitter = pendingBrainStreamEmitters.remove(sessionId);
        if (emitter == null) {
            return;
        }
        try {
            if (payload != null && !payload.isBlank()) {
                emitter.send(SseEmitter.event().name("message.completed").data(payload));
            }
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private int resolveRoundIndex(String sessionId, Map<String, Object> metadata) {
        if (metadata != null) {
            Object value = metadata.get(ExpertMessageConstants.META_ROUND_INDEX);
            if (value instanceof Number number && number.intValue() > 0) {
                return number.intValue();
            }
            if (value != null) {
                try {
                    int parsed = Integer.parseInt(String.valueOf(value));
                    if (parsed > 0) {
                        return parsed;
                    }
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
        }
        return sessionStore.peekCurrentRoundIndex(sessionId);
    }

    private boolean hasBrainRoundFinalText(String sessionId, int roundIndex) {
        return sessionStore.messages(sessionId).stream()
                .anyMatch(message -> "TEXT".equals(message.messageType())
                        && "brain".equals(message.expertId())
                        && message.roundIndex() == roundIndex
                        && Boolean.TRUE.equals(message.metadata().get(ExpertMessageConstants.META_IS_ROUND_FINAL)));
    }

    private static void putIfNotBlank(Map<String, Object> map, String key, String value) {
        String normalized = trimToNull(value);
        if (normalized != null) {
            map.put(key, normalized);
        }
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String triggerSourceFromMetadata(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(ExpertMessageConstants.META_TRIGGER_SOURCE);
        return value == null ? null : String.valueOf(value);
    }

}
