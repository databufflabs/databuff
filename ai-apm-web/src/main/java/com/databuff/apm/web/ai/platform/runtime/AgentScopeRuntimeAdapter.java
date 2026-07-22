package com.databuff.apm.web.ai.platform.runtime;

import com.databuff.apm.web.ai.InMemoryLlmProviderStore;
import com.databuff.apm.web.ai.LlmChatModelFactory;
import com.databuff.apm.web.ai.OpenAiCompatibleChatClient;
import com.databuff.apm.web.ai.agent.AgentRuntimeConfig;
import com.databuff.apm.web.ai.platform.BuiltInExpertCatalog;
import com.databuff.apm.web.ai.platform.expert.AiExpertDefinition;
import com.databuff.apm.web.ai.platform.expert.BrainRoutingCatalog;
import com.databuff.apm.web.ai.platform.expert.ExpertManagementService;
import com.databuff.apm.web.ai.platform.skill.AiSkillDefinition;
import com.databuff.apm.web.ai.platform.skill.SkillManagementService;
import com.databuff.apm.web.ai.platform.tool.AiToolDefinition;
import com.databuff.apm.web.ai.platform.tool.ExpertToolResolver;
import com.databuff.apm.web.ai.platform.tool.ToolManagementService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@Lazy
public class AgentScopeRuntimeAdapter {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeRuntimeAdapter.class);

    private final AgentRuntimeConfig agentRuntimeConfig;
    private final ExpertManagementService expertManagementService;
    private final ToolManagementService toolManagementService;
    private final SkillManagementService skillManagementService;
    private final InMemoryLlmProviderStore llmProviderStore;
    private final AgentScopeToolFactory toolFactory;
    private final ExpertToolResolver expertToolResolver;
    private final AgentScopeSessionHook sessionHook;
    private final SkillFileSyncService skillFileSyncService;
    private final SessionWorkspaceTools sessionWorkspaceTools;
    private final SessionWorkspaceService sessionWorkspaceService;
    private final BrainRoutingCatalog brainRoutingCatalog;
    private final InMemoryAgentStateStore sharedAgentStateStore = new InMemoryAgentStateStore();

    public AgentScopeRuntimeAdapter(
            AgentRuntimeConfig agentRuntimeConfig,
            ExpertManagementService expertManagementService,
            ToolManagementService toolManagementService,
            SkillManagementService skillManagementService,
            InMemoryLlmProviderStore llmProviderStore,
            AgentScopeToolFactory toolFactory,
            ExpertToolResolver expertToolResolver,
            AgentScopeSessionHook sessionHook,
            SkillFileSyncService skillFileSyncService,
            SessionWorkspaceTools sessionWorkspaceTools,
            SessionWorkspaceService sessionWorkspaceService,
            BrainRoutingCatalog brainRoutingCatalog) {
        this.agentRuntimeConfig = agentRuntimeConfig;
        this.expertManagementService = expertManagementService;
        this.toolManagementService = toolManagementService;
        this.skillManagementService = skillManagementService;
        this.llmProviderStore = llmProviderStore;
        this.toolFactory = toolFactory;
        this.expertToolResolver = expertToolResolver;
        this.sessionHook = sessionHook;
        this.skillFileSyncService = skillFileSyncService;
        this.sessionWorkspaceTools = sessionWorkspaceTools;
        this.sessionWorkspaceService = sessionWorkspaceService;
        this.brainRoutingCatalog = brainRoutingCatalog;
    }

    public ExpertRuntime buildRuntime(AiExpertDefinition expert) {
        if (expert == null) {
            throw new IllegalArgumentException("expert is required");
        }
        if (!expert.enabled()) {
            throw new IllegalStateException("expert is disabled: " + expert.expertId());
        }
        OpenAiCompatibleChatClient.ResolvedLlmProvider provider = resolveProvider(expert)
                .orElseThrow(() -> new IllegalStateException("no enabled LLM provider for expert: " + expert.expertId()));

        skillFileSyncService.syncSkillsForExpert(expert.skillIds());
        List<AiToolDefinition> tools = expertToolResolver.resolve(expert);
        List<AiSkillDefinition> skills = resolveSkills(expert.skillIds());
        RuntimeCacheKey cacheKey = RuntimeCacheKey.of(
                expert,
                tools,
                skills,
                provider.providerCode(),
                llmProviderStore.providerVersion(provider.providerCode()),
                routingCatalogHash(expert));

        ReActAgentBuildResult buildResult = buildReActAgent(expert, provider, tools, expert.skillIds(), null);
        return new AgentScopeExpertRuntime(
                expert,
                buildResult.agent(),
                cacheKey,
                Instant.now(),
                sessionHook,
                sessionWorkspaceService,
                buildResult.mcpClients());
    }

    public ExpertRuntime buildSessionRuntime(AiExpertDefinition expert, String chatSessionId) {
        if (expert == null) {
            throw new IllegalArgumentException("expert is required");
        }
        if (chatSessionId == null || chatSessionId.isBlank()) {
            throw new IllegalArgumentException("chatSessionId is required");
        }
        if (!expert.enabled()) {
            throw new IllegalStateException("expert is disabled: " + expert.expertId());
        }
        OpenAiCompatibleChatClient.ResolvedLlmProvider provider = resolveProvider(expert)
                .orElseThrow(() -> new IllegalStateException("no enabled LLM provider for expert: " + expert.expertId()));

        skillFileSyncService.syncSkillsForExpert(expert.skillIds());
        List<AiToolDefinition> tools = expertToolResolver.resolve(expert);
        List<AiSkillDefinition> skills = resolveSkills(expert.skillIds());
        RuntimeCacheKey cacheKey = RuntimeCacheKey.of(
                expert,
                tools,
                skills,
                provider.providerCode(),
                llmProviderStore.providerVersion(provider.providerCode()),
                routingCatalogHash(expert));

        ReActAgentBuildResult buildResult = buildReActAgent(
                expert, provider, tools, expert.skillIds(), chatSessionId.trim());
        return new AgentScopeExpertRuntime(
                expert,
                buildResult.agent(),
                cacheKey,
                Instant.now(),
                sessionHook,
                sessionWorkspaceService,
                buildResult.mcpClients());
    }

    ReActAgentBuildResult buildReActAgent(
            AiExpertDefinition expert,
            OpenAiCompatibleChatClient.ResolvedLlmProvider provider,
            List<AiToolDefinition> tools,
            List<String> skillIds) {
        return buildReActAgent(expert, provider, tools, skillIds, null);
    }

    ReActAgentBuildResult buildReActAgent(
            AiExpertDefinition expert,
            OpenAiCompatibleChatClient.ResolvedLlmProvider provider,
            List<AiToolDefinition> tools,
            List<String> skillIds,
            String chatSessionId) {
        Model model = buildModel(provider, expert);
        Toolkit toolkit = new Toolkit();
        List<McpClientWrapper> mcpClients = toolFactory.registerTools(toolkit, tools);
        toolkit.registerTool(sessionWorkspaceTools);

        EmbedSkillsResult embeddedSkills = embedExpertSkills(resolveSystemPrompt(expert), skillIds);
        ReActAgent.Builder builder = ReActAgent.builder()
                .name(expert.expertId())
                .sysPrompt(embeddedSkills.prompt())
                .model(model)
                .toolkit(toolkit)
                .maxIters(agentRuntimeConfig.resolvedMaxIters())
                .modelExecutionConfig(agentRuntimeConfig.llmModelExecutionConfig())
                .checkRunning(false)
                .permissionContext(AgentScopePermissionSupport.autoAllowContext())
                .middleware(NormalizeToolCallArgsMiddleware.INSTANCE);

        if (chatSessionId != null && !chatSessionId.isBlank()) {
            // Abort/timeout mid-tool leaves ToolUse without ToolResult in stateStore memory.
            // Without recovery, the next call (incl. orchestrator stream→chat fallback) fails with
            // "Pending tool calls exist without results".
            builder.stateStore(sharedAgentStateStore)
                    .defaultSessionId(chatSessionId.trim())
                    .enablePendingToolRecovery(true);
        }

        // When skill bodies are already embedded in sysPrompt (incl. session-scoped brain), skip
        // dynamic load_skill so async continuation does not re-invoke META skill tools every turn.
        if (!embeddedSkills.embedded() && hasSkillRepository(skillIds)) {
            AgentSkillRepository repository = new ExpertScopedSkillRepository(
                    agentRuntimeConfig.layeredSkillRepository(),
                    skillIds);
            builder.skillRepository(repository)
                    .skillFilter(SkillFilter.only(skillIds.toArray(String[]::new)))
                    .dynamicSkillsEnabled(true);
        }
        return new ReActAgentBuildResult(builder.build(), mcpClients);
    }

    private record EmbedSkillsResult(String prompt, boolean embedded) {
    }

    /**
     * Embeds bound SKILL.md bodies into the system prompt. Dynamic {@code load_skill_through_path} is unreliable on
     * the first model turn because AgentScope filters grouped META tools by {@code ToolContextState.activatedGroups},
     * which is only synced after the first call completes.
     */
    EmbedSkillsResult embedExpertSkills(String basePrompt, List<String> skillIds) {
        StringBuilder prompt = new StringBuilder(basePrompt == null ? "" : basePrompt.trim());
        StringBuilder skillBodies = new StringBuilder();
        int embeddedCount = 0;
        if (skillIds != null) {
            for (String skillId : skillIds) {
                var skillOpt = skillManagementService.find(skillId).filter(AiSkillDefinition::enabled);
                if (skillOpt.isPresent() && appendSkillBody(skillBodies, skillOpt.get())) {
                    embeddedCount++;
                }
            }
        }
        if (embeddedCount == 0) {
            if (skillIds != null && !skillIds.isEmpty()) {
                log.warn("No embedded skills for expert; falling back to dynamic load (skillIds={})", skillIds);
            }
            return new EmbedSkillsResult(appendExpertSkillCatalog(prompt.toString(), skillIds), false);
        }
        prompt.append("""


                以下 Skill 全文已内嵌，请直接严格遵循。
                不要调用 load_skill、load_skill_through_path，也不要用 listWorkspaceFiles/readWorkspaceFile/executeWorkspaceShell 查找 Skill 文件。
                """);
        prompt.append(skillBodies);
        return new EmbedSkillsResult(replaceDynamicSkillLoadInstructions(prompt.toString()), true);
    }

    private static String replaceDynamicSkillLoadInstructions(String prompt) {
        return prompt
                .replace(
                        "回复前先调用 load_skill_through_path(skillId=\"skill.brain.routing\", path=\"SKILL.md\") 加载路由规则，再执行任何操作。",
                        "skill.brain.routing 已内嵌在上方系统提示中，请直接遵循。")
                .replace(
                        "回复前先调用 load_skill_through_path(skillId=\"skill.data.metrics\", path=\"SKILL.md\") 加载问数规则，再选择工具和填写参数。",
                        "skill.data.metrics 已内嵌在上方系统提示中，请直接遵循。")
                .replace(
                        "回复前先调用 load_skill_through_path(skillId=\"skill.inspection.health\", path=\"SKILL.md\") 加载巡检流程，再执行巡检和补充查询。",
                        "skill.inspection.health 已内嵌在上方系统提示中，请直接遵循。")
                .replace(
                        "回复前先调用 load_skill_through_path(skillId=\"skill.qa.product\", path=\"SKILL.md\") 加载答疑规则，再开始检索。",
                        "skill.qa.product 已内嵌在上方系统提示中，请直接遵循。");
    }

    private boolean appendSkillBody(StringBuilder prompt, AiSkillDefinition skill) {
        try {
            String body = skillFileSyncService.readSkillContent(skill);
            if (body == null || body.isBlank()) {
                log.warn("Expert skill {} has no readable content", skill.skillId());
                return false;
            }
            prompt.append("\n## Skill: ").append(skill.skillId()).append("\n\n").append(body.trim()).append('\n');
            return true;
        } catch (Exception e) {
            log.warn("Failed to embed expert skill {}: {}", skill.skillId(), e.getMessage());
            return false;
        }
    }

    /**
     * Fallback catalog when skill bodies cannot be embedded on disk. Full bodies should be embedded via
     * {@link #embedExpertSkills(String, List)} under normal operation.
     */
    String appendExpertSkillCatalog(String basePrompt, List<String> skillIds) {
        StringBuilder prompt = new StringBuilder(basePrompt == null ? "" : basePrompt.trim());
        if (skillIds == null || skillIds.isEmpty()) {
            return prompt.toString();
        }
        List<AiSkillDefinition> skills = resolveSkills(skillIds);
        if (skills.isEmpty()) {
            return prompt.toString();
        }
        prompt.append("""


                ## 本专家可用 Skill（元信息）
                系统还会在推理时注入 AgentScope 的 available_skills 块。执行任务前，请用 load_skill_through_path(skillId, path=\"SKILL.md\")
                加载需要的 Skill 正文后再行动；不要用 listWorkspaceFiles/readWorkspaceFile/executeWorkspaceShell 查找 Skill 文件。
                """);
        for (AiSkillDefinition skill : skills) {
            prompt.append("- `").append(skill.skillId()).append("`");
            if (skill.name() != null && !skill.name().isBlank()) {
                prompt.append(" — ").append(skill.name().trim());
            }
            prompt.append('\n');
            if (skill.description() != null && !skill.description().isBlank()) {
                prompt.append("  说明：").append(skill.description().trim()).append('\n');
            }
        }
        return prompt.toString().trim();
    }

    record ReActAgentBuildResult(ReActAgent agent, List<McpClientWrapper> mcpClients) {
    }

    public java.util.Optional<OpenAiCompatibleChatClient.ResolvedLlmProvider> resolveProvider(
            AiExpertDefinition expert) {
        if (expert.modelProviderCode() != null && !expert.modelProviderCode().isBlank()) {
            return llmProviderStore.resolveProvider(expert.modelProviderCode());
        }
        return llmProviderStore.firstEnabledProvider();
    }

    static String brainSystemPrompt() {
        return BuiltInExpertCatalog.brainPrompt();
    }

    private String resolveSystemPrompt(AiExpertDefinition expert) {
        if ("brain".equals(expert.expertId())) {
            return brainRoutingCatalog.resolveBrainSystemPrompt(expert);
        }
        return expert.systemPrompt();
    }

    private String routingCatalogHash(AiExpertDefinition expert) {
        if (!"brain".equals(expert.expertId())) {
            return "";
        }
        return brainRoutingCatalog.routableExpertsFingerprint();
    }

    private boolean hasSkillRepository(List<String> skillIds) {
        if (skillIds == null || skillIds.isEmpty() || agentRuntimeConfig.skillSearchDirectories().isEmpty()) {
            return false;
        }
        try {
            AgentSkillRepository repository = agentRuntimeConfig.layeredSkillRepository();
            for (String skillId : skillIds) {
                if (repository.getSkill(skillId) == null) {
                    log.warn("Skill {} not found under {}", skillId, agentRuntimeConfig.skillSearchDirectories());
                }
            }
            return true;
        } catch (Exception e) {
            log.warn("Failed to inspect skills: {}", e.getMessage());
            return false;
        }
    }

    private List<AiSkillDefinition> resolveSkills(List<String> skillIds) {
        List<AiSkillDefinition> skills = new ArrayList<>();
        for (String skillId : skillIds) {
            skillManagementService.find(skillId)
                    .filter(AiSkillDefinition::enabled)
                    .ifPresent(skills::add);
        }
        return skills;
    }

    Model buildModel(
            OpenAiCompatibleChatClient.ResolvedLlmProvider provider,
            AiExpertDefinition expert) {
        // Always non-streaming: complete LLM responses avoid delta merge issues (e.g. markdown boundaries).
        return LlmChatModelFactory.build(provider, expert.modelName(), false);
    }

    static String normalizeBaseUrl(String baseUrl) {
        return LlmChatModelFactory.normalizeBaseUrl(baseUrl);
    }
}
