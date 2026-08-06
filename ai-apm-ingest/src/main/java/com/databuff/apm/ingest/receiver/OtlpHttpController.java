package com.databuff.apm.ingest.receiver;

import com.databuff.apm.common.platform.PlatformMetricNames;
import com.databuff.apm.common.platform.PlatformMetrics;
import com.databuff.apm.ingest.otel.OtlpIngestService;
import com.google.protobuf.InvalidProtocolBufferException;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static com.databuff.apm.common.platform.PlatformMetricNames.KIND_FAIL;
import static com.databuff.apm.common.platform.PlatformMetricNames.PROTO_OTEL;
import static com.databuff.apm.common.platform.PlatformMetricNames.SIGNAL_LOG;
import static com.databuff.apm.common.platform.PlatformMetricNames.SIGNAL_METRIC;
import static com.databuff.apm.common.platform.PlatformMetricNames.SIGNAL_TRACE;

@RestController
public class OtlpHttpController {

    private final OtlpIngestService ingestService;

    public OtlpHttpController(OtlpIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping(value = "/v1/traces")
    public ResponseEntity<Void> traces(@RequestBody byte[] body) throws InvalidProtocolBufferException {
        try {
            ExportTraceServiceRequest request = ExportTraceServiceRequest.parseFrom(body);
            ingestService.ingestTraces(request);
            return ResponseEntity.ok().build();
        } catch (InvalidProtocolBufferException e) {
            PlatformMetrics.counter(PlatformMetricNames.inbound(PROTO_OTEL, SIGNAL_TRACE, KIND_FAIL)).inc();
            throw e;
        }
    }

    @PostMapping(value = "/v1/metrics")
    public ResponseEntity<Void> metrics(@RequestBody byte[] body) throws InvalidProtocolBufferException {
        try {
            ExportMetricsServiceRequest request = ExportMetricsServiceRequest.parseFrom(body);
            ingestService.ingestMetrics(request);
            return ResponseEntity.ok().build();
        } catch (InvalidProtocolBufferException e) {
            PlatformMetrics.counter(PlatformMetricNames.inbound(PROTO_OTEL, SIGNAL_METRIC, KIND_FAIL)).inc();
            throw e;
        }
    }

    @PostMapping(value = "/v1/logs")
    public ResponseEntity<Void> logs(@RequestBody byte[] body) throws InvalidProtocolBufferException {
        try {
            ExportLogsServiceRequest request = ExportLogsServiceRequest.parseFrom(body);
            ingestService.ingestLogs(request);
            return ResponseEntity.ok().build();
        } catch (InvalidProtocolBufferException e) {
            PlatformMetrics.counter(PlatformMetricNames.inbound(PROTO_OTEL, SIGNAL_LOG, KIND_FAIL)).inc();
            throw e;
        }
    }
}
