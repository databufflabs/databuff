package com.databuff.apm.web.ai.agent;

import com.databuff.apm.web.ai.TestBeanSupport;
import com.databuff.apm.web.monitor.service.AlarmService;
import com.databuff.apm.web.ai.OpenAiCompatibleChatClient;
import com.databuff.apm.web.portal.ServicePortalService;
import com.databuff.apm.web.portal.TracePortalService;
import com.databuff.apm.web.tools.local.DataTools;
import com.databuff.apm.web.tools.local.TimeTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentScopeChatServiceTest {

    @TempDir
    Path tempDir;

    private AgentScopeChatService service;
    private AgentRuntimeConfig runtime;

    @BeforeEach
    void setUp() {
        DataTools dataTools = TestBeanSupport.dataTools(
                mock(ServicePortalService.class),
                mock(TracePortalService.class),
                Mockito.mock(AlarmService.class),
                new ObjectMapper());
        service = new AgentScopeChatService(dataTools, new TimeTool());
        runtime = new AgentRuntimeConfig();
        runtime.setBrainAgentName("brain");
    }

    @Test
    void skipsWhenRuntimeNull() {
        Optional<AgentScopeChatService.ChatOutcome> outcome = service.chatIfEnabled(
                null,
                new OpenAiCompatibleChatClient.ResolvedLlmProvider("openai", "http://x/v1", "gpt", "k"),
                "hello");
        Assertions.assertThat(outcome).isEmpty();
    }

    @Test
    void skipsWhenProviderNull() {
        runtime.setAgentscopeEnabled(true);
        Optional<AgentScopeChatService.ChatOutcome> outcome = service.chatIfEnabled(runtime, null, "hello");
        Assertions.assertThat(outcome).isEmpty();
    }

    @Test
    void reportsChatException() {
        runtime.setAgentscopeEnabled(true);
        Optional<AgentScopeChatService.ChatOutcome> outcome = service.chatIfEnabled(
                runtime,
                new OpenAiCompatibleChatClient.ResolvedLlmProvider(
                        "openai", "http://127.0.0.1:1/v1", "gpt-4o-mini", "sk-test"),
                "hello");
        Assertions.assertThat(outcome).isPresent();
        assertThat(outcome.get().ok()).isFalse();
    }

    @Test
    void defaultBaseUrlWhenBlank() {
        assertThat(AgentScopeChatService.normalizeBaseUrl(null)).isEqualTo("https://api.openai.com/v1");
        assertThat(AgentScopeChatService.normalizeBaseUrl("  ")).isEqualTo("https://api.openai.com/v1");
    }

    @Test
    void skipsWhenDisabled() {
        runtime.setAgentscopeEnabled(false);
        Optional<AgentScopeChatService.ChatOutcome> outcome = service.chatIfEnabled(
                runtime,
                new OpenAiCompatibleChatClient.ResolvedLlmProvider("openai", "http://x/v1", "gpt", "k"),
                "hello");
        Assertions.assertThat(outcome).isEmpty();
    }

    @Test
    void failsOnEmptyMessage() {
        runtime.setAgentscopeEnabled(true);
        Optional<AgentScopeChatService.ChatOutcome> outcome = service.chatIfEnabled(
                runtime,
                new OpenAiCompatibleChatClient.ResolvedLlmProvider("openai", "http://x/v1", "gpt", "k"),
                " ");
        Assertions.assertThat(outcome).isPresent();
        assertThat(outcome.get().ok()).isFalse();
    }

    @Test
    void buildSysPromptIncludesSubAgentNames() {
        runtime.setBrainAgentName("apm-brain");
        runtime.setDataAgentName("data");
        runtime.setInspectionAgentName("inspection");
        assertThat(AgentScopeChatService.buildSysPrompt(runtime))
                .contains("apm-brain")
                .contains("data")
                .contains("inspection");
    }

    @Test
    void registersSubAgentsOnBrainToolkit() {
        runtime.setDataAgentName("data");
        runtime.setInspectionAgentName("inspection");
        Model model = service.buildModel(
                new OpenAiCompatibleChatClient.ResolvedLlmProvider(
                        "openai", "http://127.0.0.1:9/v1", "gpt-4o-mini", "sk-test"));
        Toolkit toolkit = new Toolkit();
        service.registerSubAgents(toolkit, runtime, model, null);
        assertThat(toolkit.getToolNames()).contains("data", "inspection");
    }

    @Test
    void normalizeBaseUrlTrimsTrailingSlash() {
        assertThat(AgentScopeChatService.normalizeBaseUrl("http://127.0.0.1:11434/v1/"))
                .isEqualTo("http://127.0.0.1:11434/v1");
    }

    @Test
    void loadsSkillsFromDirectory(@TempDir Path skillsRoot) throws Exception {
        Path skillDir = skillsRoot.resolve("agent-data");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: agent-data
                description: test skill
                ---
                body
                """);
        runtime.setCustomSkillsDir(skillsRoot.toString());
        io.agentscope.core.ReActAgent agent = service.buildBrainAgent(
                runtime,
                new OpenAiCompatibleChatClient.ResolvedLlmProvider(
                        "openai", "http://127.0.0.1:9/v1", "gpt-4o-mini", "sk-test"));
        assertThat(agent.getName()).isEqualTo("brain");
        assertThat(agent.getSysPrompt()).contains("data");
    }

    @Test
    void chatOutcomeRecords() {
        assertThat(AgentScopeChatService.ChatOutcome.ok("hi").content()).isEqualTo("hi");
        assertThat(AgentScopeChatService.ChatOutcome.failed("err").error()).isEqualTo("err");
    }
}
