package com.databuff.apm.demo.fault;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** One auditable configuration transition shared by Kafka events and APM logs. */
public record DemoConfigurationChange(
        String faultRunId,
        Action action,
        Instant occurredAt) {

    public static final String CHANGE_KEY = "order.detail.cache.ttl-seconds";

    public enum Action {
        APPLIED,
        AUTO_REVERTED
    }

    public String eventId() {
        return "change-" + faultRunId + (action == Action.APPLIED ? "-applied" : "-reverted");
    }

    public String fingerprint() {
        return "fp:demo-change:" + faultRunId + ":service-b:order-cache-ttl";
    }

    public String title() {
        return action == Action.APPLIED
                ? "service-b 订单详情缓存 TTL 300s → 0s"
                : "service-b 订单详情缓存 TTL 0s → 300s";
    }

    public String status() {
        return action == Action.APPLIED ? "firing" : "resolved";
    }

    public String actionTag() {
        return action == Action.APPLIED ? "applied" : "auto_reverted";
    }

    public String logEvent() {
        return action == Action.APPLIED ? "CONFIG_APPLIED" : "CONFIG_AUTO_REVERTED";
    }

    public String beforeValue() {
        return action == Action.APPLIED ? "300" : "0";
    }

    public String afterValue() {
        return action == Action.APPLIED ? "0" : "300";
    }

    public String configVersion() {
        return action == Action.APPLIED
                ? DemoFaultSnapshot.FAULT_CONFIG_VERSION
                : DemoFaultSnapshot.NORMAL_CONFIG_VERSION;
    }

    public Map<String, String> tags() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("event_kind", "config_change");
        tags.put("change_action", actionTag());
        tags.put("change_key", CHANGE_KEY);
        tags.put("before", beforeValue());
        tags.put("after", afterValue());
        tags.put("config_version", configVersion());
        tags.put("fault_run_id", faultRunId);
        tags.put("env", "demo");
        tags.put("transport", "kafka");
        return tags;
    }

    public String toKafkaJson() {
        StringBuilder json = new StringBuilder(640);
        json.append('{');
        field(json, "id", eventId());
        field(json, "title", title());
        field(json, "description", "DataBuff demo configuration change: " + CHANGE_KEY
                + " " + beforeValue() + " -> " + afterValue());
        field(json, "severity", "info");
        field(json, "status", status());
        field(json, "source", "DataBuff Demo Change");
        field(json, "service", "service-b");
        field(json, "host", "demo-host-b");
        field(json, "fingerprint", fingerprint());
        field(json, "occurredAt", occurredAt.toString());
        json.append(",\"tags\":{");
        int index = 0;
        for (Map.Entry<String, String> entry : tags().entrySet()) {
            if (index++ > 0) {
                json.append(',');
            }
            quoted(json, entry.getKey());
            json.append(':');
            quoted(json, entry.getValue());
        }
        json.append("}}");
        return json.toString();
    }

    private static void field(StringBuilder json, String key, String value) {
        if (json.length() > 1) {
            json.append(',');
        }
        quoted(json, key);
        json.append(':');
        quoted(json, value);
    }

    static void quoted(StringBuilder json, String value) {
        json.append('"');
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            switch (current) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (current < 0x20) {
                        json.append(String.format("\\u%04x", (int) current));
                    } else {
                        json.append(current);
                    }
                }
            }
        }
        json.append('"');
    }
}
