package com.databuff.apm.ingest.receiver;

import com.databuff.apm.ingest.otel.OtlpIngestService;
import com.google.protobuf.InvalidProtocolBufferException;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OtlpHttpController {

    private final OtlpIngestService ingestService;

    public OtlpHttpController(OtlpIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping(value = "/v1/traces")
    public ResponseEntity<Void> traces(@RequestBody byte[] body) throws InvalidProtocolBufferException {
        ExportTraceServiceRequest request = ExportTraceServiceRequest.parseFrom(body);
        ingestService.ingestTraces(request);
        return ResponseEntity.ok().build();
    }

    @PostMapping(value = "/v1/metrics")
    public ResponseEntity<Void> metrics(@RequestBody byte[] body) throws InvalidProtocolBufferException {
        ExportMetricsServiceRequest request = ExportMetricsServiceRequest.parseFrom(body);
        ingestService.ingestMetrics(request);
        return ResponseEntity.ok().build();
    }

    @PostMapping(value = "/v1/logs")
    public ResponseEntity<Void> logs(@RequestBody byte[] body) throws InvalidProtocolBufferException {
        ExportLogsServiceRequest request = ExportLogsServiceRequest.parseFrom(body);
        ingestService.ingestLogs(request);
        return ResponseEntity.ok().build();
    }
}
