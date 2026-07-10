package com.databuff.apm.web.ai.agent;

import com.databuff.apm.web.ai.TestAiSupport;
import com.databuff.apm.web.ai.tool.ApmToolkit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentControllerTest {

    @Test
    void delegatesChatAndSessions() {
        ApmToolkit toolkit = mock(ApmToolkit.class);
        when(toolkit.listServiceHealth(anyLong())).thenReturn(java.util.List.of());
        AgentBrainService brain = TestAiSupport.aiFixture().agentBrain(toolkit, new AiSessionStore());
        AgentController controller = TestAiSupport.aiFixture().agentController(brain);

        AgentBrainService.ChatResponse chat = brain.chat(
                new AgentBrainService.ChatRequest(null, "帮助"));
        assertThat(chat.reply()).contains("DataBuff APM 助手");
        assertThat(controller.sessionCount().get("total")).isEqualTo(1L);
        assertThat(controller.sessions(0, 20).get("data")).asList().hasSize(1);
        assertThat(controller.messages(chat.sessionId(), null).messages()).isNotEmpty();
    }
}
