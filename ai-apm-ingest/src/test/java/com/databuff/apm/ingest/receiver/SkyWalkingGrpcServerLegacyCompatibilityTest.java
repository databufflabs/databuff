package com.databuff.apm.ingest.receiver;

import com.databuff.apm.ingest.skywalking.SkyWalkingIngestService;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.StreamObserver;
import org.apache.skywalking.apm.network.common.v3.Commands;
import org.apache.skywalking.apm.network.language.agent.v3.JVMMetricCollection;
import org.apache.skywalking.apm.network.language.agent.v3.JVMMetricReportServiceGrpc;
import org.apache.skywalking.apm.network.language.agent.v3.SegmentCollection;
import org.apache.skywalking.apm.network.language.agent.v3.SegmentObject;
import org.apache.skywalking.apm.network.language.agent.v3.TraceSegmentReportServiceGrpc;
import org.apache.skywalking.apm.network.management.v3.InstancePingPkg;
import org.apache.skywalking.apm.network.management.v3.InstanceProperties;
import org.apache.skywalking.apm.network.management.v3.ManagementServiceGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SkyWalkingGrpcServerLegacyCompatibilityTest {

    private static final CallOptions CALL_OPTIONS = CallOptions.DEFAULT.withDeadlineAfter(3, TimeUnit.SECONDS);

    private SkyWalkingGrpcServer server;
    private ManagedChannel channel;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (channel != null) {
            channel.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
        }
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void acceptsSkyWalkingEightPointOneServiceNames() throws Exception {
        SkyWalkingIngestService ingestService = mock(SkyWalkingIngestService.class);
        server = new SkyWalkingGrpcServer(ingestService, 0);
        server.start();
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.boundPort())
                .usePlaintext()
                .build();

        SegmentObject streamedSegment = SegmentObject.newBuilder()
                .setTraceId("legacy-stream-trace")
                .setTraceSegmentId("legacy-stream-segment")
                .build();
        RecordingObserver<Commands> traceResponse = new RecordingObserver<>();
        StreamObserver<SegmentObject> traceRequest = ClientCalls.asyncClientStreamingCall(
                channel.newCall(legacy(
                        TraceSegmentReportServiceGrpc.getCollectMethod(),
                        "TraceSegmentReportService"), CALL_OPTIONS),
                traceResponse);
        traceRequest.onNext(streamedSegment);
        traceRequest.onCompleted();

        assertThat(traceResponse.await()).isTrue();
        assertThat(traceResponse.error).isNull();
        assertThat(traceResponse.value).isEqualTo(Commands.getDefaultInstance());
        verify(ingestService).ingestSegment(streamedSegment);

        SegmentObject synchronousSegment = SegmentObject.newBuilder()
                .setTraceId("legacy-sync-trace")
                .setTraceSegmentId("legacy-sync-segment")
                .build();
        Commands traceCommands = ClientCalls.blockingUnaryCall(
                channel,
                legacy(TraceSegmentReportServiceGrpc.getCollectInSyncMethod(), "TraceSegmentReportService"),
                CALL_OPTIONS,
                SegmentCollection.newBuilder().addSegments(synchronousSegment).build());
        assertThat(traceCommands).isEqualTo(Commands.getDefaultInstance());
        verify(ingestService).ingestSegment(synchronousSegment);

        JVMMetricCollection jvmMetrics = JVMMetricCollection.newBuilder()
                .setService("legacy-service")
                .setServiceInstance("legacy-instance")
                .build();
        Commands jvmCommands = ClientCalls.blockingUnaryCall(
                channel,
                legacy(JVMMetricReportServiceGrpc.getCollectMethod(), "JVMMetricReportService"),
                CALL_OPTIONS,
                jvmMetrics);
        assertThat(jvmCommands).isEqualTo(Commands.getDefaultInstance());
        verify(ingestService).ingestJvmMetrics(jvmMetrics);

        Commands propertiesCommands = ClientCalls.blockingUnaryCall(
                channel,
                legacy(ManagementServiceGrpc.getReportInstancePropertiesMethod(), "ManagementService"),
                CALL_OPTIONS,
                InstanceProperties.newBuilder()
                        .setService("legacy-service")
                        .setServiceInstance("legacy-instance")
                        .build());
        assertThat(propertiesCommands).isEqualTo(Commands.getDefaultInstance());

        Commands keepAliveCommands = ClientCalls.blockingUnaryCall(
                channel,
                legacy(ManagementServiceGrpc.getKeepAliveMethod(), "ManagementService"),
                CALL_OPTIONS,
                InstancePingPkg.newBuilder()
                        .setService("legacy-service")
                        .setServiceInstance("legacy-instance")
                        .build());
        assertThat(keepAliveCommands).isEqualTo(Commands.getDefaultInstance());

        SegmentObject modernSegment = SegmentObject.newBuilder()
                .setTraceId("modern-trace")
                .setTraceSegmentId("modern-segment")
                .build();
        Commands modernCommands = TraceSegmentReportServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(3, TimeUnit.SECONDS)
                .collectInSync(SegmentCollection.newBuilder().addSegments(modernSegment).build());
        assertThat(modernCommands).isEqualTo(Commands.getDefaultInstance());
        verify(ingestService).ingestSegment(modernSegment);
    }

    private static <RequestT, ResponseT> MethodDescriptor<RequestT, ResponseT> legacy(
            MethodDescriptor<RequestT, ResponseT> source,
            String serviceName) {
        return source.toBuilder()
                .setFullMethodName(MethodDescriptor.generateFullMethodName(
                        serviceName,
                        source.getBareMethodName()))
                .build();
    }

    private static final class RecordingObserver<T> implements StreamObserver<T> {

        private final CountDownLatch completed = new CountDownLatch(1);
        private volatile T value;
        private volatile Throwable error;

        @Override
        public void onNext(T next) {
            value = next;
        }

        @Override
        public void onError(Throwable throwable) {
            error = throwable;
            completed.countDown();
        }

        @Override
        public void onCompleted() {
            completed.countDown();
        }

        boolean await() throws InterruptedException {
            return completed.await(3, TimeUnit.SECONDS);
        }
    }
}
