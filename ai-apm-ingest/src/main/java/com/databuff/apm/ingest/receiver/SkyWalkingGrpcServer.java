package com.databuff.apm.ingest.receiver;

import com.databuff.apm.ingest.skywalking.ManagementServiceNoop;
import com.databuff.apm.ingest.skywalking.SkyWalkingIngestService;
import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PreDestroy;
import org.apache.skywalking.apm.network.common.v3.Commands;
import org.apache.skywalking.apm.network.language.agent.v3.JVMMetricCollection;
import org.apache.skywalking.apm.network.language.agent.v3.JVMMetricReportServiceGrpc;
import org.apache.skywalking.apm.network.language.agent.v3.SegmentCollection;
import org.apache.skywalking.apm.network.language.agent.v3.SegmentObject;
import org.apache.skywalking.apm.network.language.agent.v3.TraceSegmentReportServiceGrpc;
import org.apache.skywalking.apm.network.logging.v3.LogData;
import org.apache.skywalking.apm.network.logging.v3.LogReportServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "ingest.skywalking.enabled", havingValue = "true", matchIfMissing = true)
public class SkyWalkingGrpcServer implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(SkyWalkingGrpcServer.class);
    private static final Commands EMPTY = Commands.getDefaultInstance();

    private final SkyWalkingIngestService ingestService;
    private final int grpcPort;
    private Server server;

    public SkyWalkingGrpcServer(
            SkyWalkingIngestService ingestService,
            @Value("${ingest.skywalking.grpc-port:11800}") int grpcPort) {
        this.ingestService = ingestService;
        this.grpcPort = grpcPort;
    }

    public void start() throws IOException {
        BindableService traceService = new TraceSegmentReportServiceGrpc.TraceSegmentReportServiceImplBase() {
            @Override
            public StreamObserver<SegmentObject> collect(StreamObserver<Commands> responseObserver) {
                return new StreamObserver<>() {
                    @Override
                    public void onNext(SegmentObject segment) {
                        ingestService.ingestSegment(segment);
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        log.warn("SkyWalking trace stream error", throwable);
                    }

                    @Override
                    public void onCompleted() {
                        responseObserver.onNext(EMPTY);
                        responseObserver.onCompleted();
                    }
                };
            }

            @Override
            public void collectInSync(SegmentCollection request, StreamObserver<Commands> responseObserver) {
                for (SegmentObject segment : request.getSegmentsList()) {
                    ingestService.ingestSegment(segment);
                }
                responseObserver.onNext(EMPTY);
                responseObserver.onCompleted();
            }
        };
        BindableService jvmService = new JVMMetricReportServiceGrpc.JVMMetricReportServiceImplBase() {
            @Override
            public void collect(JVMMetricCollection request, StreamObserver<Commands> responseObserver) {
                ingestService.ingestJvmMetrics(request);
                responseObserver.onNext(EMPTY);
                responseObserver.onCompleted();
            }
        };
        BindableService managementService = new ManagementServiceNoop();

        server = ServerBuilder.forPort(grpcPort)
                .addService(traceService)
                .addService(GrpcServiceAlias.withServiceName(traceService, "TraceSegmentReportService"))
                .addService(jvmService)
                .addService(GrpcServiceAlias.withServiceName(jvmService, "JVMMetricReportService"))
                .addService(new LogReportServiceGrpc.LogReportServiceImplBase() {
                    @Override
                    public StreamObserver<LogData> collect(StreamObserver<Commands> responseObserver) {
                        return new StreamObserver<>() {
                            @Override
                            public void onNext(LogData logData) {
                                ingestService.ingestLog(logData);
                            }

                            @Override
                            public void onError(Throwable throwable) {
                                log.warn("SkyWalking log stream error", throwable);
                            }

                            @Override
                            public void onCompleted() {
                                responseObserver.onNext(EMPTY);
                                responseObserver.onCompleted();
                            }
                        };
                    }
                })
                .addService(managementService)
                .addService(GrpcServiceAlias.withServiceName(managementService, "ManagementService"))
                .build()
                .start();
        log.info("SkyWalking gRPC listening on port {}", server.getPort());
    }

    int boundPort() {
        return server == null ? grpcPort : server.getPort();
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            start();
        } catch (IOException e) {
            throw new IllegalStateException("failed to start SkyWalking gRPC server", e);
        }
    }

    @PreDestroy
    public void stop() throws InterruptedException {
        if (server != null) {
            server.shutdown();
            server.awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
