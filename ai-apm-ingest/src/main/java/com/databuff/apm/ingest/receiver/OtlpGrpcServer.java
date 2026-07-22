package com.databuff.apm.ingest.receiver;

import com.databuff.apm.ingest.otel.OtlpIngestService;
import com.databuff.apm.ingest.receiver.compression.GrpcSnappyCodec;
import com.databuff.apm.ingest.receiver.compression.GrpcZstdCodec;
import io.grpc.Codec;
import io.grpc.CompressorRegistry;
import io.grpc.DecompressorRegistry;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import io.opentelemetry.proto.collector.logs.v1.LogsServiceGrpc;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse;
import io.opentelemetry.proto.collector.metrics.v1.MetricsServiceGrpc;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
public class OtlpGrpcServer implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(OtlpGrpcServer.class);

    private final OtlpIngestService ingestService;
    private final int grpcPort;
    private Server server;

    public OtlpGrpcServer(OtlpIngestService ingestService, @Value("${ingest.otlp.grpc-port:4317}") int grpcPort) {
        this.ingestService = ingestService;
        this.grpcPort = grpcPort;
    }

    public void start() throws IOException {
        // gzip is built into gRPC; register snappy/zstd to match otel-collector exporters.
        CompressorRegistry compressors = CompressorRegistry.newEmptyInstance();
        compressors.register(new Codec.Gzip());
        compressors.register(GrpcSnappyCodec.INSTANCE);
        compressors.register(GrpcZstdCodec.INSTANCE);
        DecompressorRegistry decompressors = DecompressorRegistry.getDefaultInstance()
                .with(GrpcSnappyCodec.INSTANCE, true)
                .with(GrpcZstdCodec.INSTANCE, true);

        server = ServerBuilder.forPort(grpcPort)
                .compressorRegistry(compressors)
                .decompressorRegistry(decompressors)
                .addService(new TraceServiceGrpc.TraceServiceImplBase() {
                    @Override
                    public void export(ExportTraceServiceRequest request, StreamObserver<ExportTraceServiceResponse> observer) {
                        ingestService.ingestTraces(request);
                        observer.onNext(ExportTraceServiceResponse.getDefaultInstance());
                        observer.onCompleted();
                    }
                })
                .addService(new MetricsServiceGrpc.MetricsServiceImplBase() {
                    @Override
                    public void export(ExportMetricsServiceRequest request, StreamObserver<ExportMetricsServiceResponse> observer) {
                        ingestService.ingestMetrics(request);
                        observer.onNext(ExportMetricsServiceResponse.getDefaultInstance());
                        observer.onCompleted();
                    }
                })
                .addService(new LogsServiceGrpc.LogsServiceImplBase() {
                    @Override
                    public void export(ExportLogsServiceRequest request, StreamObserver<ExportLogsServiceResponse> observer) {
                        ingestService.ingestLogs(request);
                        observer.onNext(ExportLogsServiceResponse.getDefaultInstance());
                        observer.onCompleted();
                    }
                })
                .build()
                .start();
        log.info("OTLP gRPC listening on port {} (compression: gzip, snappy, zstd)", grpcPort);
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            start();
        } catch (IOException e) {
            throw new IllegalStateException("failed to start OTLP gRPC server", e);
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
