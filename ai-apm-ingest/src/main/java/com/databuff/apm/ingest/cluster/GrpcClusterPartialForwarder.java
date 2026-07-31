package com.databuff.apm.ingest.cluster;

import com.databuff.apm.cluster.v1.AggregationPartialRequest;
import com.databuff.apm.cluster.v1.ClusterCoordinationServiceGrpc;
import com.databuff.apm.common.cluster.aggregate.ClusterPartialForwarder;
import com.databuff.apm.common.cluster.coordination.ClusterInstanceCoordinator;
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
    private static final LogRateLimiter FORWARD_LIMITER = new LogRateLimiter(10_000L);

    private final ClusterInstanceCoordinator coordinator;
    private final String originNodeId;
    private final Map<String, ManagedChannel> channels = new ConcurrentHashMap<>();

    public GrpcClusterPartialForwarder(ClusterInstanceCoordinator coordinator) {
        this.coordinator = coordinator;
        this.originNodeId = coordinator.localNodeId();
    }

    @Override
    public void forward(
            String targetNodeId,
            String stream,
            String partitionKey,
            long windowStart,
            long windowEnd,
            byte[] partial) {
        String endpoint = coordinator.endpointFor(targetNodeId).orElse(null);
        if (endpoint == null) {
            warnThrottled(
                    "Cluster forward out failed stream={} target={} reason=no-endpoint partition={} windowMs={} bytes={} {}",
                    stream,
                    targetNodeId,
                    ClusterAggregationLog.partitionKeyBrief(partitionKey),
                    windowStart,
                    partial == null ? 0 : partial.length,
                    ClusterAggregationLog.membershipBrief(coordinator));
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
                warnThrottled(
                        "Cluster forward out rejected stream={} origin={} target={} endpoint={} partition={} windowMs={} bytes={}",
                        stream,
                        originNodeId,
                        targetNodeId,
                        endpoint,
                        ClusterAggregationLog.partitionKeyBrief(partitionKey),
                        windowStart,
                        partial.length);
            }
        } catch (Exception e) {
            warnThrottled(
                    "Cluster forward out failed stream={} origin={} target={} endpoint={} partition={} windowMs={} bytes={}: {}",
                    stream,
                    originNodeId,
                    targetNodeId,
                    endpoint,
                    ClusterAggregationLog.partitionKeyBrief(partitionKey),
                    windowStart,
                    partial == null ? 0 : partial.length,
                    e.toString());
        }
    }

    private static void warnThrottled(String template, Object... args) {
        if (log.isDebugEnabled()) {
            log.debug(template, args);
        }
        long count = FORWARD_LIMITER.record();
        if (count > 0) {
            log.warn("Cluster partial forward failures: {} in last 10s", count);
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
