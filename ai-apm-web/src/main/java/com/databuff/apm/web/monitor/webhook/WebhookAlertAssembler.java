package com.databuff.apm.web.monitor.webhook;

import com.databuff.apm.web.monitor.Alarm;
import com.databuff.apm.web.monitor.pipeline.EventRecord;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Assembles the unified webhook alert payload from the {@code dispatch(alarm, event)}
 * objects — the single built-in format every receiver gets ({@code custom} envelopes wrap
 * it, they do not replace it). Consumers can grade, group and chart alerts without
 * parsing the Chinese message text: alarmId/eventId dual traceability keys, rule/metric
 * structured blocks, normalized severity/status, fingerprint dedup key.
 * <p>
 * Value normalization (applied here so every receiver sees the same vocabulary):
 * <ul>
 *   <li>severity → critical/high/medium/low; unknown levels (incl. blank) land on medium.</li>
 *   <li>status → firing/resolved; every non-terminal DataBuff status stays firing.</li>
 * </ul>
 * Empty fields are omitted instead of serialized as {@code null} (e.g. no metric numbers
 * → no {@code metric} block; unresolved → no {@code resolvedAt}).
 */
@Component
public class WebhookAlertAssembler {

    private static final int TITLE_MAX_LENGTH = 64;

    /** Dedup/fingerprint key: {@code rule:{ruleId}:service:{groupKey}}. */
    public static String fingerprint(Alarm alarm, EventRecord event) {
        long ruleId = event != null ? event.ruleId() : alarm.policyId();
        String groupKey = event != null && event.groupKey() != null && !event.groupKey().isBlank()
                ? event.groupKey()
                : alarm.service();
        return "rule:" + ruleId + ":service:" + groupKey;
    }

    public Map<String, Object> assemble(Alarm alarm, EventRecord event, String source) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String deliveryStatus = deliveryStatus(alarm, event);
        put(payload, "alarmId", alarm.id());
        put(payload, "eventId", event == null ? null : event.id());
        put(payload, "title", title(alarm.message()));
        put(payload, "description", alarm.message());
        put(payload, "severity", severity(alarm.level()));
        put(payload, "status", deliveryStatus);
        put(payload, "source", source == null || source.isBlank() ? "databuff-apm" : source);
        put(payload, "service", alarm.service());
        put(payload, "groupKey", event == null ? null : event.groupKey());
        put(payload, "detectionWay", alarm.detectionWay());
        put(payload, "rule", rule(event));
        put(payload, "metric", metric(event));
        put(payload, "fingerprint", fingerprint(alarm, event));
        put(payload, "triggeredAt", iso(alarm.triggeredAt()));
        put(payload, "resolvedAt", "resolved".equals(deliveryStatus)
                ? iso(alarm.resolvedAt() != null
                        ? alarm.resolvedAt()
                        : event == null ? null : event.triggeredAt())
                : null);
        payload.put("silenced", event != null && event.silenced());
        put(payload, "tags", tags(alarm, event));
        return payload;
    }

    /** Grades critical/high/medium/low; unknown levels (incl. blank) land on medium. */
    static String severity(Object raw) {
        String value = raw == null ? "" : String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (value.matches("5|disaster|fatal|critical|page|p0|sev0|sev1")) {
            return "critical";
        }
        if (value.matches("4|high|error|major|p1|sev2")) {
            return "high";
        }
        if (value.matches("0|1|info|information|low|notice|p3|p4|sev4")) {
            return "low";
        }
        return "medium";
    }

    /** firing or resolved only; every non-terminal DataBuff status stays firing. */
    static String status(Object raw) {
        String value = raw == null ? "" : String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if ("resolved".equals(value) || "ok".equals(value) || "closed".equals(value)) {
            return "resolved";
        }
        return "firing";
    }

    /**
     * The monitor currently stores every threshold hit as a one-shot resolved Alarm. The
     * EventRecord is therefore authoritative for outbound lifecycle state: a trigger must
     * reach receivers as firing, while a recover/normal event is resolved.
     */
    private static String deliveryStatus(Alarm alarm, EventRecord event) {
        if (event != null) {
            if (EventRecord.STATUS_TRIGGER.equals(event.status())) {
                return "firing";
            }
            if (EventRecord.STATUS_RECOVER.equals(event.status())
                    || EventRecord.STATUS_NORMAL.equals(event.status())) {
                return "resolved";
            }
        }
        return status(alarm.status());
    }

    private static Map<String, Object> rule(EventRecord event) {
        if (event == null) {
            return null;
        }
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("id", event.ruleId());
        rule.put("name", event.ruleName());
        return rule;
    }

    private static Map<String, Object> metric(EventRecord event) {
        if (event == null || !event.hasMetric()) {
            return null;
        }
        Map<String, Object> metric = new LinkedHashMap<>();
        put(metric, "id", event.metricId());
        put(metric, "label", event.metricLabel());
        put(metric, "unit", event.metricUnit());
        put(metric, "value", event.value());
        put(metric, "threshold", event.threshold());
        put(metric, "comparator", event.comparator());
        return metric;
    }

    private static Map<String, Object> tags(Alarm alarm, EventRecord event) {
        Map<String, Object> tags = new LinkedHashMap<>();
        if (alarm.service() != null) {
            tags.put("service", alarm.service());
        }
        if (event != null && event.ruleName() != null) {
            tags.put("ruleName", event.ruleName());
        }
        return tags.isEmpty() ? null : tags;
    }

    private static String title(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        String firstLine = message.split("\n", 2)[0].trim();
        return firstLine.length() <= TITLE_MAX_LENGTH
                ? firstLine
                : firstLine.substring(0, TITLE_MAX_LENGTH);
    }

    private static String iso(Instant instant) {
        return instant == null ? null : DateTimeFormatter.ISO_INSTANT.format(instant);
    }

    /** Puts the entry only when the value carries information (nulls are omitted). */
    private static void put(Map<String, Object> payload, String key, Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }
}
