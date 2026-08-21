package com.databuff.apm.ingest.otel;

import com.databuff.apm.common.serde.ReusableJson;
import com.databuff.apm.common.storage.DorisVarcharLimits;
import com.databuff.apm.ingest.support.TruncationWarn;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Step 1 product: OTLP log record mapped to a Doris {@code log_dc_record} row. */
public record OtlLogLine(
        String logId,
        long timeNs,
        long observedTimeNs,
        String logTime,
        String serviceId,
        String service,
        String serviceInstance,
        String hostname,
        String traceId,
        String spanId,
        String severity,
        int severityNumber,
        String body,
        String attributesJson,
        String resourceJson) {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public OtlLogLine {
        // OTel logs carry no per-record id; synthesize a 128-bit random id so the detail panel can
        // pin the exact row (time_ns is not unique on the DUPLICATE KEY table — see GitHub #73).
        // ThreadLocalRandom (not SecureRandom) — no contention, no crypto cost, 2^128 collision
        // space is safe for any realistic ingest volume.
        if (logId == null || logId.isBlank()) {
            logId = randomHex32();
        }
    }

    private static String randomHex32() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        char[] c = new char[32];
        fillHex(c, 0, r.nextLong());
        fillHex(c, 16, r.nextLong());
        return new String(c);
    }

    private static void fillHex(char[] c, int off, long v) {
        for (int i = 0; i < 16; i++) {
            c[off + 15 - i] = HEX[(int) (v >>> (i * 4)) & 0xf];
        }
    }

    public byte[] toJsonBytes() throws JsonProcessingException {
        return toJsonBytes(DorisVarcharLimits.LOG_BODY);
    }

    /** @param bodyMaxLength max {@link String#length()} for {@code body} (configurable soft cap) */
    public byte[] toJsonBytes(int bodyMaxLength) throws JsonProcessingException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("log_id", logId);
        row.put("log_time", logTime);
        row.put("service_id", serviceId);
        row.put("service", service);
        row.put("trace_id", traceId == null ? "" : traceId);
        row.put("span_id", spanId == null ? "" : spanId);
        row.put("service_instance", serviceInstance == null ? "" : serviceInstance);
        row.put("hostname", hostname == null ? "" : hostname);
        row.put("severity", severity == null ? "UNSPECIFIED" : severity);
        row.put("severity_number", severityNumber);
        row.put("body", truncateBody(body == null ? "" : body, bodyMaxLength));
        putIfPresent(row, "attributes_json",
                DorisVarcharLimits.truncate(attributesJson, DorisVarcharLimits.JSON_BLOB));
        putIfPresent(row, "resource_json",
                DorisVarcharLimits.truncate(resourceJson, DorisVarcharLimits.JSON_BLOB));
        row.put("time_ns", timeNs);
        row.put("observed_time_ns", observedTimeNs);
        return ReusableJson.writeValueAsBytes(JSON, row);
    }

    private String truncateBody(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        TruncationWarn.logBodyTruncated(service, value.length(), maxLength);
        return DorisVarcharLimits.truncate(value, maxLength);
    }

    private static void putIfPresent(Map<String, Object> row, String key, String value) {
        if (value != null && !value.isBlank()) {
            row.put(key, value);
        }
    }
}
