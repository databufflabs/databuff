package com.databuff.apm.web.monitor.eval;

/**
 * Single evaluation outcome of one rule (optionally one group of a grouped rule).
 * <p>
 * The {@code metric*} block carries the structured numbers behind the human-readable
 * {@code message}: without it the current value and threshold only survive as formatted
 * text and webhook consumers cannot re-compute or chart them.
 */
public record RuleEvaluationResult(
        boolean triggered,
        String level,
        String message,
        String detectionWay,
        String service,
        String groupKey,
        String metricId,
        String metricLabel,
        String metricUnit,
        Double value,
        Double threshold,
        String comparator) {

    /** Legacy 6-field shape: no structured metric numbers (kept for old call sites). */
    public RuleEvaluationResult(
            boolean triggered,
            String level,
            String message,
            String detectionWay,
            String service,
            String groupKey) {
        this(triggered, level, message, detectionWay, service, groupKey, null, null, null, null, null, null);
    }

    public static RuleEvaluationResult normal() {
        return new RuleEvaluationResult(false, "warning", "", "", "*", "default");
    }
}
