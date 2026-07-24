package com.databuff.apm.ingest.otel;

import com.databuff.apm.common.meta.OtelAttributeMaps;
import com.databuff.apm.ingest.metric.JvmOtelMetricNormalizer;
import com.databuff.apm.ingest.metric.OtlpMetricRowMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.stream.Collectors;

/** Debug logging for OTLP metric receive → convert → map → write. Enable via {@code logging.level.com.databuff.apm.ingest.otel.OtlpMetricDebugLogger=DEBUG}. */
public final class OtlpMetricDebugLogger {

    private static final Logger log = LoggerFactory.getLogger(OtlpMetricDebugLogger.class);

    private OtlpMetricDebugLogger() {
    }

    public static boolean isDebugEnabled() {
        return log.isDebugEnabled();
    }

    public static void receivedBatch(int resourceCount, int metricPointCount) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug("OTLP metrics batch received resourceMetrics={} dataPoints={}", resourceCount, metricPointCount);
    }

    public static void rawOtlpPoint(
            String service,
            String instrument,
            String metricName,
            Map<String, String> attributes,
            Object value) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug(
                "OTLP raw metric service={} instrument={} name={} attrs={} value={}",
                service,
                instrument,
                metricName,
                formatAttrs(attributes),
                value);
    }

    public static void unsupportedInstrument(String service, String metricName, String reason) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug("OTLP metric skipped service={} name={} reason={}", service, metricName, reason);
    }

    public static void convertedLine(OtlMetricLine line) {
        if (!log.isDebugEnabled() || line == null) {
            return;
        }
        Map<String, String> attrs = OtelAttributeMaps.parse(line.resourceMeta());
        log.debug(
                "OTLP converted line service={} serviceId={} instance={} host={} metric={} value={} attrs={}",
                line.service(),
                line.serviceId(),
                line.serviceInstance(),
                line.tagHost(),
                line.metric(),
                line.value(),
                formatAttrs(attrs));
    }

    public static void mappedRow(OtlMetricLine line, OtlpMetricRowMapper.MappedRow mapped) {
        if (!log.isDebugEnabled() || line == null || mapped == null) {
            return;
        }
        String dorisRow;
        try {
            dorisRow = truncate(new String(mapped.rowBytes()), 500);
        } catch (Exception e) {
            dorisRow = String.valueOf(mapped.fields());
        }
        log.debug(
                "OTLP mapped row service={} rawMetric={} table={} dorisRow={}",
                line.service(),
                line.metric(),
                mapped.table(),
                dorisRow);
    }

    public static void mapSkipped(OtlMetricLine line, String reason) {
        if (!log.isDebugEnabled() || line == null) {
            return;
        }
        Map<String, String> attrs = OtelAttributeMaps.parse(line.resourceMeta());
        String normalized = JvmOtelMetricNormalizer.normalizeIdentifier(line.metric(), attrs)
                .orElse(line.metric());
        log.debug(
                "OTLP map skipped service={} rawMetric={} normalized={} reason={} attrs={}",
                line.service(),
                line.metric(),
                normalized,
                reason,
                formatAttrs(attrs));
    }

    public static void mapSkippedRaw(String metricName, String reason) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug("OTLP map skipped rawJson metric={} reason={}", metricName, reason);
    }

    /** Ingest batch summary after OTLP convert, before direct write. */
    public static void ingestBatch(String service, int totalLines, int jvmLines, int skippedMap) {
        if (!log.isDebugEnabled() || totalLines == 0) {
            return;
        }
        log.debug(
                "[metric-pipeline] RECEIVED service={} totalLines={} jvmLines={} mapSkipped={}",
                service,
                totalLines,
                jvmLines,
                skippedMap);
    }

    /** JVM rows merged for one export batch (one row per instance key). */
    public static void mergedJvmRow(String service, String serviceId, int partialRows, java.util.Collection<String> metricFields) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug(
                "[metric-pipeline] MERGED service={} serviceId={} partialRows={} fields={}",
                service,
                serviceId,
                partialRows,
                metricFields);
    }

    /** Row queued into Doris batch writer. */
    public static void queuedRow(String table, String service, String rowJson) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug(
                "[metric-pipeline] QUEUED table={} service={} row={}",
                table,
                service,
                truncate(rowJson, 800));
    }

    private static String formatAttrs(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return "{}";
        }
        return attributes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }
}
