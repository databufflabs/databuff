package com.databuff.apm.web.ai.platform.runtime;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ExpertIsolatedAgentStateStoreTest {

    @AfterEach
    void tearDown() {
        ExpertChatScopeRegistry.clearForTests();
    }

    @Test
    void scopedSessionIdAppendsExpertSuffix() {
        assertThat(ExpertIsolatedAgentStateStore.scopedSessionId("chat-1", "data"))
                .isEqualTo("chat-1#expert:data");
    }

    @Test
    void dispatchedExpertsDoNotShareAgentState() {
        InMemoryAgentStateStore shared = new InMemoryAgentStateStore();
        ExpertIsolatedAgentStateStore inspection =
                new ExpertIsolatedAgentStateStore(shared, "inspection");
        ExpertIsolatedAgentStateStore data = new ExpertIsolatedAgentStateStore(shared, "data");

        ExpertChatScopeRegistry.register(new ExpertChatContext.State(
                "chat-1", "admin", "brain", null, false, null));
        ExpertChatScopeRegistry.register(new ExpertChatContext.State(
                ExpertChatScopeRegistry.taskScopedSessionId("chat-1", "task-insp"),
                "admin",
                "inspection",
                null,
                true,
                null,
                "task-insp"));
        ExpertChatScopeRegistry.register(new ExpertChatContext.State(
                ExpertChatScopeRegistry.taskScopedSessionId("chat-1", "task-data"),
                "admin",
                "data",
                null,
                true,
                null,
                "task-data"));

        AgentState inspectionState = AgentState.builder()
                .sessionId("chat-1")
                .userId("admin")
                .build();
        inspectionState.contextMutable().add(Msg.builder()
                .role(MsgRole.USER)
                .textContent("记住暗号蓝莓派")
                .build());
        inspection.save("admin", "chat-1", "agent_state", inspectionState);

        Optional<AgentState> loadedByData = data.get("admin", "chat-1", "agent_state", AgentState.class);
        assertThat(loadedByData).isEmpty();

        Optional<AgentState> loadedByInspection =
                inspection.get("admin", "chat-1", "agent_state", AgentState.class);
        assertThat(loadedByInspection).isPresent();
        assertThat(loadedByInspection.get().getContext()).hasSize(1);
        assertThat(loadedByInspection.get().getContext().get(0).getTextContent())
                .contains("蓝莓派");
        assertThat(shared.exists("admin", "chat-1#expert:inspection")).isTrue();
        assertThat(shared.exists("admin", "chat-1")).isFalse();
    }

    @Test
    void directExpertChatsShareAgentStateAcrossExperts() {
        InMemoryAgentStateStore shared = new InMemoryAgentStateStore();
        ExpertIsolatedAgentStateStore inspection =
                new ExpertIsolatedAgentStateStore(shared, "inspection");
        ExpertIsolatedAgentStateStore data = new ExpertIsolatedAgentStateStore(shared, "data");

        ExpertChatScopeRegistry.register(new ExpertChatContext.State(
                "chat-direct", "admin", "inspection", "asst-1", false, null));

        AgentState inspectionState = AgentState.builder()
                .sessionId("chat-direct")
                .userId("admin")
                .build();
        inspectionState.contextMutable().add(Msg.builder()
                .role(MsgRole.USER)
                .textContent("service-a 延迟升高")
                .build());
        inspection.save("admin", "chat-direct", "agent_state", inspectionState);

        ExpertChatScopeRegistry.unregister("chat-direct");
        ExpertChatScopeRegistry.register(new ExpertChatContext.State(
                "chat-direct", "admin", "data", "asst-2", false, null));

        Optional<AgentState> loadedByData =
                data.get("admin", "chat-direct", "agent_state", AgentState.class);
        assertThat(loadedByData).isPresent();
        assertThat(loadedByData.get().getContext().get(0).getTextContent()).contains("service-a");
        assertThat(shared.exists("admin", "chat-direct")).isTrue();
        assertThat(shared.exists("admin", "chat-direct#expert:inspection")).isFalse();
        assertThat(shared.exists("admin", "chat-direct#expert:data")).isFalse();
    }

    @Test
    void brainAlwaysUsesIsolatedSlotEvenWithoutTask() {
        InMemoryAgentStateStore shared = new InMemoryAgentStateStore();
        ExpertIsolatedAgentStateStore brain = new ExpertIsolatedAgentStateStore(shared, "brain");
        ExpertIsolatedAgentStateStore data = new ExpertIsolatedAgentStateStore(shared, "data");

        ExpertChatScopeRegistry.register(new ExpertChatContext.State(
                "chat-brain", "admin", "brain", null, false, null));

        AgentState brainState = AgentState.builder()
                .sessionId("chat-brain")
                .userId("admin")
                .build();
        brainState.contextMutable().add(Msg.builder()
                .role(MsgRole.USER)
                .textContent("大脑编排记忆")
                .build());
        brain.save("admin", "chat-brain", "agent_state", brainState);

        ExpertChatScopeRegistry.unregister("chat-brain");
        ExpertChatScopeRegistry.register(new ExpertChatContext.State(
                "chat-brain", "admin", "data", null, false, null));

        assertThat(data.get("admin", "chat-brain", "agent_state", AgentState.class)).isEmpty();
        assertThat(shared.exists("admin", "chat-brain#expert:brain")).isTrue();
        assertThat(shared.exists("admin", "chat-brain")).isFalse();
    }

    @Test
    void sameExpertReusesMemoryAcrossSerialDispatches() {
        InMemoryAgentStateStore shared = new InMemoryAgentStateStore();
        ExpertIsolatedAgentStateStore first = new ExpertIsolatedAgentStateStore(shared, "data");
        ExpertIsolatedAgentStateStore second = new ExpertIsolatedAgentStateStore(shared, "data");

        ExpertChatScopeRegistry.register(new ExpertChatContext.State(
                ExpertChatScopeRegistry.taskScopedSessionId("chat-2", "task-1"),
                "admin",
                "data",
                null,
                true,
                null,
                "task-1"));

        AgentState state = AgentState.builder()
                .sessionId("chat-2")
                .userId("admin")
                .build();
        state.contextMutable().add(Msg.builder()
                .role(MsgRole.USER)
                .textContent("芒果冰")
                .build());
        first.save("admin", "chat-2", "agent_state", state);

        ExpertChatScopeRegistry.unregister(
                ExpertChatScopeRegistry.taskScopedSessionId("chat-2", "task-1"));
        ExpertChatScopeRegistry.register(new ExpertChatContext.State(
                ExpertChatScopeRegistry.taskScopedSessionId("chat-2", "task-2"),
                "admin",
                "data",
                null,
                true,
                null,
                "task-2"));

        Optional<AgentState> loaded = second.get("admin", "chat-2", "agent_state", AgentState.class);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getContext().get(0).getTextContent()).contains("芒果冰");
        assertThat(shared.exists("admin", "chat-2#expert:data")).isTrue();
        assertThat(shared.exists("admin", "chat-2")).isFalse();
    }

    @Test
    void listSessionIdsShowsExpertScopedSlotsForDispatches() {
        InMemoryAgentStateStore shared = new InMemoryAgentStateStore();
        ExpertIsolatedAgentStateStore data = new ExpertIsolatedAgentStateStore(shared, "data");
        ExpertChatScopeRegistry.register(new ExpertChatContext.State(
                ExpertChatScopeRegistry.taskScopedSessionId("chat-3", "task-3"),
                "admin",
                "data",
                null,
                true,
                null,
                "task-3"));
        AgentState state = AgentState.builder().sessionId("chat-3").userId("admin").build();
        data.save("admin", "chat-3", "agent_state", state);

        assertThat(data.listSessionIds("admin")).contains("chat-3#expert:data");
        assertThat(shared.exists("admin", "chat-3")).isFalse();
    }

    @Test
    void shouldIsolateBrainAlwaysAndTasksOnlyWhenActive() {
        assertThat(ExpertIsolatedAgentStateStore.shouldIsolate("chat-x", "brain")).isTrue();
        assertThat(ExpertIsolatedAgentStateStore.shouldIsolate("chat-x", "data")).isFalse();

        ExpertChatScopeRegistry.register(new ExpertChatContext.State(
                ExpertChatScopeRegistry.taskScopedSessionId("chat-x", "t-1"),
                "admin",
                "data",
                null,
                true,
                null,
                "t-1"));
        assertThat(ExpertIsolatedAgentStateStore.shouldIsolate("chat-x", "data")).isTrue();
        assertThat(ExpertIsolatedAgentStateStore.shouldIsolate("chat-x", "inspection")).isFalse();
    }
}
