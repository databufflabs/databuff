package com.databuff.apm.web.ai.platform.runtime;

import com.databuff.apm.web.ai.TestAiSupport;
import com.databuff.apm.web.ai.UpdateLlmProviderRequest;
import com.databuff.apm.web.ai.platform.expert.AiExpertDefinition;
import com.databuff.apm.web.ai.platform.expert.ExpertManagementService;
import com.databuff.apm.web.ai.tool.ApmToolkit;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Integration coverage for conditional AgentScope memory isolation:
 * brain / dispatched subtasks isolate by expert; direct non-brain chats share.
 */
class ExpertMemoryIsolationIntegrationTest {

    private static final String USER = "admin";
    private static final String STATE_KEY = "agent_state";

    @TempDir
    Path tempDir;

    private InMemoryAgentStateStore sharedStore;
    private SessionExpertRuntimeRegistry sessionRegistry;
    private ExpertManagementService experts;

    @BeforeEach
    void setUp() throws Exception {
        TestAiSupport.AiFixture aiFixture = TestAiSupport.aiFixture();
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
        sharedStore = fixture.runtimeAdapter().sharedAgentStateStore();
        sessionRegistry = fixture.sessionExpertRuntimeRegistry();
        experts = fixture.expertManagementService();
    }

    @AfterEach
    void tearDown() {
        ExpertChatScopeRegistry.clearForTests();
        if (sessionRegistry != null) {
            sessionRegistry.releaseAll();
        }
    }

    @Test
    void caseA_brainParallelDispatch_expertsDoNotShareMemory() {
        String sessionId = "mem-a";
        registerBrainParent(sessionId);
        ReActAgent inspection = agentFor(sessionId, "inspection");
        ReActAgent data = agentFor(sessionId, "data");

        registerDispatch(sessionId, "inspection", "task-insp");
        saveSecret(inspection, sessionId, "蓝莓派");

        registerDispatch(sessionId, "data", "task-data");
        saveSecret(data, sessionId, "芒果冰");

        assertThat(loadSecret(inspection, sessionId)).contains("蓝莓派");
        assertThat(loadSecret(data, sessionId)).contains("芒果冰");
        assertThat(loadSecret(data, sessionId)).doesNotContain("蓝莓派");
        assertThat(loadSecret(inspection, sessionId)).doesNotContain("芒果冰");

        assertThat(sharedStore.exists(USER, sessionId + "#expert:inspection")).isTrue();
        assertThat(sharedStore.exists(USER, sessionId + "#expert:data")).isTrue();
        assertThat(sharedStore.exists(USER, sessionId)).isFalse();
    }

    @Test
    void caseB_directExpertSwitch_sharesMemory() {
        String sessionId = "mem-b";
        ReActAgent inspection = agentFor(sessionId, "inspection");
        ReActAgent data = agentFor(sessionId, "data");

        registerDirect(sessionId, "inspection");
        saveSecret(inspection, sessionId, "service-a 延迟升高 · 蓝莓派");

        ExpertChatScopeRegistry.unregister(sessionId);
        registerDirect(sessionId, "data");

        assertThat(loadSecret(data, sessionId)).contains("蓝莓派");
        assertThat(sharedStore.exists(USER, sessionId)).isTrue();
        assertThat(sharedStore.exists(USER, sessionId + "#expert:inspection")).isFalse();
        assertThat(sharedStore.exists(USER, sessionId + "#expert:data")).isFalse();
    }

    @Test
    void caseC_brainMemoryDoesNotLeakToDirectExpert() {
        String sessionId = "mem-c";
        ReActAgent brain = agentFor(sessionId, "brain");
        ReActAgent data = agentFor(sessionId, "data");

        registerDirect(sessionId, "brain");
        saveSecret(brain, sessionId, "编排密钥");

        ExpertChatScopeRegistry.unregister(sessionId);
        registerDirect(sessionId, "data");

        assertThat(loadSecret(data, sessionId)).isEmpty();
        assertThat(sharedStore.exists(USER, sessionId + "#expert:brain")).isTrue();
        assertThat(sharedStore.exists(USER, sessionId)).isFalse();
    }

    @Test
    void caseD_sameExpertSerialDispatches_reuseExpertSlot() {
        String sessionId = "mem-d";
        registerBrainParent(sessionId);
        ReActAgent data = agentFor(sessionId, "data");

        registerDispatch(sessionId, "data", "task-1");
        saveSecret(data, sessionId, "芒果冰");
        ExpertChatScopeRegistry.unregister(
                ExpertChatScopeRegistry.taskScopedSessionId(sessionId, "task-1"));

        registerDispatch(sessionId, "data", "task-2");
        assertThat(loadSecret(data, sessionId)).contains("芒果冰");
        assertThat(sharedStore.exists(USER, sessionId + "#expert:data")).isTrue();
        assertThat(sharedStore.exists(USER, sessionId)).isFalse();
    }

