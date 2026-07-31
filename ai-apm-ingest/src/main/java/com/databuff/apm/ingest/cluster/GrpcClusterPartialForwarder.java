package com.databuff.apm.ingest.cluster;

import com.databuff.apm.common.cluster.aggregate.ClusterPartialForwarder;
import com.databuff.apm.common.cluster.coordination.ClusterInstanceCoordinator;
import com.databuff.apm.cluster.v1.AggregationPartialRequest;
import com.databuff.apm.cluster.v1.ClusterCoordinationServiceGrpc;
import com.databuff.apm.ingest.support.LogRateLimiter;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class GrpcClusterPartialForwarder implements ClusterPartialForwarder, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GrpcClusterPartialForwarder.class);
    private static final LogRateLimiter FORWARD_TALLY = new LogRateLimiter(10_000L);

    private final ClusterInstanceCoordinator coordinator;
    private final String originNodeId;
    private final Map<String, ManagedChannel> channels = new ConcurrentHashMap<>();

    public GrpcClusterPartialForwarder(ClusterInstanceCoordinator coordinator) {
        this.coordinator = coordinator;
        this.originNodeId = coordinator.localNodeId();
        log.info(
                "{} boot originNodeId={} clusterEnabled={} members={} endpoints={} (pipeline diag logging active)",
                ClusterPipelineLog.FORWARD_OUT,
                originNodeId,
                coordinator.effectiveClusterEnabled(),
                coordinator.sortedMembers().size(),
                coordinator.endpointsByNodeId());
    }

    @Override
    public void forward(
            String targetNodeId,
            String stream,
            String partitionKey,
            long windowStart,
            long windowEnd,
            byte[] partial) {
        int bytes = partial == null ? 0 : partial.length;
        String endpoint = coordinator.endpointFor(targetNodeId).orElse(null);
        if (endpoint == null) {
            ClusterPipelineLog.forwardOutFailed(
                    log,
                    "no-endpoint",
                    stream,
                    originNodeId,
                    targetNodeId,
                    null,
                    partitionKey,
                    windowStart,
                    bytes,
                    coordinator,
                    "endpointFor(target) empty — peer missing from ZK membership or not registered");
            tallyFailures();
            return;
        }
        try {
            ClusterCoordinationServiceGrpc.ClusterCoordinationServiceBlockingStub stub =
                    ClusterCoordinationServiceGrpc.newBlockingStub(channel(endpoint))
                            .withDeadlineAfter(3, TimeUnit.SECONDS);
            var response = stub.forwardPartial(AggregationPartialRequest.newBuilder()
                    .setStream(stream)
                    .setPartitionKey(partitionKey)
                    .setWindowStart(windowStart)
                    .setWindowEnd(windowEnd)
                    .addPartials(com.google.protobuf.ByteString.copyFrom(partial))
                    .setOriginNodeId(originNodeId)
                    .build());
            if (!response.getAccepted()) {
                ClusterPipelineLog.forwardOutFailed(
                        log,
                        "rejected",
                        stream,
                        originNodeId,
                        targetNodeId,
                        endpoint,
                        partitionKey,
                        windowStart,
                        bytes,
                        coordinator,
                        "peer returned accepted=false (decode/owner/accept path failed on target)");
                tallyFailures();
                return;
            }
            ClusterPipelineLog.forwardOutOk(
                    log,
                    stream,
                    originNodeId,
                    targetNodeId,
                    endpoint,
                    partitionKey,
                    windowStart,
                    bytes,
                    coordinator);
        } catch (Exception e) {
            ClusterPipelineLog.forwardOutFailed(
                    log,
                    "exception",
                    stream,
                    originNodeId,
                    targetNodeId,
                    endpoint,
                    partitionKey,
                    windowStart,
                    bytes,
                    coordinator,
                    e.toString());
            tallyFailures();
        }
    }

    private static void tallyFailures() {
        long count = FORWARD_TALLY.record();
        if (count > 0) {
            log.warn("{} failure-tally={} in last 10s (see preceding FAILED lines for raw cause)",
                    ClusterPipelineLog.FORWARD_OUT,
                    count);
        }
    }

    private ManagedChannel channel(String endpoint) {
        return channels.computeIfAbsent(endpoint, ep -> ManagedChannelBuilder.forTarget(ep)
                .usePlaintext()
                .build());
    }

    @Override
    public void close() {
        channels.values().forEach(ch -> {
            try {
                ch.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        channels.clear();
    }
}
