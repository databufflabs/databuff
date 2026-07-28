package com.databuff.apm.web.ai.platform.task;

import com.databuff.apm.web.ai.TestAiSupport;
import com.databuff.apm.web.ai.UpdateLlmProviderRequest;
import com.databuff.apm.web.ai.platform.runtime.ExpertChatContext;
import com.databuff.apm.web.ai.platform.runtime.ExpertChatScopeRegistry;
import com.databuff.apm.web.ai.tool.ApmToolkit;
import io.agentscope.core.agent.RuntimeContext;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.time.Duration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ExpertDispatchToolTest {

    @TempDir
    Path tempDir;

    private ExpertDispatchTool dispatchTool;
    private ExpertTaskService taskService;

    @BeforeEach
    void setUp() {
        ExpertChatScopeRegistry.clearForTests();
        ExpertTaskContext.clearForTests();
        TestAiSupport.AiFixture aiFixture = TestAiSupport.aiFixture();
        aiFixture.agentRuntimeConfig().setCustomSkillsDir(tempDir.toString());
        aiFixture.store().updateProvider("openai", new UpdateLlmProviderRequest(
                null, "sk-test", null, true));
        TestAiSupport.PlatformRuntimeFixture fixture =
                aiFixture.buildPlatformRuntime(Mockito.mock(ApmToolkit.class));
        taskService = fixture.expertTaskService();
        dispatchTool = fixture.expertDispatchTool();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (taskService != null) {
            taskService.shutdownForTests();
        }
        ExpertChatScopeRegistry.clearForTests();
        ExpertTaskContext.clearForTests();
    }

    @Test
    void dispatchUsesRuntimeContextWhenTaskContextMissing() {
        String sessionId = "s-scope";
        ExpertChatScopeRegistry.register(new ExpertChatContext.State(
                sessionId, "admin", "brain", null, false, null));
        try {
            RuntimeContext ctx = RuntimeContext.builder().sessionId(sessionId).build();
            dispatchTool.dispatchExpertTask("data", "count spans", "{}", ctx);
            ExpertTask created = taskService.listBySession(sessionId).stream()
                    .findFirst()
                    .orElseThrow();
            assertThat(created.sessionId()).isEqualTo(sessionId);
            assertThat(taskService.listBySession("anonymous")).isEmpty();
        } finally {
            ExpertChatScopeRegistry.unregister(sessionId);
        }
    }

    @Test
    void dispatchCreatesTaskInContext() throws Exception {
        List<ExpertTaskEvent> events = new ArrayList<>();
        RuntimeContext ctx = RuntimeContext.builder().sessionId("s-dispatch").build();
        String result = ExpertTaskContext.run("s-dispatch", "brain", events::add, () ->
                dispatchTool.dispatchExpertTask("data", "count spans", "{}", ctx));
        ExpertTask finished = taskService.listBySession("s-dispatch").stream()
                .findFirst()
                .orElseThrow();
        taskService.waitFor(finished.taskId(), Duration.ofSeconds(5));

        assertThat(result).contains("taskId=" + finished.taskId());
        assertThat(result).contains("已派出").contains("targetExpertId=data");
        ExpertMessageContractTest.assertNoBanned(result, ExpertMessageContractTest.PROTOCOL_COACHING_BANNED);
        Assertions.assertThat(events).isNotEmpty();
        assertThat(events.get(0).type()).isEqualTo("subtask.created");
        assertThat(finished.status()).isIn(
                ExpertTaskStatus.CREATED,
                ExpertTaskStatus.RUNNING,
                ExpertTaskStatus.SUCCEEDED,
                ExpertTaskStatus.FAILED,
                ExpertTaskStatus.TIMEOUT,
                ExpertTaskStatus.CANCELLED);
    }

    @Test
    void dispatchUsesRuntimeContextWhenMultipleChatScopesActive() {
        ExpertChatScopeRegistry.register(new ExpertChatContext.State(
                "s-other", "admin", "brain", null, false, null));
        ExpertChatScopeRegistry.register(new ExpertChatContext.State(
                "s-target", "admin", "brain", null, false, null));
        RuntimeContext runtimeContext = RuntimeContext.builder().sessionId("s-target").build();
        try {
            dispatchTool.dispatchExpertTask("data", "count spans", "{}", runtimeContext);
            assertThat(taskService.listBySession("s-target")).hasSize(1);
            assertThat(taskService.listBySession("s-other")).isEmpty();
            assertThat(taskService.listBySession("anonymous")).isEmpty();
        } finally {
            ExpertChatScopeRegistry.unregister("s-other");
            ExpertChatScopeRegistry.unregister("s-target");
        }
    }

    @Test
    void dispatchFailsWhenSessionUnavailable() {
        assertThatThrownBy(() -> dispatchTool.dispatchExpertTask("data", "count spans", "{}", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sessionId unavailable");
    }

    @Test
    void dispatchToSameTargetIsSerialWhileInFlight() {
        String sessionId = "s-serial";
        ExpertChatScopeRegistry.register(new ExpertChatContext.State(
                sessionId, "admin", "brain", null, false, null));
        RuntimeContext ctx = RuntimeContext.builder().sessionId(sessionId).build();
        try {
            String first = ExpertTaskContext.run(sessionId, "brain", ignored -> {}, () ->
                    dispatchTool.dispatchExpertTask("data", "count spans", "{}", ctx));
            String second = ExpertTaskContext.run(sessionId, "brain", ignored -> {}, () ->
                    dispatchTool.dispatchExpertTask("data", "count spans again", "{}", ctx));
            String otherExpert = ExpertTaskContext.run(sessionId, "brain", ignored -> {}, () ->
                    dispatchTool.dispatchExpertTask("inspection", "inspect service-a", "{}", ctx));

            assertThat(first).contains("已派出").contains("targetExpertId=data");
            assertThat(second).contains("该专家当前占用中").contains("targetExpertId=data");
            assertThat(otherExpert).contains("已派出").contains("targetExpertId=inspection");
            ExpertMessageContractTest.assertNoBanned(first, ExpertMessageContractTest.PROTOCOL_COACHING_BANNED);
            ExpertMessageContractTest.assertNoBanned(second, ExpertMessageContractTest.PROTOCOL_COACHING_BANNED);
            ExpertMessageContractTest.assertNoBanned(otherExpert, ExpertMessageContractTest.PROTOCOL_COACHING_BANNED);
            assertThat(second)
                    .doesNotContain("请等待")
                    .doesNotContain("系统注入")
                    .doesNotContain("稍后再试");
            assertThat(taskService.listBySession(sessionId).stream()
                    .filter(t -> "data".equals(t.targetExpertId()))
                    .count()).isEqualTo(1);
            assertThat(taskService.listBySession(sessionId).stream()
                    .filter(t -> "inspection".equals(t.targetExpertId()))
                    .count()).isEqualTo(1);
            assertThat(taskService.listBySession(sessionId))
                    .allMatch(t -> "brain".equals(t.sourceExpertId()));
        } finally {
            ExpertChatScopeRegistry.unregister(sessionId);
        }
    }

    @Test
    void dispatchAllowsSameTargetAgainAfterInFlightCompletes() throws Exception {
        String sessionId = "s-serial-again";
        ExpertChatScopeRegistry.register(new ExpertChatContext.State(
                sessionId, "admin", "brain", null, false, null));
        RuntimeContext ctx = RuntimeContext.builder().sessionId(sessionId).build();
        try {
            String first = ExpertTaskContext.run(sessionId, "brain", ignored -> {}, () ->
                    dispatchTool.dispatchExpertTask("data", "count spans", "{}", ctx));
            ExpertTask firstTask = taskService.listBySession(sessionId).stream()
                    .filter(t -> "data".equals(t.targetExpertId()))
                    .findFirst()
                    .orElseThrow();
            ExpertTask finished = taskService.waitFor(firstTask.taskId(), Duration.ofSeconds(30));
            assertThat(finished.status().isTerminal())
                    .as("first data task must finish before serial re-dispatch")
                    .isTrue();

            String second = ExpertTaskContext.run(sessionId, "brain", ignored -> {}, () ->
                    dispatchTool.dispatchExpertTask("data", "count spans again", "{}", ctx));

            assertThat(first).contains("已派出");
            assertThat(second).contains("已派出");
            assertThat(taskService.listBySession(sessionId).stream()
                    .filter(t -> "data".equals(t.targetExpertId()))
                    .count()).isEqualTo(2);
            assertThat(taskService.listBySession(sessionId))
                    .allMatch(t -> "brain".equals(t.sourceExpertId()));
        } finally {
            ExpertChatScopeRegistry.unregister(sessionId);
        }
    }

    @Test
    void dispatchRecordsBrainSourceEvenWhenNestedTargetChatScopePresent() {
        String sessionId = "s-source-edge";
        // Simulate historical bug: subtask overwrote parent chat-scope expertId to target (qa).
        ExpertChatScopeRegistry.register(new ExpertChatContext.State(
                sessionId, "admin", "qa", null, true, null));
        try {
            RuntimeContext ctx = RuntimeContext.builder().sessionId(sessionId).build();
            String result = dispatchTool.dispatchExpertTask("qa", "explain architecture", "{}", ctx);
            ExpertTask created = taskService.listBySession(sessionId).stream()
                    .findFirst()
                    .orElseThrow();
            assertThat(result).contains("已派出").contains("targetExpertId=qa");
            assertThat(created.sourceExpertId()).isEqualTo("brain");
            assertThat(created.targetExpertId()).isEqualTo("qa");
        } finally {
            ExpertChatScopeRegistry.unregister(sessionId);
        }
    }
}
