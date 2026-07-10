package com.databuff.apm.web.tools.local;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledOnOs({OS.LINUX, OS.MAC})
class BashSessionTest {

    private BashSession session;

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    @Test
    void executesCommandAndReturnsExitCode() throws Exception {
        session = new BashSession();
        BashSession.BashExecutionResult result = session.execute("echo hello", 5_000, 10_000);
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isEqualTo("hello");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void preservesWorkingDirectoryAcrossCommands() throws Exception {
        session = new BashSession();
        BashSession.BashExecutionResult result = session.execute("cd /tmp && pwd", 5_000, 10_000);
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isEqualTo("/tmp");
    }

    @Test
    void truncatesLongOutput() throws Exception {
        session = new BashSession();
        BashSession.BashExecutionResult result = session.execute("python3 -c \"print('x'*100)\"", 10_000, 20);
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).hasSize(20);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void redirectsStdinForChildProcesses() throws Exception {
        session = new BashSession();
        // ssh without stdin redirect would deadlock in a persistent session; use a command that reads stdin.
        BashSession.BashExecutionResult result = session.execute(
                "read -r line; echo \"got:$line\"", 5_000, 10_000);
        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isEqualTo("got:");
    }

    @Test
    void rejectsBlankCommand() throws Exception {
        session = new BashSession();
        assertThatThrownBy(() -> session.execute("   ", 5_000, 10_000))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
