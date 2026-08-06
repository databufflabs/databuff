package com.databuff.apm.web.ai.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRuntimeConfigEdgeTest {

    @Test
    void exposesAndMutatesAllSettableProperties() {
        AgentRuntimeConfig config = new AgentRuntimeConfig();
        config.setAgentscopeEnabled(true);
        config.setInspectionAgentName("inspection-2");
        config.setBuiltinSkillsDir("/tmp/builtin");
        config.setCustomSkillsDir("/tmp/custom");
        config.setWorkspaceDir("/tmp/ws");
        config.setWorkspaceShellTimeoutSeconds(120);
        config.setShellCommandDenylist("nc,tcpdump");
        config.setMaxIters(50);
        config.setLlmForceHttp11(true);

        assertThat(config.isAgentscopeEnabled()).isTrue();
        assertThat(config.getInspectionAgentName()).isEqualTo("inspection-2");
        assertThat(config.getBuiltinSkillsDir()).isEqualTo("/tmp/builtin");
        assertThat(config.getCustomSkillsDir()).isEqualTo("/tmp/custom");
        assertThat(config.getWorkspaceDir()).isEqualTo("/tmp/ws");
        assertThat(config.getWorkspaceShellTimeoutSeconds()).isEqualTo(120);
        assertThat(config.getShellCommandDenylist()).isEqualTo("nc,tcpdump");
        assertThat(config.getMaxIters()).isEqualTo(50);
        assertThat(config.isLlmForceHttp11()).isTrue();
    }

    @Test
    void clampsDurationsToMinimum() {
        AgentRuntimeConfig config = new AgentRuntimeConfig();
        config.setLlmHttpTimeoutSeconds(0);
        config.setExpertRoundTimeoutSeconds(-5);
        config.setMaxIters(0);

        assertThat(config.llmHttpTimeout()).hasSeconds(1);
        assertThat(config.expertRoundTimeout()).hasSeconds(1);
        assertThat(config.expertRoundTimeoutMillis()).isEqualTo(1000);
        assertThat(config.resolvedMaxIters()).isEqualTo(1);
    }

    @Test
    void parsesWorkspaceShellCommands() {
        AgentRuntimeConfig config = new AgentRuntimeConfig();
        assertThat(config.workspaceShellCommands()).contains("cat", "head", "tail", "grep", "wc", "ls", "file", "python3");

        config.setWorkspaceShellCommands("cat, LS,,python3,  ");
        assertThat(config.workspaceShellCommands()).containsExactlyInAnyOrder("cat", "ls", "python3");

        config.setWorkspaceShellCommands("  ");
        assertThat(config.workspaceShellCommands()).contains("cat", "head");

        config.setWorkspaceShellCommands(",");
        assertThat(config.workspaceShellCommands()).contains("cat", "head");
    }

    @Test
    void buildsShellCommandPolicyFromDenylist() {
        AgentRuntimeConfig config = new AgentRuntimeConfig();
        config.setShellCommandDenylist("nc,tcpdump");

        ShellCommandPolicy policy = config.shellCommandPolicy();

        assertThat(policy.userDenylistSize()).isEqualTo(2);
        assertThat(policy.userDeniedCommands()).contains("nc", "tcpdump");
    }

    @Test
    void resolvesWorkspaceDirectoryAbsolute() {
        AgentRuntimeConfig config = new AgentRuntimeConfig();
        config.setWorkspaceDir("./data/ai-workspaces");

        Path dir = config.workspaceDirectory();
        assertThat(dir.isAbsolute()).isTrue();
        assertThat(dir.getFileName().toString()).isEqualTo("ai-workspaces");
    }

    @Test
    void skillSearchDirectoriesPrefersCustomThenBuiltin() throws Exception {
        Path builtin = java.nio.file.Files.createTempDirectory("builtin-skills-");
        Path custom = java.nio.file.Files.createTempDirectory("custom-skills-");
        AgentRuntimeConfig config = new AgentRuntimeConfig();
        config.setBuiltinSkillsDir(builtin.toString());
        config.setCustomSkillsDir(custom.toString());

        java.util.List<Path> dirs = config.skillSearchDirectories();

        assertThat(dirs).containsExactly(custom, builtin);
    }

    @Test
    void layeredSkillRepositoryAndModelConfigAreUsable() {
        AgentRuntimeConfig config = new AgentRuntimeConfig();
        assertThat(config.layeredSkillRepository()).isNotNull();
        assertThat(config.llmModelExecutionConfig()).isNotNull();
        assertThat(config.shellCommandPolicy()).isNotNull();
        assertThat(config.summary()).isNotNull();
    }
}
