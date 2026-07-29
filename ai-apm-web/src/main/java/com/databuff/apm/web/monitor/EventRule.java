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

    public static final String FLUCTUATE_VAL_UP = "valUp";
    public static final String FLUCTUATE_VAL_DOWN = "valDown";
    public static final String FLUCTUATE_YOY_UP = "yoyUp";
    public static final String FLUCTUATE_YOY_DOWN = "yoyDown";

    public EventRule withEnabled(boolean newEnabled) {
        return new EventRule(
                id, ruleName, classify, detectionWay, service, metric,
                threshold, comparator, newEnabled, queryJson, Instant.now());
    }
}
