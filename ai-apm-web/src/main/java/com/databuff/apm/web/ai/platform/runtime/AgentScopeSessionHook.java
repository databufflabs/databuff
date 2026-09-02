package com.databuff.apm.web.ai.platform.runtime;

import com.databuff.apm.web.ai.agent.AiMessageStatus;
import com.databuff.apm.web.ai.agent.AiMessageType;
import com.databuff.apm.web.ai.agent.AiSessionStore;
import com.databuff.apm.web.ai.platform.task.ExpertMessageConstants;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class AgentScopeSessionHook {

    private final AiSessionStore sessionStore;
    private final ConcurrentMap<String, CapturedToolResult> capturedToolResults = new ConcurrentHashMap<>();

    public AgentScopeSessionHook(AiSessionStore sessionStore) {
        this.sessionStore = sessionStore;
    }

    public TraceRecorder newTraceRecorder(ExpertChatContext.State ctx) {
        return new TraceRecorder(ctx);
    }

    void captureToolResult(String sessionId, ToolUseBlock toolUse, ToolResultBlock resultBlock) {
        captureToolResult(sessionId, toolUse, resultBlock, -1L);
    }

    void captureToolResult(
            String sessionId,
            ToolUseBlock toolUse,
            ToolResultBlock resultBlock,
            long executionDurationMs) {
        if (isBlank(sessionId) || toolUse == null || isBlank(toolUse.getId()) || resultBlock == null) {
            return;
        }
        String resultText = contentBlockText(resultBlock);
        capturedToolResults.put(toolResultKey(sessionId, toolUse.getId()), new CapturedToolResult(
                nullToDefault(toolUse.getName(), "tool"),
                mapToJson(toolUse.getInput()),
                resultText,
                executionDurationMs));
    }

    public final class TraceRecorder {
        private final ExpertChatContext.State ctx;
        private final Map<String, ToolTrace> toolTraces = new LinkedHashMap<>();
        private final StringBuilder pendingTextBeforeTool = new StringBuilder();
        private final StringBuilder pendingThinkingBeforeTool = new StringBuilder();
        private final AtomicBoolean finished = new AtomicBoolean(false);

        private TraceRecorder(ExpertChatContext.State ctx) {
            this.ctx = ctx;
        }

        public Flux<ExpertRuntimeEvent> record(AgentEvent event) {
            if (event == null) {
                return Flux.empty();
            }
            AgentEventType type = event.getType();
            if (type == AgentEventType.THINKING_BLOCK_DELTA && event instanceof ThinkingBlockDeltaEvent thinking) {
                String chunk = thinking.getDelta();
                if (isBlank(chunk)) {
                    return Flux.empty();
                }
                if (ctx.exposeToolEvents()) {
                    pendingThinkingBeforeTool.append(chunk);
                } else {
                    persistStreamingTrace(AiMessageType.REASONING, chunk, Map.of("chunk", true));
                }
                return Flux.just(ExpertRuntimeEvent.reasoning(chunk));
            }
            if (type == AgentEventType.TEXT_BLOCK_DELTA && event instanceof TextBlockDeltaEvent text) {
                String chunk = text.getDelta();
                if (isBlank(chunk)) {
                    return Flux.empty();
                }
                if (ctx.exposeToolEvents()) {
                    pendingTextBeforeTool.append(chunk);
                }
                return Flux.just(ExpertRuntimeEvent.text(chunk));
            }
            if (!ctx.exposeToolEvents()) {
                return Flux.empty();
            }
            if (type == AgentEventType.TOOL_CALL_START && event instanceof ToolCallStartEvent start) {
                return recordToolCallStart(start);
            }
            if (type == AgentEventType.TOOL_CALL_DELTA && event instanceof ToolCallDeltaEvent delta) {
                toolTrace(delta.getToolCallId()).input.append(nullToEmpty(delta.getDelta()));
                return Flux.empty();
            }
            if (type == AgentEventType.TOOL_CALL_END && event instanceof ToolCallEndEvent end) {
                ToolTrace trace = toolTrace(end.getToolCallId());
                if (!isBlank(end.getToolCallName())) {
                    trace.toolName = end.getToolCallName();
                }
                trace.callEnded = true;
                trace.callEndedAtMs = System.currentTimeMillis();
                sessionStore.updateToolCallInput(
                        storeSessionId(), trace.toolCallId, ctx.taskId(), trace.input.toString());
                return Flux.empty();
            }
            if (type == AgentEventType.TOOL_RESULT_START && event instanceof ToolResultStartEvent start) {
                ToolTrace trace = toolTrace(start.getToolCallId());
                if (!isBlank(start.getToolCallName())) {
                    trace.toolName = start.getToolCallName();
                }
                return Flux.empty();
            }
            if (type == AgentEventType.TOOL_RESULT_TEXT_DELTA && event instanceof ToolResultTextDeltaEvent delta) {
                toolTrace(delta.getToolCallId()).result.append(nullToEmpty(delta.getDelta()));
                return Flux.empty();
            }
            if (type == AgentEventType.TOOL_RESULT_DATA_DELTA && event instanceof ToolResultDataDeltaEvent delta) {
                ToolTrace trace = toolTrace(delta.getToolCallId());
                if (!isBlank(delta.getToolCallName())) {
                    trace.toolName = delta.getToolCallName();
                }
                String text = contentBlockText(delta.getData());
                if (!isBlank(text)) {
                    trace.result.append(text);
                }
                return Flux.empty();
            }
            if (type == AgentEventType.TOOL_RESULT_END && event instanceof ToolResultEndEvent end) {
                ToolTrace trace = toolTrace(end.getToolCallId());
                if (!isBlank(end.getToolCallName())) {
                    trace.toolName = end.getToolCallName();
                }
                return recordToolResultEnd(end);
            }
            return Flux.empty();
        }

        /** Flush buffered assistant text and finalize streaming rows at end of stream. */
        public void finish() {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            String storeSessionId = storeSessionId();
            sessionStore.endReasoningSegment(storeSessionId, ctx.expertId());
            finishTerminalBuffers();
            sessionStore.finalizeRoundStreaming(storeSessionId, ctx.expertId());
        }

        private void finishTerminalBuffers() {
            String terminalText = pendingTextBeforeTool.toString().trim();
            String terminalThinking = pendingThinkingBeforeTool.toString().trim();
            pendingTextBeforeTool.setLength(0);
            pendingThinkingBeforeTool.setLength(0);

            // Terminal TEXT is the reply candidate. The orchestrator owns its final
            // REASONING/TEXT classification because only it knows whether subtasks remain.
            // Preserve a distinct native thinking block as trace information.
            if (!isBlank(terminalThinking) && !terminalThinking.equals(terminalText)) {
                persistCompletedTrace(
                        AiMessageType.REASONING,
                        terminalThinking,
                        Map.of("source", "thinking"));
            }
        }

        private Flux<ExpertRuntimeEvent> recordToolCallStart(ToolCallStartEvent event) {
            sessionStore.endReasoningSegment(storeSessionId(), ctx.expertId());
            ExpertRuntimeEvent pendingReasoning = flushPendingTextBeforeTool();
            ToolTrace trace = toolTrace(event.getToolCallId());
            trace.toolName = isBlank(event.getToolCallName()) ? "tool" : event.getToolCallName();
            String content = "工具调用开始：" + trace.toolName;
            persistCompletedTrace(AiMessageType.TOOL_CALL, content, Map.of(
                    "toolName", trace.toolName,
                    "toolCallId", trace.toolCallId));
            ExpertRuntimeEvent toolCall = ExpertRuntimeEvent.toolCall(content);
            return pendingReasoning == null ? Flux.just(toolCall) : Flux.just(pendingReasoning, toolCall);
        }

        private ExpertRuntimeEvent flushPendingTextBeforeTool() {
            String content = pendingTextBeforeTool.toString().trim();
            if (isBlank(content)) {
                content = pendingThinkingBeforeTool.toString().trim();
            }
            pendingTextBeforeTool.setLength(0);
            pendingThinkingBeforeTool.setLength(0);
            if (isBlank(content)) {
                return null;
            }
            persistCompletedTrace(AiMessageType.REASONING, content, Map.of("source", "pre_tool_text"));
            return ExpertRuntimeEvent.reasoning(content);
        }

        private Flux<ExpertRuntimeEvent> recordToolResultEnd(ToolResultEndEvent event) {
            ToolTrace trace = toolTrace(event.getToolCallId());
            CapturedToolResult captured = capturedToolResults.remove(toolResultKey(ctx.sessionId(), trace.toolCallId));
            if (captured != null) {
                if (isBlank(trace.toolName) || "tool".equals(trace.toolName)) {
                    trace.toolName = captured.toolName();
                }
                if (trace.input.isEmpty() && !isBlank(captured.toolInput())) {
                    trace.input.append(captured.toolInput());
                }
                if (trace.result.isEmpty() && !isBlank(captured.toolResult())) {
                    trace.result.append(captured.toolResult());
                }
            }
            String content = "工具调用结果：" + trace.toolName;
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("toolName", trace.toolName);
            metadata.put("toolCallId", trace.toolCallId);
            metadata.put("toolInput", trace.input.toString());
            metadata.put("toolResult", trace.result.toString());
            if (event.getState() != null) {
                metadata.put("toolResultState", String.valueOf(event.getState()));
            }
            metadata.put("durationMs", resolveToolExecutionDurationMs(trace, captured));
            AiMessageStatus status = event.getState() == null || event.getState() == ToolResultState.SUCCESS
                    ? AiMessageStatus.COMPLETED
                    : AiMessageStatus.FAILED;
            persistTrace(AiMessageType.TOOL_RESULT, content, metadata, status);
            return Flux.just(ExpertRuntimeEvent.toolResult(content));
        }

        private ToolTrace toolTrace(String toolCallId) {
            String key = isBlank(toolCallId) ? "tool" : toolCallId;
            return toolTraces.computeIfAbsent(key, ToolTrace::new);
        }

        private long resolveToolExecutionDurationMs(ToolTrace trace, CapturedToolResult captured) {
            if (captured != null && captured.durationMs() >= 0L) {
                return captured.durationMs();
            }
            if (trace.callEndedAtMs > 0L) {
                return Math.max(0L, System.currentTimeMillis() - trace.callEndedAtMs);
            }
            return 0L;
        }

        private void persistStreamingTrace(
                AiMessageType messageType,
                String content,
                Map<String, Object> extra) {
            persistTrace(messageType, content, extra, AiMessageStatus.STREAMING);
        }

        private void persistCompletedTrace(
                AiMessageType messageType,
                String content,
                Map<String, Object> extra) {
            persistTrace(messageType, content, extra, AiMessageStatus.COMPLETED);
        }

        private void persistTrace(
                AiMessageType messageType,
                String content,
                Map<String, Object> extra,
                AiMessageStatus status) {
            if (ctx.sessionId() == null || ctx.sessionId().isBlank() || isBlank(content)) {
                return;
            }
            Map<String, Object> metadata = baseMetadata(ctx);
            metadata.putAll(extra);
            sessionStore.appendTraceMessage(
                    storeSessionId(),
                    ctx.expertId(),
                    ctx.userName(),
                    messageType,
                    content,
                    status,
                    metadata);
        }

        private String storeSessionId() {
            String logicalSessionId = ExpertChatScopeRegistry.parentSessionId(ctx.sessionId());
            return logicalSessionId == null ? ctx.sessionId() : logicalSessionId;
        }
    }

    private static Map<String, Object> baseMetadata(ExpertChatContext.State ctx) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        String runtimeSessionId = ctx.sessionId();
        String logicalSessionId = ExpertChatScopeRegistry.parentSessionId(runtimeSessionId);
        metadata.put(ExpertMessageConstants.META_SESSION_ID,
                logicalSessionId == null ? runtimeSessionId : logicalSessionId);
        if (logicalSessionId != null && !logicalSessionId.equals(runtimeSessionId)) {
            metadata.put(ExpertMessageConstants.META_RUNTIME_SESSION_ID, runtimeSessionId);
        }
        metadata.put("userName", ctx.userName());
        metadata.put("expertId", ctx.expertId());
        if (ctx.taskId() != null && !ctx.taskId().isBlank()) {
            metadata.put(ExpertMessageConstants.META_TASK_ID, ctx.taskId());
        }
        if (ctx.assistantMessageId() != null && !ctx.assistantMessageId().isBlank()) {
            metadata.put("assistantMessageId", ctx.assistantMessageId());
        }
        return metadata;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String contentBlockText(ContentBlock block) {
        if (block == null) {
            return "";
        }
        if (block instanceof TextBlock textBlock) {
            return nullToEmpty(textBlock.getText());
        }
        if (block instanceof ImageBlock imageBlock) {
            return imageBlockSummary(imageBlock);
        }
        if (block instanceof DataBlock dataBlock) {
            return dataBlockText(dataBlock);
        }
        if (block instanceof ToolResultBlock resultBlock) {
            return contentBlocksText(resultBlock.getOutput());
        }
        return nullToEmpty(block.toString());
    }

    private static String nullToDefault(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value;
    }

    private static String toolResultKey(String sessionId, String toolCallId) {
        return nullToEmpty(sessionId) + ":" + nullToEmpty(toolCallId);
    }

    private static String mapToJson(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception ignored) {
            return String.valueOf(value);
        }
    }

    private static String imageBlockSummary(ImageBlock block) {
        if (block == null || block.getSource() == null) {
            return "[image]";
        }
        Source source = block.getSource();
        if (source instanceof Base64Source base64Source) {
            String mediaType = nullToEmpty(base64Source.getMediaType());
            if (!mediaType.isBlank()) {
                return "[image:" + mediaType + "]";
            }
        }
        return "[image]";
    }

    private static String dataBlockText(DataBlock block) {
        Source source = block.getSource();
        if (source instanceof Base64Source base64Source) {
            String data = nullToEmpty(base64Source.getData());
            if (data.isBlank()) {
                return "";
            }
            String mediaType = nullToEmpty(base64Source.getMediaType()).toLowerCase();
            if (isTextMediaType(mediaType)) {
                return decodeBase64Text(data);
            }
            return "[data:" + base64Source.getMediaType() + ";base64," + data + "]";
        }
        if (source instanceof URLSource urlSource) {
            return nullToEmpty(urlSource.getUrl());
        }
        return nullToEmpty(block.toString());
    }

    private static boolean isTextMediaType(String mediaType) {
        return mediaType.isBlank()
                || mediaType.startsWith("text/")
                || mediaType.contains("json")
                || mediaType.contains("xml")
                || mediaType.contains("csv")
                || mediaType.contains("yaml")
                || mediaType.contains("javascript")
                || mediaType.contains("x-www-form-urlencoded");
    }

    private static String decodeBase64Text(String data) {
        try {
            return new String(Base64.getDecoder().decode(data), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return data;
        }
    }

    private static String contentBlocksText(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : blocks) {
            text.append(contentBlockText(block));
        }
        return text.toString();
    }

    private record CapturedToolResult(String toolName, String toolInput, String toolResult, long durationMs) {
    }

    private static final class ToolTrace {
        private final String toolCallId;
        private String toolName = "tool";
        private boolean callEnded;
        private long callEndedAtMs;
        private final StringBuilder input = new StringBuilder();
        private final StringBuilder result = new StringBuilder();

        private ToolTrace(String toolCallId) {
            this.toolCallId = toolCallId;
        }
    }
}
