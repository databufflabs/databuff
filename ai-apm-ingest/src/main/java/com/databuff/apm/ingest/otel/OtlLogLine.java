package com.databuff.apm.ingest.otel;

import com.databuff.apm.common.serde.ReusableJson;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** Step 1 product: OTLP log record mapped to a Doris {@code log_dc_record} row. */
public record OtlLogLine(
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

    public byte[] toJsonBytes() throws JsonProcessingException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("log_time", logTime);
        row.put("service_id", serviceId);
        row.put("service", service);
        row.put("trace_id", traceId == null ? "" : traceId);
        row.put("span_id", spanId == null ? "" : spanId);
        row.put("service_instance", serviceInstance == null ? "" : serviceInstance);
        row.put("hostname", hostname == null ? "" : hostname);
        row.put("severity", severity == null ? "UNSPECIFIED" : severity);
        row.put("severity_number", severityNumber);
        row.put("body", body == null ? "" : body);
        putIfPresent(row, "attributes_json", attributesJson);
        putIfPresent(row, "resource_json", resourceJson);
        row.put("time_ns", timeNs);
        row.put("observed_time_ns", observedTimeNs);
        return ReusableJson.writeValueAsBytes(JSON, row);
    }

    private static void putIfPresent(Map<String, Object> row, String key, String value) {
        if (value != null && !value.isBlank()) {
            row.put(key, value);
        }
    }
}
