package com.databuff.apm.web.ai.agent;

import com.databuff.apm.web.ai.OpenAiCompatibleChatClient;
import com.databuff.apm.web.ai.TestAiSupport;
import com.databuff.apm.web.ai.TestBeanSupport;
import com.databuff.apm.web.ai.UpdateLlmProviderRequest;
import com.databuff.apm.web.ai.platform.expert.AiExpertDefinition;
import com.databuff.apm.web.ai.platform.expert.ExpertManagementService;
import com.databuff.apm.web.ai.platform.task.ExpertTaskContext;
import com.databuff.apm.web.ai.tool.ApmToolkit;
import jakarta.servlet.http.HttpServletRequest;
import com.databuff.apm.web.support.WebTestClusterSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Design Phase 6 integration checks mapped to requirement acceptance criteria.
 */
class AiAcceptanceIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void builtInExpertsExistAndCannotBeDeleted() {
        ExpertManagementService experts = platformFixture().expertManagementService();
        assertThat(experts.list()).extracting(AiExpertDefinition::expertId)
                .containsExactlyInAnyOrder("brain", "data", "inspection", "ops");
        assertThat(experts.delete("brain")).isFalse();
        assertThat(experts.delete("data")).isFalse();
        assertThat(experts.delete("inspection")).isFalse();
    }

    @Test
    void platformCrudSurfacesAreAvailable() {
        TestAiSupport.PlatformRuntimeFixture fixture = platformFixture();
        assertThat(fixture.toolManagementService().list()).isNotEmpty();
        assertThat(fixture.skillManagementService().list()).isNotEmpty();
        assertThat(fixture.expertManagementService().list()).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void chatDefaultsToBrainAndRoutesDirectExpert() {
        ApmToolkit toolkit = mock(ApmToolkit.class);
        when(toolkit.countRecentSpans(anyLong())).thenReturn(3);
        AgentBrainService brain = TestAiSupport.aiFixture().agentBrain(toolkit, new AiSessionStore());

        AgentBrainService.ChatResponse brainResponse = brain.chat(
                new AgentBrainService.ChatRequest(null, "最近 trace 有多少"));
        assertThat(brainResponse.expertId()).isEqualTo("brain");
        assertThat(brainResponse.reply()).contains("3");

        AgentBrainService.ChatResponse data = brain.chat(
                new AgentBrainService.ChatRequest(null, "data", "hello", false, Map.of(), null));
        assertThat(data.expertId()).isEqualTo("data");
    }

    @Test
    void submitPollChatFlowWorksWithoutStream() throws Exception {
        ApmToolkit toolkit = mock(ApmToolkit.class);
        when(toolkit.countRecentSpans(anyLong())).thenReturn(9);
        AgentBrainService brain = TestAiSupport.aiFixture().agentBrain(toolkit, new AiSessionStore());
        AgentController controller = TestAiSupport.aiFixture().agentController(brain);
        HttpServletRequest request = mock(HttpServletRequest.class);

        AgentBrainService.ChatSubmitResponse submitted = controller.submit(
                request,
                new AgentBrainService.ChatRequest(null, "最近 trace 有多少"));
        assertThat(submitted.status()).isEqualTo("PROCESSING");
        assertThat(submitted.expertId()).isEqualTo("brain");

        AiSessionStore.MessagePollResponse poll = null;
        for (int i = 0; i < 80; i++) {
            poll = controller.messages(submitted.sessionId(), null);
            if (!poll.running()) {
                break;
            }
            Thread.sleep(50L);
        }
        assertThat(poll).isNotNull();
        assertThat(poll.running()).isFalse();
        assertThat(poll.messages()).anyMatch(message ->
                "assistant".equals(message.role()) && message.content().contains("9"));
    }

    @Test
    void brainSubtasksAreTrackedInSessionMessages() {
        TestAiSupport.PlatformRuntimeFixture fixture = platformFixture();
        AiSessionStore store = new AiSessionStore();
        AiChatOrchestrator orchestrator = orchestrator(fixture, store);
        String sessionId = store.ensureSession(null, "brain", "session:key", "web-1");

        ExpertTaskContext.run(sessionId, "brain", null, () -> {
            fixture.expertDispatchTool().dispatchExpertTask("data", "health summary", "{}", null);
            fixture.expertDispatchTool().dispatchExpertTask("inspection", "triage alerts", "{}", null);
            return null;
        });

        Map<String, Object> metadata = orchestrator.buildAssistantMetadata(sessionId, "brain");
        store.appendAssistantMessage(sessionId, "aggregated reply", "brain", metadata);

        assertThat(store.messages(sessionId))
                .hasSize(1)
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.expertId()).isEqualTo("brain");
                    assertThat(message.metadata()).containsKey("subtasks");
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> subtasks =
                            (List<Map<String, Object>>) message.metadata().get("subtasks");
                    assertThat(subtasks).hasSize(2);
                    assertThat(subtasks).extracting(item -> item.get("targetExpertId"))
                            .containsExactlyInAnyOrder("data", "inspection");
                });
        assertThat(fixture.expertTaskService().listBySession(sessionId)).hasSize(2);
    }

    private TestAiSupport.PlatformRuntimeFixture platformFixture() {
        TestAiSupport.AiFixture aiFixture = TestAiSupport.aiFixture();
        aiFixture.agentRuntimeConfig().setCustomSkillsDir(tempDir.toString());
        aiFixture.store().updateProvider("openai", new UpdateLlmProviderRequest(null, "sk-test", null, true));
        return aiFixture.buildPlatformRuntime(mock(ApmToolkit.class));
    }

    private AgentController controller(ApmToolkit toolkit) {
        return TestAiSupport.aiFixture().agentController(
                TestAiSupport.aiFixture().agentBrain(toolkit, new AiSessionStore()));
    }

    private AiChatOrchestrator orchestrator(TestAiSupport.PlatformRuntimeFixture fixture, AiSessionStore store) {
        TestAiSupport.AiFixture aiFixture = TestAiSupport.aiFixture();
        AiChatOrchestrator chatOrchestrator = TestBeanSupport.chatOrchestrator(
                fixture.expertManagementService(),
                fixture.expertRuntimeRegistry(),
                fixture.sessionExpertRuntimeRegistry(),
                store,
                aiFixture.aiConfigService(),
                aiFixture.agentRuntimeConfig(),
                mock(ApmToolkit.class),
                new OpenAiCompatibleChatClient(aiFixture.agentRuntimeConfig()),
                aiFixture.store(),
                WebTestClusterSupport.standaloneAiRouter("web-1"),
                new AiRuntimeForwarder(WebTestClusterSupport.standaloneAiRouter("web-1"), 120L),
                fixture.expertTaskService(),
                fixture.expertTaskPendingRegistry(),
                fixture.expertTaskTextGuard(),
                fixture.sessionWorkspaceService(),
                15);
        fixture.wireBrainContinuer(chatOrchestrator);
        return chatOrchestrator;
    }
}
