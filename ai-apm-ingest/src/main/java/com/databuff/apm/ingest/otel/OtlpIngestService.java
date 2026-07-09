package com.databuff.apm.ingest.otel;

import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.ingest.event.TraceBatchEvent;
import com.databuff.apm.ingest.event.TraceEvent;
import com.databuff.apm.ingest.gateway.PipelineGateway;
import com.databuff.apm.ingest.log.OtlpLogDirectWriter;
import com.databuff.apm.ingest.metric.OtlpMetricDirectWriter;
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

/**
 * OTLP 接收入口：protobuf 反序列化后转为 DataBuff 内存对象，经 {@link PipelineGateway} 分发。
 * <p>
 * Trace 链路：convert → enrich → traceId 聚合 → fill → 指标聚合 → 存储<br>
 * Metric 链路：convert → {@link OtlpMetricDirectWriter} → Doris<br>
 * Log 链路：convert → {@link OtlpLogDirectWriter} → Doris
 */
public final class OtlpIngestService {

    private static final Logger log = LoggerFactory.getLogger(OtlpIngestService.class);

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
        int accepted = 0;
        Map<String, List<DcSpan>> spansByTraceId = new LinkedHashMap<>();
        for (OtelConverter.ConvertedTrace trace : converter.convertTraces(request)) {
            DcSpan span = trace.span();
            if (span == null || span.trace_id == null || span.trace_id.isBlank()) {
                if (gateway.emit(trace.serviceKey(), new TraceEvent(span))) {
                    accepted++;
                }
                continue;
            }
            spansByTraceId.computeIfAbsent(span.trace_id, ignored -> new ArrayList<>()).add(span);
        }
        for (Map.Entry<String, List<DcSpan>> entry : spansByTraceId.entrySet()) {
            if (gateway.emit(entry.getKey(), new TraceBatchEvent(entry.getValue()))) {
                accepted += entry.getValue().size();
            } else {
                log.warn(
                        "Trace batch emit failed traceId={} spans={}",
                        shortTraceId(entry.getKey()),
                        entry.getValue().size());
            }
        }
        tracesIngested.addAndGet(accepted);
        return accepted;
    }

    /** Step 1：OTLP metric → 映射后直接写入 Doris（不经聚合流水线）。 */
    public int ingestMetrics(ExportMetricsServiceRequest request) {
        List<OtelConverter.ConvertedMetric> converted = converter.convertMetrics(request);
        metricDirectWriter.write(converted.stream().map(OtelConverter.ConvertedMetric::line).toList());
        int accepted = converted.size();
        metricsIngested.addAndGet(accepted);
        return accepted;
    }

    /** OTLP log → mapped rows written directly to Doris. */
    public int ingestLogs(ExportLogsServiceRequest request) {
        List<OtelConverter.ConvertedLog> converted = converter.convertLogs(request);
        logDirectWriter.write(converted.stream().map(OtelConverter.ConvertedLog::line).toList());
        int accepted = converted.size();
        logsIngested.addAndGet(accepted);
        return accepted;
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

    private static String shortTraceId(String traceId) {
        if (traceId == null || traceId.length() <= 12) {
            return String.valueOf(traceId);
        }
        return traceId.substring(0, 12) + "..." + traceId.substring(traceId.length() - 6);
    }
}
