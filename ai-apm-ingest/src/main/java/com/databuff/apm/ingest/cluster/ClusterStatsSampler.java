package com.databuff.apm.ingest.cluster;

import com.databuff.apm.common.cluster.coordination.ClusterInstanceCoordinator;
import com.databuff.apm.common.platform.PlatformMetricNames;
import com.databuff.apm.common.platform.PlatformMetrics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Samples ingest cluster membership / leadership into {@link PlatformMetrics}.
 * Forward req/fail/cost are recorded at the call site ({@link GrpcClusterPartialForwarder},
 * {@link ClusterCoordinationGrpcService}).
 * <p>
 * Cron defaults to second 50 so gauges land in the same closed minute as
 * {@link PlatformMetrics} flush (~:02 of the next minute).
 */
@Component
@ConditionalOnProperty(name = "apm.platform-metrics.enabled", havingValue = "true", matchIfMissing = true)
public class ClusterStatsSampler {

    private final ClusterInstanceCoordinator coordinator;

    public ClusterStatsSampler(ClusterInstanceCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Scheduled(cron = "${apm.platform-metrics.cluster-sample-cron:50 * * * * *}")
    void sample() {
        PlatformMetrics.gauge(PlatformMetricNames.CLUSTER_MEMBER_COUNT)
                .set(coordinator.sortedMembers().size());
        PlatformMetrics.gauge(PlatformMetricNames.CLUSTER_EFFECTIVE)
                .set(coordinator.effectiveClusterEnabled() ? 1.0 : 0.0);
        PlatformMetrics.gauge(PlatformMetricNames.CLUSTER_LEADER)
                .set(coordinator.isLeader() ? 1.0 : 0.0);
    }
}
