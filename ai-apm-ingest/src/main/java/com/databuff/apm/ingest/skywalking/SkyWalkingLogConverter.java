package com.databuff.apm.ingest.skywalking;

import com.databuff.apm.common.time.ApmTimeZones;
import com.databuff.apm.common.util.ServiceKeyUtil;
import com.databuff.apm.ingest.otel.OtlLogLine;
import com.databuff.apm.ingest.trace.remote.TraceDataSources;
import org.apache.skywalking.apm.network.common.v3.KeyStringValuePair;
import org.apache.skywalking.apm.network.logging.v3.LogData;
import org.apache.skywalking.apm.network.logging.v3.LogDataBody;
import org.apache.skywalking.apm.network.logging.v3.TraceContext;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SkyWalking LogData → {@link OtlLogLine}. */
public final class SkyWalkingLogConverter {

    private static final DateTimeFormatter DATETIME = ApmTimeZones.WALL_CLOCK;

    public ConvertedLog convert(LogData logData) {
        if (logData == null || logData.getService().isBlank()) {
            return null;
        }
        String body = logBody(logData.getBody());
        if (body == null || body.isBlank()) {
            return null;
        }
        String serviceName = logData.getService();
        String serviceKey = ServiceKeyUtil.of(serviceName);
        String serviceInstance = logData.getServiceInstance();
        long timeMs = logData.getTimestamp() > 0 ? logData.getTimestamp() : System.currentTimeMillis();
        long timeNs = timeMs * 1_000_000L;
        Map<String, String> attributes = new LinkedHashMap<>();
        if (logData.hasTags()) {
            attributes.putAll(tags(logData.getTags().getDataList()));
        }
        attributes.put(TraceDataSources.META_DATA_SOURCE, TraceDataSources.SKY_WALKING);
        String severity = firstNonBlank(attributes.get("level"), attributes.get("severity"), "INFO");
        TraceContext traceContext = logData.getTraceContext();
        String traceId = traceContext.getTraceId();
        String spanId = "";
        if (!traceContext.getTraceSegmentId().isBlank() && traceContext.getSpanId() >= 0) {
            spanId = SkyWalkingIdUtil.spanId(traceContext.getTraceSegmentId(), traceContext.getSpanId());
        }
        String attributesJson = encodeAttributes(attributes);
        String hostName = firstNonBlank(attributes.get("host.name"), serviceInstance, "unknown");
        OtlLogLine line = new OtlLogLine(
                null,
                timeNs,
                timeNs,
                DATETIME.format(Instant.ofEpochMilli(timeMs)),
                serviceKey,
                serviceName,
                serviceInstance,
                hostName,
                traceId,
                spanId,
                severity,
                severityNumber(severity),
                body,
                attributesJson,
                null);
        return new ConvertedLog(serviceKey, line);
    }

    private static String logBody(LogDataBody body) {
        if (body == null) {
            return null;
        }
        return switch (body.getContentCase()) {
            case TEXT -> body.getText().getText();
            case JSON -> body.getJson().getJson();
            case YAML -> body.getYaml().getYaml();
            case CONTENT_NOT_SET -> null;
        };
    }

    private static Map<String, String> tags(List<KeyStringValuePair> pairs) {
        Map<String, String> out = new LinkedHashMap<>();
        if (pairs == null) {
            return out;
        }
        for (KeyStringValuePair pair : pairs) {
            if (pair.getKey() != null && !pair.getKey().isBlank() && pair.getValue() != null) {
                out.put(pair.getKey(), pair.getValue());
            }
        }
        return out;
    }

    private static String encodeAttributes(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(escape(entry.getKey())).append("\":\"")
                    .append(escape(entry.getValue())).append('"');
        }
        json.append('}');
        return json.toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static int severityNumber(String severity) {
        if (severity == null) {
            return 9;
        }
        return switch (severity.toUpperCase()) {
            case "FATAL" -> 21;
            case "ERROR" -> 17;
            case "WARN", "WARNING" -> 13;
            case "DEBUG" -> 5;
            case "TRACE" -> 1;
            default -> 9;
        };
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public record ConvertedLog(String serviceKey, OtlLogLine line) {
    }
}
