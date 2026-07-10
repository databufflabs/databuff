package com.databuff.apm.web.ai.agent;

import com.databuff.apm.common.cluster.coordination.ClusterPartitionRouter;
import com.databuff.apm.common.cluster.coordination.ClusterInstanceCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiRuntimeRouterTest {

    @Test
    void prefersSessionRouteKey() {
        AgentBrainService.ChatRequest request = new AgentBrainService.ChatRequest(
                "s-1", "data", "hello", false, java.util.Map.of(), "req-1");
        assertThat(AiRuntimeRouter.routeKey(request, "s-1", "data", "admin"))
                .isEqualTo("session:s-1");
    }

    @Test
    void usesUserExpertWhenNoSession() {
        AgentBrainService.ChatRequest request = new AgentBrainService.ChatRequest(
                null, "data", "hello", false, java.util.Map.of(), "req-1");
        assertThat(AiRuntimeRouter.routeKey(request, null, "data", "admin"))
                .isEqualTo("user:admin:expert:data");
    }

    @Test
    void localOwnerWhenClusterDisabled() {
        ClusterInstanceCoordinator coordinator = standaloneCoordinator("web-1");
        AiRuntimeRouter router = new AiRuntimeRouter(coordinator, false);
        AgentBrainService.ChatRequest request = new AgentBrainService.ChatRequest(null, "help");
        AiRuntimeRouter.RouteDecision decision = router.route(request, "s-1", "brain", "admin");
        assertThat(decision.localOwner()).isTrue();
        assertThat(decision.ownerNodeId()).isEqualTo("web-1");
    }

    @Test
    void choosesStableOwnerWhenClusterEnabled() {
        ClusterInstanceCoordinator coordinator = clusteredCoordinator(
                "web-1",
                List.of("web-1", "web-2"),
                Map.of("web-1", "127.0.0.1:8080", "web-2", "127.0.0.1:8081"));
        AiRuntimeRouter router = new AiRuntimeRouter(coordinator, true);
        AgentBrainService.ChatRequest request = new AgentBrainService.ChatRequest(null, "help");
        AiRuntimeRouter.RouteDecision first = router.route(request, "s-42", "brain", "admin");
        AiRuntimeRouter.RouteDecision second = router.route(request, "s-42", "brain", "admin");
        assertThat(first.ownerNodeId()).isEqualTo(second.ownerNodeId());
    }

    @Test
    void resolvesConfiguredEndpoint() {
        ClusterInstanceCoordinator coordinator = clusteredCoordinator(
                "web-1",
                List.of("web-1", "web-2"),
                Map.of("web-2", "127.0.0.1:8081"));
        AiRuntimeRouter router = new AiRuntimeRouter(coordinator, true);
        assertThat(router.endpoint("web-2")).contains("127.0.0.1:8081");
    }

    private static ClusterInstanceCoordinator standaloneCoordinator(String localNodeId) {
        ClusterInstanceCoordinator coordinator = mock(ClusterInstanceCoordinator.class);
        when(coordinator.localNodeId()).thenReturn(localNodeId);
        when(coordinator.effectiveClusterEnabled()).thenReturn(false);
        when(coordinator.sortedMembers()).thenReturn(List.of(localNodeId));
        when(coordinator.endpointsByNodeId()).thenReturn(Map.of());
        when(coordinator.partitionOwner(org.mockito.ArgumentMatchers.anyString())).thenReturn(localNodeId);
        when(coordinator.endpointFor(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        return coordinator;
    }

    private static ClusterInstanceCoordinator clusteredCoordinator(
            String localNodeId,
            List<String> members,
            Map<String, String> endpoints) {
        ClusterInstanceCoordinator coordinator = mock(ClusterInstanceCoordinator.class);
        when(coordinator.localNodeId()).thenReturn(localNodeId);
        when(coordinator.effectiveClusterEnabled()).thenReturn(true);
        when(coordinator.sortedMembers()).thenReturn(members);
        when(coordinator.endpointsByNodeId()).thenReturn(endpoints);
        when(coordinator.endpointFor(org.mockito.ArgumentMatchers.eq("web-2")))
                .thenReturn(Optional.ofNullable(endpoints.get("web-2")));
        when(coordinator.partitionOwner(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0);
                    return ClusterPartitionRouter.chooseOwner(
                            key, members);
                });
        return coordinator;
    }
}
