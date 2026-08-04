package com.databuff.apm.web.monitor;

import java.time.Instant;

public record EventRule(
        long id,
        String ruleName,
        String classify,
        String detectionWay,
        String service,
        String metric,
        double threshold,
        String comparator,
        boolean enabled,
        String queryJson,
        Instant updatedAt) {

    public static final String CLASSIFY_SINGLE = "singleMetric";
    public static final String WAY_THRESHOLD = "threshold";
    public static final String WAY_MUTATION = "mutation";
    public static final String METRIC_ERROR_RATE = "error_rate";
    public static final String METRIC_REQUEST_COUNT = "request_count";
    public static final String COMPARATOR_GT = "gt";
    public static final String COMPARATOR_GTE = "gte";
    public static final String COMPARATOR_LT = "lt";
    public static final String COMPARATOR_LTE = "lte";

    public static final String LEVEL_CRITICAL = "critical";
    public static final String LEVEL_WARNING = "warning";

    public static final String FLUCTUATE_VAL_UP = "valUp";
    public static final String FLUCTUATE_VAL_DOWN = "valDown";
    public static final String FLUCTUATE_YOY_UP = "yoyUp";
    public static final String FLUCTUATE_YOY_DOWN = "yoyDown";

    public EventRule withEnabled(boolean newEnabled) {
        return new EventRule(
                id, ruleName, classify, detectionWay, service, metric,
                threshold, comparator, newEnabled, queryJson, Instant.now());
    }

    public EventRule withComparator(String newComparator) {
        return new EventRule(
                id, ruleName, classify, detectionWay, service, metric,
                threshold, normalizeComparator(newComparator), enabled, queryJson, updatedAt);
    }

    /**
     * Normalizes UI symbols ({@code >}/{@code >=}/{@code <}/{@code <=}) and aliases to
     * {@link #COMPARATOR_GT}/{@link #COMPARATOR_GTE}/{@link #COMPARATOR_LT}/{@link #COMPARATOR_LTE}.
     * Blank input defaults to {@link #COMPARATOR_GT}.
     */
    public static String normalizeComparator(String comparator) {
        if (comparator == null || comparator.isBlank()) {
            return COMPARATOR_GT;
        }
        return switch (comparator.trim()) {
            case ">", "gt" -> COMPARATOR_GT;
            case ">=", "gte" -> COMPARATOR_GTE;
            case "<", "lt" -> COMPARATOR_LT;
            case "<=", "lte" -> COMPARATOR_LTE;
            default -> comparator.trim();
        };
    }

    /** True when the comparator means "metric is below the threshold". */
    public static boolean isLowerBoundComparator(String comparator) {
        String normalized = normalizeComparator(comparator);
        return COMPARATOR_LT.equals(normalized) || COMPARATOR_LTE.equals(normalized);
    }
}
