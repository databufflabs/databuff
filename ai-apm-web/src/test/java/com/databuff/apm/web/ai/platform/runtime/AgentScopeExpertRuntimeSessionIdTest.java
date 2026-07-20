package com.databuff.apm.web.ai.platform.runtime;

import com.databuff.apm.web.ai.platform.task.ExpertMessageConstants;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentScopeExpertRuntimeSessionIdTest {

    @Test
    void memorySessionIdUsesLogicalParentNotTaskScope() {
        String parent = "chat-session-1";
        String taskScoped = ExpertChatScopeRegistry.taskScopedSessionId(parent, "task-9");
        ExpertChatInput input = new ExpertChatInput(
                "continue inspection",
                taskScoped,
                "admin",
                null,
                Map.of(
                        ExpertMessageConstants.META_SESSION_ID, parent,
                        ExpertMessageConstants.META_TASK_ID, "task-9",
                        ExpertMessageConstants.META_RUNTIME_SESSION_ID, taskScoped));

        assertThat(AgentScopeExpertRuntime.resolveMemorySessionId(input)).isEqualTo(parent);
        assertThat(AgentScopeExpertRuntime.resolveChatScopeSessionId(input, "task-9"))
                .isEqualTo(taskScoped);
    }

    @Test
    void directChatUsesLogicalSessionForBothKeys() {
        ExpertChatInput input = new ExpertChatInput(
                "inspect service-b",
                "chat-session-2",
                "admin",
                "asst-1",
                Map.of());

        assertThat(AgentScopeExpertRuntime.resolveMemorySessionId(input)).isEqualTo("chat-session-2");
        assertThat(AgentScopeExpertRuntime.resolveChatScopeSessionId(input, null))
                .isEqualTo("chat-session-2");
    }
}
