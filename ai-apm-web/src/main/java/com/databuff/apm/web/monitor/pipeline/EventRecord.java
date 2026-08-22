package com.databuff.apm.web.monitor.pipeline;

import java.time.Instant;

/**
 * Raw monitor event, the information-richest stop of the alert pipeline.
 * <p>
 * The {@code metric*} block carries the structured numbers behind {@code message}
 * (current value, breached threshold, comparator) so webhook payloads can expose
 * them as data instead of formatted text.
 */
public record EventRecord(
        String id,
        long ruleId,
        String ruleName,
        String service,
        String detectionWay,
        String level,
        String status,
        String message,
        String groupKey,
        boolean silenced,
        Instant triggeredAt,
        String metricId,
        String metricLabel,
        String metricUnit,
        Double value,
        Double threshold,
        String comparator) {

    public static final String STATUS_TRIGGER = "trigger";
    public static final String STATUS_RECOVER = "recover";
    public static final String STATUS_NORMAL = "normal";

    /** Legacy 11-field shape without structured metric numbers (kept for old call sites). */
    public EventRecord(
            String id,
            long ruleId,
            String ruleName,
            String service,
            String detectionWay,
            String level,
            String status,
            String message,
            String groupKey,
            boolean silenced,
            Instant triggeredAt) {
        this(id, ruleId, ruleName, service, detectionWay, level, status, message, groupKey,
                silenced, triggeredAt, null, null, null, null, null, null);
    }

    public boolean isAbnormal() {
        return STATUS_TRIGGER.equals(status) && !silenced;
    }

    public boolean hasMetric() {
        return metricId != null && !metricId.isBlank() || value != null || threshold != null;
    }
}
