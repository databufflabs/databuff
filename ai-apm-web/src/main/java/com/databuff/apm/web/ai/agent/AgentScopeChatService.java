package com.databuff.apm.web.ai.agent;

import com.databuff.apm.web.ai.OpenAiCompatibleChatClient;
import com.databuff.apm.web.tools.local.DataTools;
import com.databuff.apm.web.tools.local.TimeTool;
import com.databuff.apm.web.ai.platform.runtime.AgentScopePermissionSupport;
import com.databuff.apm.web.ai.platform.runtime.ExpertScopedSkillRepository;
import com.databuff.apm.web.ai.platform.runtime.NormalizeToolCallArgsMiddleware;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import com.databuff.apm.web.ai.LlmChatModelFactory;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.subagent.SubAgentConfig;
import io.agentscope.core.tool.subagent.SubAgentTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@Lazy
public class AgentScopeChatService {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeChatService.class);

    private final DataTools dataTools;
    private final TimeTool timeTool;

    public AgentScopeChatService(DataTools dataTools, TimeTool timeTool) {
        this.dataTools = dataTools;
        this.timeTool = timeTool;
    }

    public Optional<ChatOutcome> chatIfEnabled(
            AgentRuntimeConfig runtime,
            OpenAiCompatibleChatClient.ResolvedLlmProvider provider,
            String message) {
        if (runtime == null || !runtime.isAgentscopeEnabled() || provider == null) {
            return Optional.empty();
        }
        if (message == null || message.isBlank()) {
            return Optional.of(ChatOutcome.failed("message is empty"));
        }
        try {
            ReActAgent agent = buildBrainAgent(runtime, provider);
            Msg response = agent.call(Msg.builder().textContent(message.trim()).build())
                    .block();
            if (response == null || response.getTextContent() == null || response.getTextContent().isBlank()) {
                return Optional.of(ChatOutcome.failed("empty AgentScope response"));
            }
            return Optional.of(ChatOutcome.ok(response.getTextContent()));
        } catch (Exception e) {
            log.warn("AgentScope chat failed: {}", e.getMessage());
            return Optional.of(ChatOutcome.failed(
                    e.getMessage() == null ? "AgentScope chat failed" : e.getMessage()));
        }
    }

    ReActAgent buildBrainAgent(
            AgentRuntimeConfig runtime,
            OpenAiCompatibleChatClient.ResolvedLlmProvider provider) {
        Model model = buildModel(provider);
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(dataTools);
        toolkit.registerTool(timeTool);
        registerSubAgents(toolkit, runtime, model);

        ReActAgent.Builder builder = ReActAgent.builder()
                .name(runtime.getBrainAgentName())
                .sysPrompt(buildSysPrompt(runtime))
                .model(model)
                .toolkit(toolkit)
                .maxIters(runtime.resolvedMaxIters())
                .modelExecutionConfig(runtime.llmModelExecutionConfig())
                .checkRunning(false)
                .permissionContext(AgentScopePermissionSupport.autoAllowContext())
                .middleware(NormalizeToolCallArgsMiddleware.INSTANCE);

        if (hasFilesystemSkills(runtime)) {
            builder.skillRepository(runtime.layeredSkillRepository());
        }
        return builder.build();
    }

    void registerSubAgents(
            Toolkit toolkit,
            AgentRuntimeConfig runtime,
            Model model) {
        toolkit.registerAgentTool(new SubAgentTool(
                () -> buildSubAgent(
                        runtime.getDataAgentName(),
                        model,
                        buildDataPrompt(),
                        runtime,
                        "skill.data.metrics"),
                SubAgentConfig.builder()
                        .toolName(runtime.getDataAgentName())
                        .description("APM 问数：metrics / trace / error rate queries")
                        .build()));
        toolkit.registerAgentTool(new SubAgentTool(
                () -> buildSubAgent(
                        runtime.getInspectionAgentName(),
                        model,
                        buildInspectionPrompt(),
                        runtime,
                        "skill.inspection.health"),
                SubAgentConfig.builder()
                        .toolName(runtime.getInspectionAgentName())
                        .description("APM 巡检：health inspection and anomaly triage")
                        .build()));
    }

    ReActAgent buildSubAgent(
            String name,
            Model model,
            String sysPrompt,
            AgentRuntimeConfig runtime,
            String skillId) {
        Toolkit subToolkit = new Toolkit();
        subToolkit.registerTool(dataTools);
        subToolkit.registerTool(timeTool);

        ReActAgent.Builder builder = ReActAgent.builder()
                .name(name)
                .sysPrompt(sysPrompt)
                .model(model)
                .toolkit(subToolkit)
                .maxIters(runtime.resolvedMaxIters())
                .modelExecutionConfig(runtime.llmModelExecutionConfig())
                .checkRunning(false)
                .permissionContext(AgentScopePermissionSupport.autoAllowContext())
                .middleware(NormalizeToolCallArgsMiddleware.INSTANCE);
        if (hasSkill(runtime, skillId)) {
            AgentSkillRepository repository = new ExpertScopedSkillRepository(
                    runtime.layeredSkillRepository(),
                    List.of(skillId));
            builder.skillRepository(repository)
                    .skillFilter(SkillFilter.only(skillId))
                    .dynamicSkillsEnabled(true);
        }
        return builder.build();
    }

    Model buildModel(OpenAiCompatibleChatClient.ResolvedLlmProvider provider) {
        return LlmChatModelFactory.build(provider, provider.defaultModel(), false);
    }

    static String buildSysPrompt(AgentRuntimeConfig runtime) {
        return """
                You are the DataBuff APM brain agent ("%s").
                Delegate when helpful:
                - "%s" for metrics / trace / error rate questions
                - "%s" for health inspection and anomaly triage
                You may also call APM tools directly. Prefer tool results over guessing.
                Answer concisely in the user's language.
                """.formatted(
                runtime.getBrainAgentName(),
                runtime.getDataAgentName(),
                runtime.getInspectionAgentName());
    }

    static String buildDataPrompt() {
        return """
                You are the DataBuff APM data sub-agent.
                Use APM tools and skill "agent-data" to answer metrics and trace questions.
                """;
    }

    static String buildInspectionPrompt() {
        return """
                You are the DataBuff APM inspection sub-agent.
                Use APM tools and skill "agent-inspection" to triage service health.
                """;
    }

    static boolean hasFilesystemSkills(AgentRuntimeConfig runtime) {
        if (runtime == null) {
            return false;
        }
        try {
            AgentSkillRepository repository = runtime.layeredSkillRepository();
            int size = repository.getAllSkillNames().size();
            log.info("Found {} AgentScope skills from {}", size, runtime.skillSearchDirectories());
            return size > 0;
        } catch (Exception e) {
            log.warn("Failed to inspect skills: {}", e.getMessage());
            return false;
        }
    }

    static boolean hasSkill(AgentRuntimeConfig runtime, String skillId) {
        if (runtime == null || skillId == null || skillId.isBlank()) {
            return false;
        }
        try {
            return runtime.layeredSkillRepository().getSkill(skillId) != null;
        } catch (Exception e) {
            log.warn("Failed to inspect skill {}: {}", skillId, e.getMessage());
            return false;
        }
    }

    static String normalizeBaseUrl(String baseUrl) {
        return LlmChatModelFactory.normalizeBaseUrl(baseUrl);
    }

    public record ChatOutcome(boolean ok, String content, String error) {
        public static ChatOutcome ok(String content) {
            return new ChatOutcome(true, content, null);
        }

        public static ChatOutcome failed(String error) {
            return new ChatOutcome(false, null, error);
        }
    }
}
