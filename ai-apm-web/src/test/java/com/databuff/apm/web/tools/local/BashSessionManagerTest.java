package com.databuff.apm.web.tools.local;

import com.databuff.apm.web.ai.platform.runtime.ExpertChatContext;
import com.databuff.apm.web.ai.platform.runtime.ExpertChatScopeRegistry;
import com.databuff.apm.web.ai.platform.task.ExpertTaskContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class BashSessionManagerTest {

    @BeforeEach
    void setUp() {
        ExpertChatScopeRegistry.clearForTests();
        ExpertTaskContext.clearForTests();
    }

    @AfterEach
    void tearDown() {
        ExpertChatScopeRegistry.clearForTests();
        ExpertTaskContext.clearForTests();
    }

    @Nested
    @EnabledOnOs({OS.LINUX, OS.MAC})
    class ExecuteTests {

        private final BashBackgroundTaskManager backgroundTaskManager = new BashBackgroundTaskManager();
        private final BashSessionManager sessionManager = new BashSessionManager(backgroundTaskManager);

        @AfterEach
        void closeSessions() {
            sessionManager.closeSession("chat-1");
            sessionManager.closeSession("default");
        }

        @Test
        void executesCommandAndReturnsResult() throws Exception {
            BashSession.BashExecutionResult result =
                    sessionManager.execute("chat-1", "echo hello", 5_000, 10_000);

            assertThat(result.exitCode()).isZero();
            assertThat(result.output()).isEqualTo("hello");
            assertThat(result.truncated()).isFalse();
        }

        @Test
        void createsSessionOnFirstExecuteAndReusesIt() throws Exception {
            BashSession.BashExecutionResult first =
                    sessionManager.execute("chat-1", "echo first", 5_000, 10_000);
            BashSession.BashExecutionResult second =
                    sessionManager.execute("chat-1", "echo second", 5_000, 10_000);
            BashSession.BashExecutionResult firstPid =
                    sessionManager.execute("chat-1", "echo $$", 5_000, 10_000);
            BashSession.BashExecutionResult secondPid =
                    sessionManager.execute("chat-1", "echo $$", 5_000, 10_000);

            assertThat(first.output()).isEqualTo("first");
            assertThat(second.output()).isEqualTo("second");
            assertThat(firstPid.output()).isEqualTo(secondPid.output());
        }

        @Test
        void usesDefaultSessionWhenSessionIdIsNullOrBlank() throws Exception {
            BashSession.BashExecutionResult fromNull =
                    sessionManager.execute(null, "echo default-null", 5_000, 10_000);
            BashSession.BashExecutionResult fromBlank =
                    sessionManager.execute("   ", "echo default-blank", 5_000, 10_000);

            assertThat(fromNull.exitCode()).isZero();
            assertThat(fromNull.output()).isEqualTo("default-null");
            assertThat(fromBlank.exitCode()).isZero();
            assertThat(fromBlank.output()).isEqualTo("default-blank");
        }

        @Test
        void rejectsBlankCommand() {
            assertThatThrownBy(() -> sessionManager.execute("chat-1", "   ", 5_000, 10_000))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @EnabledOnOs({OS.LINUX, OS.MAC})
    class CloseSessionIntegrationTests {

        private final BashBackgroundTaskManager backgroundTaskManager = new BashBackgroundTaskManager();
        private final BashSessionManager sessionManager = new BashSessionManager(backgroundTaskManager);

        @Test
        void closeSessionRemovesPersistentState() throws Exception {
            BashSession.BashExecutionResult beforeClose =
                    sessionManager.execute("chat-1", "echo $$", 5_000, 10_000);
            sessionManager.closeSession("chat-1");

            BashSession.BashExecutionResult afterClose =
                    sessionManager.execute("chat-1", "echo $$", 5_000, 10_000);

            assertThat(beforeClose.output()).isNotEqualTo(afterClose.output());
            sessionManager.closeSession("chat-1");
        }
    }

    @Nested
    class CloseSessionTests {

        @Test
        void closeSessionDelegatesToBackgroundTaskManager() {
            BashBackgroundTaskManager backgroundTaskManager = mock(BashBackgroundTaskManager.class);
            BashSessionManager sessionManager = new BashSessionManager(backgroundTaskManager);

            sessionManager.closeSession("chat-1");

            verify(backgroundTaskManager).closeSession("chat-1");
        }

        @Test
        void closeSessionTrimsSessionId() {
            BashBackgroundTaskManager backgroundTaskManager = mock(BashBackgroundTaskManager.class);
            BashSessionManager sessionManager = new BashSessionManager(backgroundTaskManager);

            sessionManager.closeSession("  chat-1  ");

            verify(backgroundTaskManager).closeSession("chat-1");
        }

        @Test
        void closeSessionIgnoresNullSessionId() {
            BashBackgroundTaskManager backgroundTaskManager = mock(BashBackgroundTaskManager.class);
            BashSessionManager sessionManager = new BashSessionManager(backgroundTaskManager);

            sessionManager.closeSession(null);

            verify(backgroundTaskManager, never()).closeSession(anyString());
        }

        @Test
        void closeSessionIgnoresBlankSessionId() {
            BashBackgroundTaskManager backgroundTaskManager = mock(BashBackgroundTaskManager.class);
            BashSessionManager sessionManager = new BashSessionManager(backgroundTaskManager);

            sessionManager.closeSession("   ");

            verify(backgroundTaskManager, never()).closeSession(anyString());
        }

        @Test
        void closeSessionStillDelegatesForUnknownSession() {
            BashBackgroundTaskManager backgroundTaskManager = mock(BashBackgroundTaskManager.class);
            BashSessionManager sessionManager = new BashSessionManager(backgroundTaskManager);

            sessionManager.closeSession("missing");

            verify(backgroundTaskManager).closeSession("missing");
        }
    }

    @Nested
    class ResolveSessionIdTests {

        private final BashBackgroundTaskManager backgroundTaskManager = mock(BashBackgroundTaskManager.class);
        private final BashSessionManager sessionManager = new BashSessionManager(backgroundTaskManager);

        @Test
        void resolveSessionIdReturnsDefaultWhenNoContext() {
            assertThat(sessionManager.resolveSessionId()).isEqualTo("default");
        }

        @Test
        void resolveSessionIdUsesTaskContextSession() {
            ExpertTaskContext.runVoid("task-session", "brain", null, () ->
                    assertThat(sessionManager.resolveSessionId()).isEqualTo("task-session"));
        }

        @Test
        void resolveSessionIdUsesSoleChatScopeWhenTaskContextMissing() {
            ExpertChatScopeRegistry.register(new ExpertChatContext.State(
                    "chat-scope", "tester", "ops", "msg-1", false, null, null));

            assertThat(sessionManager.resolveSessionId()).isEqualTo("chat-scope");
        }

        @Test
        void resolveSessionIdPrefersTaskContextOverChatScope() {
            ExpertChatScopeRegistry.register(new ExpertChatContext.State(
                    "chat-scope", "tester", "ops", "msg-1", false, null, null));

            ExpertTaskContext.runVoid("task-session", "brain", null, () ->
                    assertThat(sessionManager.resolveSessionId()).isEqualTo("task-session"));
        }

        @Test
        void resolveSessionIdFallsBackWhenTaskContextSessionInvalid() {
            ExpertChatScopeRegistry.register(new ExpertChatContext.State(
                    "chat-scope", "tester", "ops", "msg-1", false, null, null));

            ExpertTaskContext.runVoid("anonymous", "brain", null, () ->
                    assertThat(sessionManager.resolveSessionId()).isEqualTo("chat-scope"));
        }
    }
}
