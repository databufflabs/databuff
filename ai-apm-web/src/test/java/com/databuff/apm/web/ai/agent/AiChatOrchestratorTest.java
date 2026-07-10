package com.databuff.apm.web.ai.agent;

import com.databuff.apm.web.ai.TestAiSupport;
import com.databuff.apm.web.ai.TestBeanSupport;
import com.databuff.apm.web.ai.OpenAiCompatibleChatClient;
import com.databuff.apm.web.ai.platform.task.ExpertTaskContext;
import com.databuff.apm.web.support.WebTestClusterSupport;
import com.databuff.apm.web.ai.tool.ApmToolkit;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiChatOrchestratorTest {

    @Test
    void rejectsUnknownExpert() {
        AgentBrainService service = TestAiSupport.aiFixture().agentBrain(Mockito.mock(ApmToolkit.class), new AiSessionStore());

        assertThatThrownBy(() -> service.chat(new AgentBrainService.ChatRequest(
                null, "missing", "hello", false, java.util.Map.of(), null)))
                .isInstanceOf(AiPlatformChatException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void submitChatReturnsProcessingStatus() {
        AgentBrainService service = TestAiSupport.aiFixture().agentBrain(mock(ApmToolkit.class), new AiSessionStore());
        AgentBrainService.ChatSubmitResponse submitted = service.submitChat(
                new AgentBrainService.ChatRequest(null, "inspection", "hello", false, java.util.Map.of(), null));
        assertThat(submitted.status()).isEqualTo("PROCESSING");
        assertThat(submitted.assistantMessageId()).isNotBlank();
    }

    @Test
    void pollMessagesReflectRunningState() throws Exception {
        ApmToolkit toolkit = mock(ApmToolkit.class);
        when(toolkit.countRecentSpans(anyLong())).thenReturn(3);
        AgentBrainService service = TestAiSupport.aiFixture().agentBrain(toolkit, new AiSessionStore());
        AgentBrainService.ChatSubmitResponse submitted = service.submitChat(
                new AgentBrainService.ChatRequest(null, "最近 trace 有多少"));
        assertThat(submitted.status()).isEqualTo("PROCESSING");

        AiSessionStore.MessagePollResponse poll = null;
        for (int i = 0; i < 80; i++) {
            poll = service.pollMessages(submitted.sessionId(), null);
            if (!poll.running()) {
                break;
            }
            Thread.sleep(50L);
        }
        assertThat(poll).isNotNull();
        assertThat(poll.running()).isFalse();
        assertThat(poll.messages()).anyMatch(message -> message.messageId().equals(submitted.assistantMessageId()));
    }

    @Test
    void recordsExpertIdInSession() {
        AgentBrainService service = TestAiSupport.aiFixture().agentBrain(mock(ApmToolkit.class), new AiSessionStore());
        AgentBrainService.ChatResponse response = service.chat(new AgentBrainService.ChatRequest(
                null, "inspection", "hello", false, java.util.Map.of(), null));

        assertThat(response.expertId()).isEqualTo("inspection");
        assertThat(service.listSessions(0, 20).get("data")).asList().singleElement()
                .satisfies(summary -> assertThat(((AiSessionStore.SessionSummary) summary).expertId()).isEqualTo("inspection"));
    }

    @Test
    void brainChatStoresSubtaskMetadataWhenDispatchRuns() {
        TestAiSupport.AiFixture aiFixture = TestAiSupport.aiFixture();
        TestAiSupport.PlatformRuntimeFixture fixture =
                aiFixture.buildPlatformRuntime(mock(ApmToolkit.class));
        AiSessionStore store = new AiSessionStore();
        AiRuntimeRouter runtimeRouter = WebTestClusterSupport.standaloneAiRouter("web-1");
        AiChatOrchestrator orchestrator = TestBeanSupport.chatOrchestrator(
                fixture.expertManagementService(),
                fixture.expertRuntimeRegistry(),
                fixture.sessionExpertRuntimeRegistry(),
                store,
                aiFixture.aiConfigService(),
                aiFixture.agentRuntimeConfig(),
                mock(ApmToolkit.class),
                new OpenAiCompatibleChatClient(aiFixture.agentRuntimeConfig()),
                aiFixture.store(),
                runtimeRouter,
                new AiRuntimeForwarder(runtimeRouter, 120L),
                fixture.expertTaskService(),
                fixture.expertTaskPendingRegistry(),
                fixture.expertTaskTextGuard(),
                fixture.sessionWorkspaceService(),
                15);
        fixture.wireBrainContinuer(orchestrator);
        String sessionId = store.ensureSession(null, "brain", "rk", "web-1");
        ExpertTaskContext.run(sessionId, "brain", null, () -> {
            fixture.expertDispatchTool().dispatchExpertTask("data", "metrics", "{}", null);
            return null;
        });

        Map<String, Object> metadata = orchestrator.buildAssistantMetadata(sessionId, "brain");
        assertThat(metadata).containsKey("subtasks");
    }
}
