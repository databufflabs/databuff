package com.databuff.apm.ingest.cluster;

import com.databuff.apm.ingest.metric.TraceMinuteMetricAggregator;
import com.databuff.apm.cluster.v1.AggregationPartialRequest;
import com.databuff.apm.cluster.v1.AggregationPartialResponse;
import com.databuff.apm.cluster.v1.ClusterCoordinationServiceGrpc;
import com.databuff.apm.cluster.v1.GetCacheRequest;
import com.databuff.apm.cluster.v1.GetCacheResponse;
import com.databuff.apm.cluster.v1.InvalidateCacheRequest;
import com.databuff.apm.cluster.v1.InvalidateCacheResponse;
import com.databuff.apm.cluster.v1.ReplicateCacheRequest;
import com.databuff.apm.cluster.v1.ReplicateCacheResponse;
import com.databuff.apm.common.cluster.coordination.ClusterInstanceCoordinator;
import com.databuff.apm.ingest.component.AggregateComponent;
import com.databuff.apm.ingest.component.TraceComponent;
import com.databuff.apm.ingest.event.AggregateEvent;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 集群协调 gRPC 服务。
 * Cache RPCs are serialized on the ingest leader; followers proxy through {@link GrpcLeaderClusterCacheTransport}.
 */
public final class ClusterCoordinationGrpcService
        extends ClusterCoordinationServiceGrpc.ClusterCoordinationServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(ClusterCoordinationGrpcService.class);

    private final AggregateComponent aggregateComponent;
    private final TraceComponent traceComponent;
    private final LeaderClusterCacheExecutor cacheExecutor;
    private final ClusterInstanceCoordinator coordinator;
    private final String localNodeId;

    public ClusterCoordinationGrpcService(
            AggregateComponent aggregateComponent,
            TraceComponent traceComponent,
            LeaderClusterCacheExecutor cacheExecutor,
            ClusterInstanceCoordinator coordinator) {
        this.aggregateComponent = aggregateComponent;
        this.traceComponent = traceComponent;
        this.cacheExecutor = cacheExecutor;
        this.coordinator = coordinator;
        this.localNodeId = coordinator.localNodeId();
    }

    @Override
    public void forwardPartial(
            AggregationPartialRequest request, StreamObserver<AggregationPartialResponse> responseObserver) {
        try {
            if (TraceComponent.TRACE_STREAM.equals(request.getStream())) {
                for (com.google.protobuf.ByteString partial : request.getPartialsList()) {
                    traceComponent.acceptForwardedSpan(
                            request.getPartitionKey(), partial.toByteArray());
                }
            } else if (TraceMinuteMetricAggregator.STREAM.equals(request.getStream())) {
                for (com.google.protobuf.ByteString partial : request.getPartialsList()) {
                    aggregateComponent.acceptForwardedTraceMinutePartial(
                            request.getPartitionKey(),
                            request.getWindowStart(),
                            request.getWindowEnd(),
                            partial.toByteArray());
                }
            } else {
                for (com.google.protobuf.ByteString partial : request.getPartialsList()) {
                    aggregateComponent.acceptForwardedPartial(
                            request.getPartitionKey(),
                            AggregateEvent.fromBytes(partial.toByteArray()));
                }
            }
            responseObserver.onNext(AggregationPartialResponse.newBuilder().setAccepted(true).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.warn("ForwardPartial ingest failed from {}: {}", request.getOriginNodeId(), e.toString());
            responseObserver.onNext(AggregationPartialResponse.newBuilder().setAccepted(false).build());
            responseObserver.onCompleted();
        }
    }

    @Override
    public void replicateCache(
            ReplicateCacheRequest request, StreamObserver<ReplicateCacheResponse> responseObserver) {
        try {
            if (!coordinator.isLeader()) {
                log.warn("Rejecting cache put on non-leader node {} from {}", localNodeId, request.getOriginNodeId());
                responseObserver.onNext(ReplicateCacheResponse.newBuilder().setAccepted(false).build());
                responseObserver.onCompleted();
                return;
            }
            cacheExecutor.put(
                    request.getRegion(),
                    request.getKey(),
                    request.getValue().toByteArray());
            responseObserver.onNext(ReplicateCacheResponse.newBuilder().setAccepted(true).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.warn("ReplicateCache failed from {}: {}", request.getOriginNodeId(), e.toString());
            responseObserver.onNext(ReplicateCacheResponse.newBuilder().setAccepted(false).build());
            responseObserver.onCompleted();
        }
    }

    @Override
    public void invalidateCache(
            InvalidateCacheRequest request, StreamObserver<InvalidateCacheResponse> responseObserver) {
        try {
            if (!coordinator.isLeader()) {
                log.warn("Rejecting cache invalidate on non-leader node {} from {}",
                        localNodeId, request.getOriginNodeId());
                responseObserver.onNext(InvalidateCacheResponse.newBuilder().setAccepted(false).build());
                responseObserver.onCompleted();
                return;
            }
            cacheExecutor.invalidate(request.getRegion(), request.getKey());
            responseObserver.onNext(InvalidateCacheResponse.newBuilder().setAccepted(true).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.warn("InvalidateCache failed from {}: {}", request.getOriginNodeId(), e.toString());
            responseObserver.onNext(InvalidateCacheResponse.newBuilder().setAccepted(false).build());
            responseObserver.onCompleted();
        }
    }

    @Override
    public void getCache(GetCacheRequest request, StreamObserver<GetCacheResponse> responseObserver) {
        try {
            if (!coordinator.isLeader()) {
                log.warn("Rejecting cache get on non-leader node {} from {}", localNodeId, request.getOriginNodeId());
                responseObserver.onNext(GetCacheResponse.newBuilder().setFound(false).build());
                responseObserver.onCompleted();
                return;
            }
            byte[] value = cacheExecutor.get(request.getRegion(), request.getKey());
            GetCacheResponse.Builder builder = GetCacheResponse.newBuilder();
            if (value == null) {
                builder.setFound(false);
            } else {
                builder.setFound(true).setValue(com.google.protobuf.ByteString.copyFrom(value));
            }
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.warn("GetCache failed from {}: {}", request.getOriginNodeId(), e.toString());
            responseObserver.onNext(GetCacheResponse.newBuilder().setFound(false).build());
            responseObserver.onCompleted();
        }
    }
}
