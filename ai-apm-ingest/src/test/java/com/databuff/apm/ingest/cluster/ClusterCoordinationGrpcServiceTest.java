package com.databuff.apm.ingest.cluster;

import com.databuff.apm.ingest.event.AggregateEvent;
import com.databuff.apm.ingest.support.IngestTestComponents;
import com.databuff.apm.cluster.v1.AggregationPartialRequest;
import com.databuff.apm.cluster.v1.AggregationPartialResponse;
import com.databuff.apm.cluster.v1.GetCacheRequest;
import com.databuff.apm.cluster.v1.GetCacheResponse;
import com.databuff.apm.cluster.v1.InvalidateCacheRequest;
import com.databuff.apm.cluster.v1.InvalidateCacheResponse;
import com.databuff.apm.cluster.v1.ReplicateCacheRequest;
import com.databuff.apm.cluster.v1.ReplicateCacheResponse;
import com.databuff.apm.common.cluster.cache.CacheRegionPolicy;
import com.databuff.apm.common.cluster.cache.ClusterCacheRegistry;
import com.databuff.apm.common.cluster.coordination.ClusterInstanceCoordinator;
import com.databuff.apm.common.model.OptimizedMetric;
import com.databuff.apm.common.serde.OptimizedMetricUtil;
import com.databuff.apm.ingest.component.AggregateComponent;
import com.databuff.apm.ingest.component.TraceComponent;
import com.databuff.apm.common.storage.DorisBatchWriter;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClusterCoordinationGrpcServiceTest {

    private AggregateComponent component;

    @AfterEach
    void tearDown() {
        if (component != null) {
            component.close();
        }
    }

    @Test
    void acceptsForwardedPartial() {
        component = IngestTestComponents.aggregate(new DorisBatchWriter());
        component.start(1);
        ClusterCoordinationGrpcService service = newService(component, leaderCoordinator("n1"));

        RecordingObserver observer = new RecordingObserver();
        service.forwardPartial(AggregationPartialRequest.newBuilder()
                .setPartitionKey("svc")
                .addPartials(com.google.protobuf.ByteString.copyFrom(
                        OptimizedMetricUtil.serialize(sampleMetric())))
                .setOriginNodeId("n2")
                .build(), observer);

        assertThat(observer.response.getAccepted()).isTrue();
    }

    @Test
    void returnsNotAcceptedOnFailure() {
        AggregateComponent failing = mock(AggregateComponent.class);
        doThrow(new IllegalStateException("boom"))
                .when(failing)
                .acceptForwardedPartial(anyString(), any(AggregateEvent.class));
        ClusterCoordinationGrpcService service = newService(failing, leaderCoordinator("n1"));

        RecordingObserver observer = new RecordingObserver();
        service.forwardPartial(AggregationPartialRequest.newBuilder()
                .setPartitionKey("svc")
                .addPartials(com.google.protobuf.ByteString.copyFrom(new byte[] {1}))
                .build(), observer);

        assertThat(observer.response.getAccepted()).isFalse();
    }

    @Test
    void leaderAppliesCachePut() {
        ClusterCacheRegistry registry = new ClusterCacheRegistry();
        registry.region("ingest.meta", CacheRegionPolicy.LEADER, java.time.Duration.ofHours(1));
        ClusterCoordinationGrpcService service = cacheService(registry, leaderCoordinator("n1"));

        ReplicateObserver observer = new ReplicateObserver();
        service.replicateCache(ReplicateCacheRequest.newBuilder()
                .setRegion("ingest.meta")
                .setKey("svc:demo")
                .setValue(com.google.protobuf.ByteString.copyFrom(new byte[]{9}))
                .setOriginNodeId("n2")
                .build(), observer);

        assertThat(observer.response.getAccepted()).isTrue();
        assertThat(registry.get("ingest.meta").get("svc:demo")).containsExactly(9);
    }

    @Test
    void leaderAppliesCacheInvalidate() {
        ClusterCacheRegistry registry = new ClusterCacheRegistry();
        var cache = registry.region("ingest.meta", CacheRegionPolicy.LEADER, java.time.Duration.ofHours(1));
        cache.put("svc:demo", new byte[]{1});
        ClusterCoordinationGrpcService service = cacheService(registry, leaderCoordinator("n1"));

        InvalidateObserver observer = new InvalidateObserver();
        service.invalidateCache(InvalidateCacheRequest.newBuilder()
                .setRegion("ingest.meta")
                .setKey("svc:demo")
                .setOriginNodeId("n2")
                .build(), observer);

        assertThat(observer.response.getAccepted()).isTrue();
        assertThat(cache.get("svc:demo")).isNull();
    }

    @Test
    void rejectsCachePutOnNonLeader() {
        ClusterCacheRegistry registry = new ClusterCacheRegistry();
        registry.region("ingest.meta", CacheRegionPolicy.LEADER, java.time.Duration.ofHours(1));
        ClusterCoordinationGrpcService service = cacheService(registry, followerCoordinator("n2"));

        ReplicateObserver observer = new ReplicateObserver();
        service.replicateCache(ReplicateCacheRequest.newBuilder()
                .setRegion("ingest.meta")
                .setKey("svc:demo")
                .setValue(com.google.protobuf.ByteString.copyFrom(new byte[]{9}))
                .setOriginNodeId("n2")
                .build(), observer);

        assertThat(observer.response.getAccepted()).isFalse();
        assertThat(registry.get("ingest.meta").get("svc:demo")).isNull();
    }

    @Test
    void leaderReturnsCacheValue() {
        ClusterCacheRegistry registry = new ClusterCacheRegistry();
        var cache = registry.region("ingest.meta", CacheRegionPolicy.LEADER, java.time.Duration.ofHours(1));
        cache.put("svc:demo", new byte[]{7});
        ClusterCoordinationGrpcService service = cacheService(registry, leaderCoordinator("n1"));

        GetCacheObserver observer = new GetCacheObserver();
        service.getCache(GetCacheRequest.newBuilder()
                .setRegion("ingest.meta")
                .setKey("svc:demo")
                .setOriginNodeId("n2")
                .build(), observer);

        assertThat(observer.response.getFound()).isTrue();
        assertThat(observer.response.getValue().toByteArray()).containsExactly(7);
    }

    private static ClusterCoordinationGrpcService newService(
            AggregateComponent component,
            ClusterInstanceCoordinator coordinator) {
        return new ClusterCoordinationGrpcService(
                component,
                Mockito.mock(TraceComponent.class),
                new LeaderClusterCacheExecutor(new ClusterCacheRegistry()),
                coordinator);
    }

    private static ClusterCoordinationGrpcService cacheService(
            ClusterCacheRegistry registry,
            ClusterInstanceCoordinator coordinator) {
        return new ClusterCoordinationGrpcService(
                mock(AggregateComponent.class),
                mock(TraceComponent.class),
                new LeaderClusterCacheExecutor(registry),
                coordinator);
    }

    private static ClusterInstanceCoordinator leaderCoordinator(String nodeId) {
        return testCoordinator(nodeId, true);
    }

    private static ClusterInstanceCoordinator followerCoordinator(String nodeId) {
        return testCoordinator(nodeId, false);
    }

    private static ClusterInstanceCoordinator testCoordinator(String nodeId, boolean leader) {
        ClusterInstanceCoordinator coordinator = mock(ClusterInstanceCoordinator.class);
        when(coordinator.localNodeId()).thenReturn(nodeId);
        when(coordinator.isLeader()).thenReturn(leader);
        when(coordinator.endpointsByNodeId()).thenReturn(Map.of(nodeId, "127.0.0.1:18112"));
        return coordinator;
    }

    private static OptimizedMetric sampleMetric() {
        return new OptimizedMetric()
                .withTsId(1)
                .withTimestamp(1_700_000_000_000_000_000L)
                .withMeasurement("service")
                .withTagValues("ok", "demo", "demo-id", "inst")
                .withFieldValues(1, 0, 100);
    }

    private static final class RecordingObserver implements StreamObserver<AggregationPartialResponse> {
        private AggregationPartialResponse response;

        @Override
        public void onNext(AggregationPartialResponse value) {
            response = value;
        }

        @Override
        public void onError(Throwable t) {
        }

        @Override
        public void onCompleted() {
        }
    }

    private static final class ReplicateObserver implements StreamObserver<ReplicateCacheResponse> {
        private ReplicateCacheResponse response;

        @Override
        public void onNext(ReplicateCacheResponse value) {
            response = value;
        }

        @Override
        public void onError(Throwable t) {
        }

        @Override
        public void onCompleted() {
        }
    }

    private static final class InvalidateObserver implements StreamObserver<InvalidateCacheResponse> {
        private InvalidateCacheResponse response;

        @Override
        public void onNext(InvalidateCacheResponse value) {
            response = value;
        }

        @Override
        public void onError(Throwable t) {
        }

        @Override
        public void onCompleted() {
        }
    }

    private static final class GetCacheObserver implements StreamObserver<GetCacheResponse> {
        private GetCacheResponse response;

        @Override
        public void onNext(GetCacheResponse value) {
            response = value;
        }

        @Override
        public void onError(Throwable t) {
        }

        @Override
        public void onCompleted() {
        }
    }
}
