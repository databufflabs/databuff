package com.databuff.apm.ingest.otel;

import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.common.platform.PlatformMetricNames;
import com.databuff.apm.common.platform.PlatformMetrics;
import com.databuff.apm.ingest.event.TraceBatchEvent;
import com.databuff.apm.ingest.event.TraceEvent;
import com.databuff.apm.ingest.gateway.PipelineGateway;
import com.databuff.apm.ingest.log.OtlpLogDirectWriter;
import com.databuff.apm.ingest.metric.OtlpMetricDirectWriter;
import com.databuff.apm.ingest.support.LogRateLimiter;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static com.databuff.apm.common.platform.PlatformMetricNames.KIND_BYTES;
import static com.databuff.apm.common.platform.PlatformMetricNames.KIND_COST_MS;
import static com.databuff.apm.common.platform.PlatformMetricNames.KIND_FAIL;
import static com.databuff.apm.common.platform.PlatformMetricNames.KIND_REQ;
import static com.databuff.apm.common.platform.PlatformMetricNames.PROTO_OTEL;
import static com.databuff.apm.common.platform.PlatformMetricNames.SIGNAL_LOG;
import static com.databuff.apm.common.platform.PlatformMetricNames.SIGNAL_METRIC;
import static com.databuff.apm.common.platform.PlatformMetricNames.SIGNAL_TRACE;

/**
 * OTLP 接收入口：protobuf 反序列化后转为 DataBuff 内存对象，经 {@link PipelineGateway} 分发。
 * <p>
 * Trace 链路：convert → enrich → traceId 聚合 → fill → 指标聚合 → 存储<br>
 * Metric 链路：convert → {@link OtlpMetricDirectWriter} → Doris<br>
 * Log 链路：convert → {@link OtlpLogDirectWriter} → Doris
 */
public final class OtlpIngestService {

    private static final Logger log = LoggerFactory.getLogger(OtlpIngestService.class);
    private static final LogRateLimiter TRACE_BATCH_EMIT_LIMITER = new LogRateLimiter(10_000L);

    private final OtelConverter converter;
    private final PipelineGateway gateway;
    private final OtlpMetricDirectWriter metricDirectWriter;
    private final OtlpLogDirectWriter logDirectWriter;
    private final AtomicLong tracesIngested = new AtomicLong();
    private final AtomicLong metricsIngested = new AtomicLong();
    private final AtomicLong logsIngested = new AtomicLong();

    public OtlpIngestService(
            OtelConverter converter,
            PipelineGateway gateway,
            OtlpMetricDirectWriter metricDirectWriter,
            OtlpLogDirectWriter logDirectWriter) {
        this.converter = converter;
        this.gateway = gateway;
        this.metricDirectWriter = metricDirectWriter;
        this.logDirectWriter = logDirectWriter;
    }

    /** Step 1：OTLP trace → {@link DcSpan} 对象（不序列化）。 */
    public int ingestTraces(ExportTraceServiceRequest request) {
        long startMs = System.currentTimeMillis();
        PlatformMetrics.counter(otel(SIGNAL_TRACE, KIND_BYTES)).add(request.getSerializedSize());
        try {
            int accepted = 0;
            Map<String, List<DcSpan>> spansByTraceId = new LinkedHashMap<>();
            List<OtelConverter.ConvertedTrace> converted = converter.convertTraces(request);
            // req = span 个数（非 HTTP 请求次数）
            PlatformMetrics.counter(otel(SIGNAL_TRACE, KIND_REQ)).add(converted.size());
            for (OtelConverter.ConvertedTrace trace : converted) {
                DcSpan span = trace.span();
                if (span == null || span.trace_id == null || span.trace_id.isBlank()) {
                    if (gateway.emit(trace.serviceKey(), new TraceEvent(span))) {
                        accepted++;
                    } else {
                        PlatformMetrics.counter(otel(SIGNAL_TRACE, KIND_FAIL)).inc();
                    }
                    continue;
                }
                spansByTraceId.computeIfAbsent(span.trace_id, ignored -> new ArrayList<>()).add(span);
            }
            for (Map.Entry<String, List<DcSpan>> entry : spansByTraceId.entrySet()) {
                if (gateway.emit(entry.getKey(), new TraceBatchEvent(entry.getValue()))) {
                    accepted += entry.getValue().size();
                } else {
                    PlatformMetrics.counter(otel(SIGNAL_TRACE, KIND_FAIL)).add(entry.getValue().size());
                    if (log.isDebugEnabled()) {
                        log.debug(
                                "Trace batch emit failed traceId={} spans={}",
                                shortTraceId(entry.getKey()),
                                entry.getValue().size());
                    }
                    long suppressed = TRACE_BATCH_EMIT_LIMITER.record();
                    if (suppressed > 0) {
                        log.warn("Trace batch emit failed {} times in the last 10s (trace pipeline queue full)", suppressed);
                    }
                }
            }
            tracesIngested.addAndGet(accepted);
            return accepted;
        } finally {
            PlatformMetrics.timer(otel(SIGNAL_TRACE, KIND_COST_MS))
                    .add(System.currentTimeMillis() - startMs);
        }
    }

    /** Step 1：OTLP metric → 映射后直接写入 Doris（不经聚合流水线）。 */
    public int ingestMetrics(ExportMetricsServiceRequest request) {
        long startMs = System.currentTimeMillis();
        PlatformMetrics.counter(otel(SIGNAL_METRIC, KIND_BYTES)).add(request.getSerializedSize());
        try {
            List<OtelConverter.ConvertedMetric> converted = converter.convertMetrics(request);
            // req = metric 条数（非 Export 请求次数）
            PlatformMetrics.counter(otel(SIGNAL_METRIC, KIND_REQ)).add(converted.size());
            metricDirectWriter.write(converted.stream().map(OtelConverter.ConvertedMetric::line).toList());
            int accepted = converted.size();
            metricsIngested.addAndGet(accepted);
            return accepted;
        } finally {
            PlatformMetrics.timer(otel(SIGNAL_METRIC, KIND_COST_MS))
                    .add(System.currentTimeMillis() - startMs);
        }
    }

    /** OTLP log → mapped rows written directly to Doris. */
    public int ingestLogs(ExportLogsServiceRequest request) {
        long startMs = System.currentTimeMillis();
        PlatformMetrics.counter(otel(SIGNAL_LOG, KIND_BYTES)).add(request.getSerializedSize());
        try {
            List<OtelConverter.ConvertedLog> converted = converter.convertLogs(request);
            // req = log 条数（非 Export 请求次数）
            PlatformMetrics.counter(otel(SIGNAL_LOG, KIND_REQ)).add(converted.size());
            logDirectWriter.write(converted.stream().map(OtelConverter.ConvertedLog::line).toList());
            int accepted = converted.size();
            logsIngested.addAndGet(accepted);
            return accepted;
        } finally {
            PlatformMetrics.timer(otel(SIGNAL_LOG, KIND_COST_MS))
                    .add(System.currentTimeMillis() - startMs);
        }
    }

    public long tracesIngested() {
        return tracesIngested.get();
    }

    public long metricsIngested() {
        return metricsIngested.get();
    }

    public long logsIngested() {
        return logsIngested.get();
    }

    private static String otel(String signal, String kind) {
        return PlatformMetricNames.inbound(PROTO_OTEL, signal, kind);
    }

    private static String shortTraceId(String traceId) {
        if (traceId == null || traceId.length() <= 12) {
            return String.valueOf(traceId);
        }
        return traceId.substring(0, 12) + "..." + traceId.substring(traceId.length() - 6);
    }
}
