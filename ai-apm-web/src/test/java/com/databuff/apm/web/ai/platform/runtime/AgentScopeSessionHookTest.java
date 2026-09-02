package com.databuff.apm.web.ai.platform.runtime;

import com.databuff.apm.web.ai.agent.AiSessionStore;
import com.databuff.apm.web.ai.platform.task.ExpertMessageConstants;
import com.databuff.apm.web.tools.local.CommonTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.DefaultToolResultConverter;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentScopeSessionHookTest {

    @Test
    void recordsToolResultTextFromToolResultBlockDataDelta() {
        AiSessionStore store = new AiSessionStore();
        String sessionId = store.ensureSession(null, "data", "rk", "web-1", "admin");
        store.appendUserMessage(sessionId, "查拓扑", "data", "admin", Map.of());
        AgentScopeSessionHook hook = new AgentScopeSessionHook(store);
        AgentScopeSessionHook.TraceRecorder recorder = hook.newTraceRecorder(new ExpertChatContext.State(
                sessionId, "admin", "data", null, true, null));

        recorder.record(new ToolCallStartEvent("reply-1", "call-1", "queryServiceTopology")).blockLast();
        recorder.record(new ToolResultDataDeltaEvent(
                "reply-1",
                "call-1",
                "queryServiceTopology",
                ToolResultBlock.text("{\"serviceName\":\"service-a\",\"data\":{\"nodes\":[]}}"))).blockLast();
        recorder.record(new ToolResultEndEvent(
                "reply-1", "call-1", "queryServiceTopology", ToolResultState.SUCCESS)).blockLast();

        AiSessionStore.ChatMessage result = store.activeRoundMessages(sessionId).stream()
                .filter(message -> "TOOL_RESULT".equals(message.messageType()))
                .findFirst()
                .orElseThrow();
        assertThat(result.metadata())
                .containsEntry("toolName", "queryServiceTopology")
                .containsEntry("toolResult", "{\"serviceName\":\"service-a\",\"data\":{\"nodes\":[]}}");
    }

    @Test
    void recordsToolResultTextFromTextBlockDataDelta() {
        AiSessionStore store = new AiSessionStore();
        String sessionId = store.ensureSession(null, "data", "rk", "web-1", "admin");
        store.appendUserMessage(sessionId, "查拓扑", "data", "admin", Map.of());
        AgentScopeSessionHook hook = new AgentScopeSessionHook(store);
        AgentScopeSessionHook.TraceRecorder recorder = hook.newTraceRecorder(new ExpertChatContext.State(
                sessionId, "admin", "data", null, true, null));

        recorder.record(new ToolCallStartEvent("reply-1", "call-1", "queryServiceTopology")).blockLast();
        recorder.record(new ToolResultDataDeltaEvent(
                "reply-1",
                "call-1",
                "queryServiceTopology",
                TextBlock.builder().text("{\"ok\":true}").build())).blockLast();
        recorder.record(new ToolResultEndEvent(
                "reply-1", "call-1", "queryServiceTopology", ToolResultState.SUCCESS)).blockLast();

        AiSessionStore.ChatMessage result = store.activeRoundMessages(sessionId).stream()
                .filter(message -> "TOOL_RESULT".equals(message.messageType()))
                .findFirst()
                .orElseThrow();
        assertThat(result.metadata()).containsEntry("toolResult", "{\"ok\":true}");
    }

    @Test
    void recordsToolResultTextFromJsonDataBlockDelta() {
        AiSessionStore store = new AiSessionStore();
        String sessionId = store.ensureSession(null, "data", "rk", "web-1", "admin");
        store.appendUserMessage(sessionId, "查服务", "data", "admin", Map.of());
        AgentScopeSessionHook hook = new AgentScopeSessionHook(store);
        AgentScopeSessionHook.TraceRecorder recorder = hook.newTraceRecorder(new ExpertChatContext.State(
                sessionId, "admin", "data", null, true, null));
        String payload = "{\"services\":[{\"name\":\"checkout\"}]}";
        DataBlock dataBlock = DataBlock.builder()
                .source(Base64Source.builder()
                        .mediaType("application/json")
                        .data(Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8)))
                        .build())
                .build();

        recorder.record(new ToolCallStartEvent("reply-1", "call-1", "queryServicesAll")).blockLast();
        recorder.record(new ToolResultDataDeltaEvent("reply-1", "call-1", "queryServicesAll", dataBlock)).blockLast();
        recorder.record(new ToolResultEndEvent(
                "reply-1", "call-1", "queryServicesAll", ToolResultState.SUCCESS)).blockLast();

        AiSessionStore.ChatMessage result = store.activeRoundMessages(sessionId).stream()
                .filter(message -> "TOOL_RESULT".equals(message.messageType()))
                .findFirst()
                .orElseThrow();
        assertThat(result.metadata())
                .containsEntry("toolName", "queryServicesAll")
                .containsEntry("toolResult", payload);
    }

    @Test
    void recordsGetCurrentTimeRangeResultCapturedFromToolTracerWhenStreamDeltaIsMissing() {
        AiSessionStore store = new AiSessionStore();
        String sessionId = store.ensureSession(null, "data", "rk", "web-1", "admin");
        store.appendUserMessage(sessionId, "查最近一小时", "data", "admin", Map.of());
        AgentScopeSessionHook hook = new AgentScopeSessionHook(store);
        AgentScopeSessionHook.TraceRecorder recorder = hook.newTraceRecorder(new ExpertChatContext.State(
                sessionId, "admin", "data", "assistant-1", true, null));
        CommonTools commonTools = new CommonTools(new ObjectMapper());
        Map<String, String> resultMap = commonTools.getCurrentTimeRange(60);
        ToolResultBlock resultBlock = new DefaultToolResultConverter().convert(resultMap, Map.class);
        ToolUseBlock toolUse = ToolUseBlock.builder()
                .id("call_function_y0smdx9jwa9j_1")
                .name("getCurrentTimeRange")
                .input(Map.of("rangeMinutes", 60))
                .build();

        hook.captureToolResult(sessionId, toolUse, resultBlock, 42L);
        recorder.record(new ToolCallStartEvent("reply-1", "call_function_y0smdx9jwa9j_1", "getCurrentTimeRange")).blockLast();
        recorder.record(new ToolResultEndEvent(
                "reply-1",
                "call_function_y0smdx9jwa9j_1",
                "getCurrentTimeRange",
                ToolResultState.SUCCESS)).blockLast();

        AiSessionStore.ChatMessage result = store.activeRoundMessages(sessionId).stream()
                .filter(message -> "TOOL_RESULT".equals(message.messageType()))
                .findFirst()
                .orElseThrow();
        assertThat(result.metadata())
                .containsEntry("toolName", "getCurrentTimeRange")
                .containsEntry("toolResultState", "SUCCESS");
        assertThat(result.messageStatus()).isEqualTo("COMPLETED");
        assertThat(result.metadata()).containsEntry("durationMs", 42L);
        assertThat(String.valueOf(result.metadata().get("toolInput"))).contains("\"rangeMinutes\":60");
        assertThat(String.valueOf(result.metadata().get("toolResult")))
                .isNotBlank()
                .contains("fromTime")
                .contains("toTime");
    }

    @Test
    void taskScopedRecorderPersistsToolTraceToParentSessionAndKeepsRuntimeIdentity() {
        AiSessionStore store = new AiSessionStore();
        String sessionId = store.ensureSession(null, "brain", "rk", "web-1", "admin");
        store.appendUserMessage(sessionId, "巡检 service-a", "brain", "admin", Map.of());
        String taskId = "task-inspection-1";
        String runtimeSessionId = ExpertChatScopeRegistry.taskScopedSessionId(sessionId, taskId);
        AgentScopeSessionHook hook = new AgentScopeSessionHook(store);
        AgentScopeSessionHook.TraceRecorder recorder = hook.newTraceRecorder(new ExpertChatContext.State(
                runtimeSessionId, "admin", "inspection", null, true, null, taskId));
        ToolUseBlock toolUse = ToolUseBlock.builder()
                .id("call-inspect-1")
                .name("inspectService")
                .input(Map.of("serviceName", "service-a"))
                .build();

        hook.captureToolResult(runtimeSessionId, toolUse, ToolResultBlock.text("{\"status\":\"ok\"}"), 17L);
        recorder.record(new ToolCallStartEvent("reply-1", "call-inspect-1", "inspectService")).blockLast();
        recorder.record(new ToolResultEndEvent(
                "reply-1", "call-inspect-1", "inspectService", ToolResultState.SUCCESS)).blockLast();
        recorder.finish();
        recorder.finish();

        assertThat(store.activeRoundMessages(runtimeSessionId)).isEmpty();
        assertThat(store.activeRoundMessages(sessionId))
                .filteredOn(message -> "TOOL_CALL".equals(message.messageType())
                        || "TOOL_RESULT".equals(message.messageType()))
                .hasSize(2)
                .allSatisfy(message -> assertThat(message.metadata())
                        .containsEntry(ExpertMessageConstants.META_SESSION_ID, sessionId)
                        .containsEntry(ExpertMessageConstants.META_RUNTIME_SESSION_ID, runtimeSessionId)
                        .containsEntry(ExpertMessageConstants.META_TASK_ID, taskId)
                        .containsEntry("expertId", "inspection"));
        AiSessionStore.ChatMessage result = store.activeRoundMessages(sessionId).stream()
                .filter(message -> "TOOL_RESULT".equals(message.messageType()))
                .findFirst()
                .orElseThrow();
        assertThat(result.metadata())
                .containsEntry("durationMs", 17L)
                .containsEntry("toolResult", "{\"status\":\"ok\"}");
        assertThat(String.valueOf(result.metadata().get("toolInput"))).contains("service-a");
    }

    @Test
    void recordsToolExecutionDurationFromCallEndWhenTracerMissing() throws Exception {
        AiSessionStore store = new AiSessionStore();
        String sessionId = store.ensureSession(null, "data", "rk", "web-1", "admin");
        store.appendUserMessage(sessionId, "查拓扑", "data", "admin", Map.of());
        AgentScopeSessionHook hook = new AgentScopeSessionHook(store);
        AgentScopeSessionHook.TraceRecorder recorder = hook.newTraceRecorder(new ExpertChatContext.State(
                sessionId, "admin", "data", null, true, null));

        recorder.record(new ToolCallStartEvent("reply-1", "call-1", "queryServiceTopology")).blockLast();
        Thread.sleep(30L);
        recorder.record(new ToolCallEndEvent("reply-1", "call-1", "queryServiceTopology")).blockLast();
        Thread.sleep(20L);
        recorder.record(new ToolResultEndEvent(
                "reply-1", "call-1", "queryServiceTopology", ToolResultState.SUCCESS)).blockLast();

        AiSessionStore.ChatMessage result = store.activeRoundMessages(sessionId).stream()
                .filter(message -> "TOOL_RESULT".equals(message.messageType()))
                .findFirst()
                .orElseThrow();
        long durationMs = ((Number) result.metadata().get("durationMs")).longValue();
        assertThat(durationMs).isBetween(15L, 200L);
    }

    @Test
    void recordsFailedToolResultWithDurationAndStatus() {
        AiSessionStore store = new AiSessionStore();
        String sessionId = store.ensureSession(null, "data", "rk", "web-1", "admin");
        store.appendUserMessage(sessionId, "查拓扑", "data", "admin", Map.of());
        AgentScopeSessionHook hook = new AgentScopeSessionHook(store);
        AgentScopeSessionHook.TraceRecorder recorder = hook.newTraceRecorder(new ExpertChatContext.State(
                sessionId, "admin", "data", null, true, null));

        recorder.record(new ToolCallStartEvent("reply-1", "call-1", "queryServiceTopology")).blockLast();
        recorder.record(new ToolCallEndEvent("reply-1", "call-1", "queryServiceTopology")).blockLast();
        recorder.record(new ToolResultEndEvent(
                "reply-1", "call-1", "queryServiceTopology", ToolResultState.ERROR)).blockLast();

        AiSessionStore.ChatMessage result = store.activeRoundMessages(sessionId).stream()
                .filter(message -> "TOOL_RESULT".equals(message.messageType()))
                .findFirst()
                .orElseThrow();
        assertThat(result.messageStatus()).isEqualTo("FAILED");
        assertThat(result.metadata())
                .containsEntry("toolResultState", "ERROR")
                .containsKey("durationMs");
        assertThat(((Number) result.metadata().get("durationMs")).longValue()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void recordsTextBeforeToolCallAsReasoningInsteadOfFinalText() {
        AiSessionStore store = new AiSessionStore();
        String sessionId = store.ensureSession(null, "data", "rk", "web-1", "admin");
        store.appendUserMessage(sessionId, "查最近一小时服务列表", "data", "admin", Map.of());
        AgentScopeSessionHook hook = new AgentScopeSessionHook(store);
        AgentScopeSessionHook.TraceRecorder recorder = hook.newTraceRecorder(new ExpertChatContext.State(
                sessionId, "admin", "data", "assistant-1", true, null));

        recorder.record(new TextBlockDeltaEvent(
                "reply-1", "text-1", "我来帮您查询最近1小时的服务列表。")).blockLast();
        List<ExpertRuntimeEvent> events = recorder.record(
                new ToolCallStartEvent("reply-1", "call-1", "getCurrentTimeRange")).collectList().block();

        Assertions.assertThat(events)
                .extracting(ExpertRuntimeEvent::type)
                .containsExactly("reasoning", "tool_call");
        assertThat(store.activeRoundMessages(sessionId))
                .extracting(AiSessionStore.ChatMessage::messageType)
                .containsExactly("USER", "REASONING", "TOOL_CALL");
        assertThat(store.activeRoundMessages(sessionId))
                .extracting(AiSessionStore.ChatMessage::messageIndex)
                .containsExactly(1, 2, 3);
        assertThat(store.activeRoundMessages(sessionId).get(1).content())
                .isEqualTo("我来帮您查询最近1小时的服务列表。");
    }

    @Test
    void terminalTextWithoutFollowingToolIsLeftForOrchestratorClassification() {
        AiSessionStore store = new AiSessionStore();
        String sessionId = store.ensureSession(null, "brain", "rk", "web-1", "admin");
        store.appendUserMessage(sessionId, "汇总专家结论", "brain", "admin", Map.of());
        AgentScopeSessionHook hook = new AgentScopeSessionHook(store);
        AgentScopeSessionHook.TraceRecorder recorder = hook.newTraceRecorder(new ExpertChatContext.State(
                sessionId, "admin", "brain", "assistant-1", true, null));

        recorder.record(new TextBlockDeltaEvent(
                "reply-1", "text-1", "这是本次 stream 的最终答复。")).blockLast();
        recorder.finish();

        assertThat(store.activeRoundMessages(sessionId))
                .filteredOn(message -> "REASONING".equals(message.messageType()))
                .as("terminal text is a reply candidate, not pre-tool reasoning")
                .isEmpty();
    }

    @Test
    void recordsPreToolReasoningOnceWhenThinkingAndTextBlocksMatch() {
        AiSessionStore store = new AiSessionStore();
        String sessionId = store.ensureSession(null, "brain", "rk", "web-1", "admin");
        store.appendUserMessage(sessionId, "查服务", "brain", "admin", Map.of());
        AgentScopeSessionHook hook = new AgentScopeSessionHook(store);
        AgentScopeSessionHook.TraceRecorder recorder = hook.newTraceRecorder(new ExpertChatContext.State(
                sessionId, "admin", "brain", "assistant-1", true, null));
        String preToolText = "已派发任务给 'data' 专家，正在查询最近1小时的服务列表及指标概览，请稍候。";

        recorder.record(new io.agentscope.core.event.ThinkingBlockDeltaEvent(
                "reply-1", "think-1", preToolText)).blockLast();
        recorder.record(new TextBlockDeltaEvent(
                "reply-1", "text-1", preToolText)).blockLast();
        recorder.record(new ToolCallStartEvent("reply-1", "call-1", "dispatchExpertTask")).blockLast();

        assertThat(store.activeRoundMessages(sessionId))
                .filteredOn(message -> "REASONING".equals(message.messageType()))
                .hasSize(1)
                .first()
                .extracting(AiSessionStore.ChatMessage::content)
                .isEqualTo(preToolText);
    }
}
