package com.databuff.apm.web.ai.platform.runtime;

import com.databuff.apm.web.ai.TestAiSupport;
import com.databuff.apm.web.ai.UpdateLlmProviderRequest;
import com.databuff.apm.web.ai.platform.expert.ExpertRuntimeOptions;
import com.databuff.apm.web.ai.platform.expert.ExpertType;
import com.databuff.apm.web.ai.platform.expert.AiExpertDefinition;
import com.databuff.apm.web.ai.platform.expert.ExpertManagementService;
import com.databuff.apm.web.ai.tool.ApmToolkit;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.skill.DynamicSkillMiddleware;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentScopeRuntimeAdapterTest {

    @TempDir
    Path tempDir;

    private AgentScopeRuntimeAdapter adapter;
    private ExpertManagementService expertManagementService;
    private TestAiSupport.AiFixture aiFixture;

    @BeforeEach
    void setUp() throws Exception {
        aiFixture = TestAiSupport.aiFixture();
        Path skillsRoot = tempDir.resolve("skills");
        writeSkill(skillsRoot, "skill.brain.routing", "大脑路由", "AI 大脑路由与专家派发规则", "# AI 大脑路由规则");
        writeSkill(skillsRoot, "skill.data.metrics", "问数口径", "APM 指标、Trace 与告警查询规则", "# 智能问数规则");
        writeSkill(skillsRoot, "skill.inspection.health", "巡检流程", "服务健康巡检与异常诊断流程", "# 巡检规则");
        aiFixture.agentRuntimeConfig().setBuiltinSkillsDir(skillsRoot.toString());
        aiFixture.agentRuntimeConfig().setCustomSkillsDir(tempDir.resolve("custom-skills").toString());
        aiFixture.store().updateProvider("openai", new UpdateLlmProviderRequest(
                null, "sk-test", null, true));
        TestAiSupport.PlatformRuntimeFixture fixture =
                aiFixture.buildPlatformRuntime(mock(ApmToolkit.class));
        adapter = fixture.runtimeAdapter();
        expertManagementService = fixture.expertManagementService();
    }

    private static void writeSkill(
            Path skillsRoot, String skillId, String name, String description, String body)
            throws Exception {
        Path dir = skillsRoot.resolve(skillId);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: %s
                ---
                %s
                """.formatted(skillId, description, body));
    }

    @Test
    void buildsDataExpertRuntimeWithEmbeddedSkillBody() {
        AiExpertDefinition dataExpert = expertManagementService.find("data").orElseThrow();
        assertThat(aiFixture.agentRuntimeConfig().layeredSkillRepository().getSkill("skill.data.metrics"))
                .isNotNull();
        ExpertRuntime runtime = adapter.buildRuntime(dataExpert);

        assertThat(runtime.expertId()).isEqualTo("data");
        assertThat(runtime).isInstanceOf(AgentScopeExpertRuntime.class);
        ReActAgent agent = ((AgentScopeExpertRuntime) runtime).agent();
        assertThat(agent.getName()).isEqualTo("data");
        assertThat(agent.getSysPrompt())
                .contains("智能问数")
                .contains("skill.data.metrics")
                .contains("以下 Skill 全文已内嵌")
                .contains("# 智能问数规则")
                .contains("skill.data.metrics 已内嵌在上方系统提示中")
                .doesNotContain("回复前先调用 load_skill_through_path");
        assertThat(agent.getMiddlewares().stream().anyMatch(DynamicSkillMiddleware.class::isInstance)).isFalse();
        assertThat(agent.getToolkit().getToolNames())
                .contains(
                        "getCurrentTimeRange",
                        "getTimeRangeAroundTime",
                        "queryServicesAll",
                        "queryServicesByServiceType",
                        "queryServiceTopology",
                        "queryTraceListByCondition",
                        "queryTraceDetail",
                        "queryServiceAlarms",
                        "queryMetricData");
    }

    @Test
    void buildsBrainWithDispatchToolsAndEmbeddedSkillBody() {
        AiExpertDefinition brainExpert = expertManagementService.find("brain").orElseThrow();
        ExpertRuntime runtime = adapter.buildRuntime(brainExpert);
        ReActAgent agent = ((AgentScopeExpertRuntime) runtime).agent();

        assertThat(agent.getName()).isEqualTo("brain");
        assertThat(agent.getSysPrompt())
                .contains("dispatchExpertTask")
                .contains("skill.brain.routing")
                .contains("# AI 大脑路由规则")
                .contains("skill.brain.routing 已内嵌在上方系统提示中")
                .doesNotContain("回复前先调用 load_skill_through_path");
        assertThat(agent.getMiddlewares().stream().anyMatch(DynamicSkillMiddleware.class::isInstance)).isFalse();
        assertThat(agent.getToolkit().getToolNames())
                .contains("dispatchExpertTask",
                        "listWorkspaceFiles", "readWorkspaceFile", "writeWorkspaceFile", "executeWorkspaceShell")
                .doesNotContain("queryMetricData", "getCurrentTimeRange", "drawTrendCharts");
    }

    @Test
    void sessionScopedBrainEmbedsSkillAndEnablesPendingRecovery() {
        AiExpertDefinition brainExpert = expertManagementService.find("brain").orElseThrow();
        ExpertRuntime runtime = adapter.buildSessionRuntime(brainExpert, "session-scoped-brain");
        ReActAgent agent = ((AgentScopeExpertRuntime) runtime).agent();

        assertThat(agent.getSysPrompt())
                .contains("skill.brain.routing")
                .contains("# AI 大脑路由规则")
                .contains("dispatchExpertTask")
                .doesNotContain("回复前先调用 load_skill_through_path");
        assertThat(agent.getToolkit().getToolNames())
                .contains("dispatchExpertTask");
        assertThat(agent.isPendingToolRecoveryEnabled()).isTrue();
    }

    @Test
    void sessionScopedOpsEnablesPendingToolRecovery() {
        AiExpertDefinition opsExpert = expertManagementService.find("ops").orElseThrow();
        ExpertRuntime runtime = adapter.buildSessionRuntime(opsExpert, "session-scoped-ops");
        ReActAgent agent = ((AgentScopeExpertRuntime) runtime).agent();

        assertThat(agent.getName()).isEqualTo("ops");
        assertThat(agent.isPendingToolRecoveryEnabled()).isTrue();
    }

    @Test
    void brainPromptDiscoversCustomExpertDynamically() {
        Instant now = Instant.now();
        expertManagementService.save(new AiExpertDefinition(
                "sre", "SRE专家", "运维", "处理发布、变更与故障复盘", ExpertType.CUSTOM,
                null, null, "sre prompt", java.util.List.of("data.queryServicesAll"),
                java.util.List.of("skill.data.metrics"),
                ExpertRuntimeOptions.defaults(),
                true, false, 0, now, now));

        AiExpertDefinition brainExpert = expertManagementService.find("brain").orElseThrow();
        ReActAgent agent = ((AgentScopeExpertRuntime) adapter.buildRuntime(brainExpert)).agent();

        assertThat(agent.getSysPrompt())
                .contains("`sre`")
                .contains("处理发布、变更与故障复盘")
                .contains("`data`")
                .contains("`inspection`");
    }
}
