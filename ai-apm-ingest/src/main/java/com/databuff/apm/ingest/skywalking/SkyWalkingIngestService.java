package com.databuff.apm.ingest.skywalking;

import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.ingest.event.TraceBatchEvent;
import com.databuff.apm.ingest.event.TraceEvent;
import com.databuff.apm.ingest.gateway.PipelineGateway;
import com.databuff.apm.ingest.log.OtlpLogDirectWriter;
import com.databuff.apm.ingest.metric.OtlpMetricDirectWriter;
import org.apache.skywalking.apm.network.language.agent.v3.JVMMetricCollection;
import org.apache.skywalking.apm.network.language.agent.v3.SegmentObject;
import org.apache.skywalking.apm.network.logging.v3.LogData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** SkyWalking native protocol ingest entrypoint. */
public final class SkyWalkingIngestService {

    private static final Logger log = LoggerFactory.getLogger(SkyWalkingIngestService.class);

    private final SkyWalkingConverter traceConverter;
    private final SkyWalkingJvmConverter jvmConverter;
    private final SkyWalkingLogConverter logConverter;
    private final PipelineGateway gateway;
    private final OtlpMetricDirectWriter metricDirectWriter;
    private final OtlpLogDirectWriter logDirectWriter;
    private final AtomicLong tracesIngested = new AtomicLong();
    private final AtomicLong metricsIngested = new AtomicLong();
    private final AtomicLong logsIngested = new AtomicLong();

    public SkyWalkingIngestService(
            SkyWalkingConverter traceConverter,
            SkyWalkingJvmConverter jvmConverter,
            SkyWalkingLogConverter logConverter,
            PipelineGateway gateway,
            OtlpMetricDirectWriter metricDirectWriter,
            OtlpLogDirectWriter logDirectWriter) {
        this.traceConverter = traceConverter;
        this.jvmConverter = jvmConverter;
        this.logConverter = logConverter;
        this.gateway = gateway;
        this.metricDirectWriter = metricDirectWriter;
        this.logDirectWriter = logDirectWriter;
    }

    public int ingestSegment(SegmentObject segment) {
        int accepted = 0;
        Map<String, List<DcSpan>> spansByTraceId = new LinkedHashMap<>();
        for (SkyWalkingConverter.ConvertedTrace trace : traceConverter.convertSegment(segment)) {
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
                log.warn("SkyWalking trace batch emit failed traceId={} spans={}", entry.getKey(), entry.getValue().size());
            }
        }
        tracesIngested.addAndGet(accepted);
        return accepted;
    }

    public int ingestJvmMetrics(JVMMetricCollection collection) {
        List<SkyWalkingJvmConverter.ConvertedMetric> converted = jvmConverter.convert(collection);
        metricDirectWriter.write(converted.stream().map(SkyWalkingJvmConverter.ConvertedMetric::line).toList());
        int accepted = converted.size();
        metricsIngested.addAndGet(accepted);
        return accepted;
    }

    public int ingestLog(LogData logData) {
        SkyWalkingLogConverter.ConvertedLog converted = logConverter.convert(logData);
        if (converted == null) {
            return 0;
        }
        logDirectWriter.write(List.of(converted.line()));
        logsIngested.addAndGet(1);
        return 1;
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
}
