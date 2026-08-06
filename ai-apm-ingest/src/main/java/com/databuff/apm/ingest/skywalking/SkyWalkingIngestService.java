package com.databuff.apm.ingest.skywalking;

import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.common.platform.PlatformMetricNames;
import com.databuff.apm.common.platform.PlatformMetrics;
import com.databuff.apm.ingest.event.TraceBatchEvent;
import com.databuff.apm.ingest.event.TraceEvent;
import com.databuff.apm.ingest.gateway.PipelineGateway;
import com.databuff.apm.ingest.log.OtlpLogDirectWriter;
import com.databuff.apm.ingest.metric.OtlpMetricDirectWriter;
import com.databuff.apm.ingest.support.LogRateLimiter;
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

import static com.databuff.apm.common.platform.PlatformMetricNames.KIND_BYTES;
import static com.databuff.apm.common.platform.PlatformMetricNames.KIND_COST_MS;
import static com.databuff.apm.common.platform.PlatformMetricNames.KIND_FAIL;
import static com.databuff.apm.common.platform.PlatformMetricNames.KIND_REQ;
import static com.databuff.apm.common.platform.PlatformMetricNames.PROTO_SW;
import static com.databuff.apm.common.platform.PlatformMetricNames.SIGNAL_LOG;
import static com.databuff.apm.common.platform.PlatformMetricNames.SIGNAL_METRIC;
import static com.databuff.apm.common.platform.PlatformMetricNames.SIGNAL_TRACE;

/** SkyWalking native protocol ingest entrypoint. */
public final class SkyWalkingIngestService {

    private static final Logger log = LoggerFactory.getLogger(SkyWalkingIngestService.class);
    private static final LogRateLimiter TRACE_BATCH_EMIT_LIMITER = new LogRateLimiter(10_000L);

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
        long startMs = System.currentTimeMillis();
        PlatformMetrics.counter(sw(SIGNAL_TRACE, KIND_BYTES)).add(segment.getSerializedSize());
        try {
            int accepted = 0;
            Map<String, List<DcSpan>> spansByTraceId = new LinkedHashMap<>();
            List<SkyWalkingConverter.ConvertedTrace> converted = traceConverter.convertSegment(segment);
            // req = span 个数（非 Segment 请求次数）
            PlatformMetrics.counter(sw(SIGNAL_TRACE, KIND_REQ)).add(converted.size());
            for (SkyWalkingConverter.ConvertedTrace trace : converted) {
                DcSpan span = trace.span();
                if (span == null || span.trace_id == null || span.trace_id.isBlank()) {
                    if (gateway.emit(trace.serviceKey(), new TraceEvent(span))) {
                        accepted++;
                    } else {
                        PlatformMetrics.counter(sw(SIGNAL_TRACE, KIND_FAIL)).inc();
                    }
                    continue;
                }
                spansByTraceId.computeIfAbsent(span.trace_id, ignored -> new ArrayList<>()).add(span);
            }
            for (Map.Entry<String, List<DcSpan>> entry : spansByTraceId.entrySet()) {
                if (gateway.emit(entry.getKey(), new TraceBatchEvent(entry.getValue()))) {
                    accepted += entry.getValue().size();
                } else {
                    PlatformMetrics.counter(sw(SIGNAL_TRACE, KIND_FAIL)).add(entry.getValue().size());
                    if (log.isDebugEnabled()) {
                        log.debug(
                                "SkyWalking trace batch emit failed traceId={} spans={}",
                                entry.getKey(),
                                entry.getValue().size());
                    }
                    long suppressed = TRACE_BATCH_EMIT_LIMITER.record();
                    if (suppressed > 0) {
                        log.warn(
                                "SkyWalking trace batch emit failed {} times in the last 10s (trace pipeline queue full)",
                                suppressed);
                    }
                }
            }
            tracesIngested.addAndGet(accepted);
            return accepted;
        } finally {
            PlatformMetrics.timer(sw(SIGNAL_TRACE, KIND_COST_MS))
                    .add(System.currentTimeMillis() - startMs);
        }
    }

    public int ingestJvmMetrics(JVMMetricCollection collection) {
        long startMs = System.currentTimeMillis();
        PlatformMetrics.counter(sw(SIGNAL_METRIC, KIND_BYTES)).add(collection.getSerializedSize());
        try {
            List<SkyWalkingJvmConverter.ConvertedMetric> converted = jvmConverter.convert(collection);
            // req = metric 条数
            PlatformMetrics.counter(sw(SIGNAL_METRIC, KIND_REQ)).add(converted.size());
            metricDirectWriter.write(converted.stream().map(SkyWalkingJvmConverter.ConvertedMetric::line).toList());
            int accepted = converted.size();
            metricsIngested.addAndGet(accepted);
            return accepted;
        } finally {
            PlatformMetrics.timer(sw(SIGNAL_METRIC, KIND_COST_MS))
                    .add(System.currentTimeMillis() - startMs);
        }
    }

    public int ingestLog(LogData logData) {
        long startMs = System.currentTimeMillis();
        PlatformMetrics.counter(sw(SIGNAL_LOG, KIND_BYTES)).add(logData.getSerializedSize());
        try {
            SkyWalkingLogConverter.ConvertedLog converted = logConverter.convert(logData);
            if (converted == null) {
                return 0;
            }
            // req = log 条数（本协议一次通常 1 条）
            PlatformMetrics.counter(sw(SIGNAL_LOG, KIND_REQ)).add(1);
            logDirectWriter.write(List.of(converted.line()));
            logsIngested.addAndGet(1);
            return 1;
        } finally {
            PlatformMetrics.timer(sw(SIGNAL_LOG, KIND_COST_MS))
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

    private static String sw(String signal, String kind) {
        return PlatformMetricNames.inbound(PROTO_SW, signal, kind);
    }
}
