package com.databuff.apm.web.ai.platform.runtime;

import com.databuff.apm.web.ai.platform.task.ExpertTaskContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExpertChatScopeRegistryTest {

    @AfterEach
    void tearDown() {
        ExpertChatScopeRegistry.clearForTests();
    }

    @Test
    void subtaskRegisterDoesNotOverwriteParentBrainScope() {
        String sessionId = "s-parent";
        ExpertChatScopeRegistry.register(new ExpertChatContext.State(
                sessionId, "admin", "brain", null, false, null));

        String runtimeSessionId = ExpertChatScopeRegistry.taskScopedSessionId(sessionId, "task-1");
        ExpertChatScopeRegistry.register(new ExpertChatContext.State(
                runtimeSessionId, "admin", "qa", null, true, null, "task-1"));

        assertThat(ExpertChatScopeRegistry.find(sessionId))
                .isPresent()
                .get()
                .extracting(ExpertChatContext.State::expertId)
                .isEqualTo("brain");
        assertThat(ExpertChatScopeRegistry.find(runtimeSessionId))
                .isPresent()
                .get()
                .extracting(ExpertChatContext.State::expertId)
                .isEqualTo("qa");
        assertThat(ExpertChatScopeRegistry.findParent(runtimeSessionId))
                .isPresent()
                .get()
                .extracting(ExpertChatContext.State::expertId)
                .isEqualTo("brain");
    }

    @Test
    void registerWithTaskIdOnLogicalSessionIdIsRewrittenToTaskScopedKey() {
        String sessionId = "s-rewrite";
        ExpertChatScopeRegistry.register(new ExpertChatContext.State(
                sessionId, "admin", "brain", null, false, null));
        ExpertChatScopeRegistry.register(new ExpertChatContext.State(
                sessionId, "admin", "qa", null, true, null, "task-2"));

        assertThat(ExpertChatScopeRegistry.find(sessionId))
                .isPresent()
                .get()
                .extracting(ExpertChatContext.State::expertId)
                .isEqualTo("brain");
        assertThat(ExpertChatScopeRegistry.find(
                ExpertChatScopeRegistry.taskScopedSessionId(sessionId, "task-2")))
                .isPresent()
                .get()
                .extracting(ExpertChatContext.State::expertId)
                .isEqualTo("qa");
    }

    @Test
    void hasActiveTaskForExpertMatchesTaskScopedRegistration() {
        String sessionId = "s-active-task";
        ExpertChatScopeRegistry.register(new ExpertChatContext.State(
                sessionId, "admin", "brain", null, false, null));
        assertThat(ExpertChatScopeRegistry.hasActiveTaskForExpert(sessionId, "data")).isFalse();

        ExpertChatScopeRegistry.register(new ExpertChatContext.State(
                ExpertChatScopeRegistry.taskScopedSessionId(sessionId, "task-9"),
                "admin",
                "data",
                null,
                true,
                null,
                "task-9"));
        assertThat(ExpertChatScopeRegistry.hasActiveTaskForExpert(sessionId, "data")).isTrue();
        assertThat(ExpertChatScopeRegistry.hasActiveTaskForExpert(sessionId, "inspection")).isFalse();
        assertThat(ExpertChatScopeRegistry.hasActiveTaskForExpert(
                ExpertChatScopeRegistry.taskScopedSessionId(sessionId, "task-9"), "data")).isTrue();
    }

    @Test
    void parallelTaskScopesDoNotShareStackKeys() {
        String sessionId = "s-parallel";
        ExpertTaskContext.clearForTests();
        try {
            String a = ExpertChatScopeRegistry.taskScopedSessionId(sessionId, "t-a");
            String b = ExpertChatScopeRegistry.taskScopedSessionId(sessionId, "t-b");
            ExpertTaskContext.enterScope(a, "brain", ignored -> {});
            ExpertTaskContext.enterScope(b, "brain", ignored -> {});
            ExpertTaskContext.leaveScope(a);

            // Leaving A must not pop B's frame.
            assertThat(ExpertTaskContext.outermostSourceExpertId(sessionId)).isEmpty();
            ExpertTaskContext.enterScope(sessionId, "brain", ignored -> {});
            assertThat(ExpertTaskContext.outermostSourceExpertId(sessionId)).contains("brain");
            ExpertTaskContext.leaveScope(b);
            ExpertTaskContext.leaveScope(sessionId);
        } finally {
            ExpertTaskContext.clearForTests();
        }
    }
}
