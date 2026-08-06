package com.databuff.apm.ingest.cluster;

import com.databuff.apm.common.cluster.coordination.ClusterInstanceCoordinator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClusterStatsSamplerTest {

    @Test
    void sampleRecordsMembershipLeadershipAndEffectiveness() {
        ClusterInstanceCoordinator coordinator = mock(ClusterInstanceCoordinator.class);
        when(coordinator.sortedMembers()).thenReturn(List.of("n1", "n2"));
        when(coordinator.effectiveClusterEnabled()).thenReturn(true);
        when(coordinator.isLeader()).thenReturn(true);

        ClusterStatsSampler sampler = new ClusterStatsSampler(coordinator);
        sampler.sample();
    }

    @Test
    void sampleRecordsStandaloneSingleNodeState() {
        ClusterInstanceCoordinator coordinator = mock(ClusterInstanceCoordinator.class);
        when(coordinator.sortedMembers()).thenReturn(List.of("n1"));
        when(coordinator.effectiveClusterEnabled()).thenReturn(false);
        when(coordinator.isLeader()).thenReturn(true);

        ClusterStatsSampler sampler = new ClusterStatsSampler(coordinator);
        sampler.sample();
    }
}