    @Test
    void caseE_asyncDispatchStillIsolatesAfterBrainScopeEnds() {
        String sessionId = "mem-e";
        registerBrainParent(sessionId);
        ReActAgent inspection = agentFor(sessionId, "inspection");
        ReActAgent data = agentFor(sessionId, "data");

        registerDispatch(sessionId, "inspection", "task-insp");
        registerDispatch(sessionId, "data", "task-data");
        // Brain SSE finished; parent scope gone while subtasks still run.
        ExpertChatScopeRegistry.unregister(sessionId);

        saveSecret(inspection, sessionId, "蓝莓派");
        saveSecret(data, sessionId, "芒果冰");

        assertThat(loadSecret(inspection, sessionId)).contains("蓝莓派");
        assertThat(loadSecret(data, sessionId)).contains("芒果冰");
        assertThat(loadSecret(data, sessionId)).doesNotContain("蓝莓派");
        assertThat(sharedStore.exists(USER, sessionId + "#expert:inspection")).isTrue();
        assertThat(sharedStore.exists(USER, sessionId + "#expert:data")).isTrue();
        assertThat(sharedStore.exists(USER, sessionId)).isFalse();
    }

    @Test
    void caseF_dispatchSlotDoesNotFeedLaterDirectChat() {
        String sessionId = "mem-f";
        registerBrainParent(sessionId);
        ReActAgent data = agentFor(sessionId, "data");

        registerDispatch(sessionId, "data", "task-f");
        saveSecret(data, sessionId, "派发暗号");
        ExpertChatScopeRegistry.unregister(
                ExpertChatScopeRegistry.taskScopedSessionId(sessionId, "task-f"));
        ExpertChatScopeRegistry.unregister(sessionId);

        registerDirect(sessionId, "data");
        assertThat(loadSecret(data, sessionId)).isEmpty();
        assertThat(sharedStore.exists(USER, sessionId + "#expert:data")).isTrue();
        assertThat(sharedStore.exists(USER, sessionId)).isFalse();
    }

    private ReActAgent agentFor(String sessionId, String expertId) {
        AiExpertDefinition expert = experts.find(expertId).orElseThrow();
        ExpertRuntime runtime = sessionRegistry.getOrCreate(sessionId, expert);
        assertThat(runtime).isInstanceOf(AgentScopeExpertRuntime.class);
        ReActAgent agent = ((AgentScopeExpertRuntime) runtime).agent();
        assertThat(agent.getStateStore()).isInstanceOf(ExpertIsolatedAgentStateStore.class);
        return agent;
    }

    private static void registerBrainParent(String sessionId) {
        ExpertChatScopeRegistry.register(new ExpertChatContext.State(
                sessionId, USER, "brain", null, false, null));
    }

    private static void registerDirect(String sessionId, String expertId) {
        ExpertChatScopeRegistry.register(new ExpertChatContext.State(
                sessionId, USER, expertId, "asst-1", false, null));
    }

    private static void registerDispatch(String sessionId, String expertId, String taskId) {
        String scoped = ExpertChatScopeRegistry.taskScopedSessionId(sessionId, taskId);
        ExpertChatScopeRegistry.register(new ExpertChatContext.State(
                scoped, USER, expertId, null, true, null, taskId));
    }

    private static void saveSecret(ReActAgent agent, String sessionId, String secret) {
        AgentStateStore store = agent.getStateStore();
        AgentState state = AgentState.builder()
                .sessionId(sessionId)
                .userId(USER)
                .build();
        state.contextMutable().add(Msg.builder()
                .role(MsgRole.USER)
                .textContent(secret)
                .build());
        store.save(USER, sessionId, STATE_KEY, state);
    }

    private static String loadSecret(ReActAgent agent, String sessionId) {
        Optional<AgentState> loaded =
                agent.getStateStore().get(USER, sessionId, STATE_KEY, AgentState.class);
        if (loaded.isEmpty() || loaded.get().getContext() == null || loaded.get().getContext().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Msg msg : loaded.get().getContext()) {
            if (msg.getTextContent() != null) {
                sb.append(msg.getTextContent());
            }
        }
        return sb.toString();
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
}
