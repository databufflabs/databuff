package com.databuff.apm.web.monitor.eval;

import com.databuff.apm.web.monitor.EventRule;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class EventRulePayloadParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EventRulePayloadParser() {
    }

    public record DerivedFields(
            String classify,
            String detectionWay,
            String service,
            String metric,
            double threshold,
            String comparator,
            String queryJson) {
    }

    public static DerivedFields derive(Map<String, Object> body) {
        String queryJson = sanitizeQueryJson(serializeQuery(body.get("query")));
        Map<String, Object> query = parseQuery(queryJson);
        if (query.isEmpty()) {
            return new DerivedFields(
                    EventRule.CLASSIFY_SINGLE,
                    normalizeWay(stringValue(body.get("detectionWay"), stringValue(body.get("type"), EventRule.WAY_THRESHOLD))),
                    stringValue(body.get("service"), "*"),
                    stringValue(body.get("metric"), EventRule.METRIC_ERROR_RATE),
                    doubleValue(body.get("threshold"), 0.05),
                    stringValue(body.get("comparator"), EventRule.COMPARATOR_GT),
                    queryJson);
        }
        Map<String, Object> primary = primaryQueryItem(query);
        String way = normalizeWay(stringValue(primary.get("way"), EventRule.WAY_THRESHOLD));
        String service = extractService(primary);
        String metric = extractMetric(primary);
        double threshold = extractThreshold(primary, body);
        String comparator = extractComparator(primary);
        return new DerivedFields(EventRule.CLASSIFY_SINGLE, way, service, metric, threshold, comparator, queryJson);
    }

    /**
     * Group-by tag keys configured on the primary metric query item.
     */
    @SuppressWarnings("unchecked")
    public static List<String> extractPrimaryGroupByFields(Map<String, Object> queryItem) {
        if (queryItem == null || queryItem.isEmpty()) {
            return List.of();
        }
        Object metricObj = queryItem.get("A");
        if (!(metricObj instanceof Map<?, ?> metric)) {
            return List.of();
        }
        Object by = ((Map<String, Object>) metric).get("by");
        if (!(by instanceof List<?> byList)) {
            return List.of();
        }
        List<String> fields = new ArrayList<>();
        for (Object field : byList) {
            if (field == null) {
                continue;
            }
            String value = String.valueOf(field).trim();
            if (!value.isEmpty()) {
                fields.add(value);
            }
        }
        return fields;
    }

    /**
     * Collects distinct group-by tag keys configured in a monitor rule query payload.
     */
    @SuppressWarnings("unchecked")
    public static List<String> extractGroupByFields(String queryJson) {
        Map<String, Object> query = parseQuery(queryJson);
        if (query.isEmpty()) {
            return List.of();
        }
        Set<String> fields = new LinkedHashSet<>();
        for (Object item : query.values()) {
            if (item instanceof Map<?, ?> queryItem) {
                collectGroupByFields((Map<String, Object>) queryItem, fields);
            }
        }
        return new ArrayList<>(fields);
    }

    @SuppressWarnings("unchecked")
    private static void collectGroupByFields(Map<String, Object> queryItem, Set<String> fields) {
        for (Map.Entry<String, Object> entry : queryItem.entrySet()) {
            String key = entry.getKey();
            if (key.length() != 1) {
                continue;
            }
            char letter = key.charAt(0);
            if (letter < 'A' || letter > 'Z') {
                continue;
            }
            if (!(entry.getValue() instanceof Map<?, ?> metric)) {
                continue;
            }
            Object by = ((Map<String, Object>) metric).get("by");
            if (!(by instanceof List<?> byList)) {
                continue;
            }
            for (Object field : byList) {
                if (field == null) {
                    continue;
                }
                String value = String.valueOf(field).trim();
                if (!value.isEmpty()) {
                    fields.add(value);
                }
            }
        }
    }

    public static Map<String, Object> parseQuery(String queryJson) {
        if (queryJson == null || queryJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = MAPPER.readValue(queryJson, new TypeReference<>() {
            });
            return parsed == null ? Map.of() : parsed;
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    public static String serializeQuery(Object query) {
        if (query == null) {
            return null;
        }
        if (query instanceof String text) {
            return text.isBlank() ? null : text;
        }
        try {
            return MAPPER.writeValueAsString(query);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> primaryQueryItem(Map<String, Object> query) {
        Object item = query.get("1");
        if (item instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    static String extractService(Map<String, Object> queryItem) {
        Object metricObj = queryItem.get("A");
        if (!(metricObj instanceof Map<?, ?> metric)) {
            return "*";
        }
        Object fromObj = metric.get("from");
        if (!(fromObj instanceof List<?> fromList)) {
            return "*";
        }
        for (Object filterObj : fromList) {
            if (!(filterObj instanceof Map<?, ?> filter)) {
                continue;
            }
            if ("service".equals(String.valueOf(filter.get("type")))
                    || "service".equals(String.valueOf(filter.get("left")))) {
                Object value = filter.get("value");
                if (value == null) {
                    value = filter.get("right");
                }
                String service = stringValue(value, null);
                if (service != null) {
                    return service;
                }
            }
        }
        return "*";
    }

    @SuppressWarnings("unchecked")
    public static String extractMetric(Map<String, Object> queryItem) {
        Object metricObj = queryItem.get("A");
        if (metricObj instanceof Map<?, ?> metric) {
            String name = stringValue(metric.get("metric"), EventRule.METRIC_ERROR_RATE);
            return mapMetricName(name);
        }
        return EventRule.METRIC_ERROR_RATE;
    }

    public static String extractMetricIdentifier(EventRule rule) {
        Map<String, Object> query = parseQuery(rule.queryJson());
        return extractMetricIdentifier(rule, primaryQueryItem(query));
    }

    public static String extractMetricIdentifier(EventRule rule, Map<String, Object> primary) {
        Object metricObj = primary.get("A");
        if (metricObj instanceof Map<?, ?> metric) {
            String name = stringValue(metric.get("metric"), null);
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        if (EventRule.METRIC_REQUEST_COUNT.equals(rule.metric())) {
            return "service.cnt";
        }
        return "service.error.pct";
    }

    public static String extractViewUnit(Map<String, Object> queryItem) {
        if (queryItem == null || queryItem.isEmpty()) {
            return null;
        }
        return stringValue(queryItem.get("view_unit"), null);
    }

    /**
     * Critical + optional warning thresholds from a query item. Missing / blank / null values are
     * absent ({@link Double#isNaN()}). Front-end stores plain numbers under
     * {@code thresholds.critical}/{@code thresholds.warning}; nested API may use
     * {@code {value, comparator}}.
     */
    public record ThresholdLevels(double critical, double warning) {
        public boolean hasCritical() {
            return !Double.isNaN(critical);
        }

        public boolean hasWarning() {
            return !Double.isNaN(warning);
        }
    }

    static double extractThreshold(Map<String, Object> queryItem, Map<String, Object> body) {
        ThresholdLevels levels = extractThresholdLevels(queryItem);
        if (levels.hasCritical()) {
            return levels.critical();
        }
        return doubleValue(body.get("threshold"), 0.05);
    }

    public static ThresholdLevels extractThresholdLevels(Map<String, Object> queryItem) {
        if (queryItem == null || queryItem.isEmpty()) {
            return new ThresholdLevels(Double.NaN, Double.NaN);
        }
        Object thresholdsObj = queryItem.get("thresholds");
        if (!(thresholdsObj instanceof Map<?, ?> thresholds)) {
            return new ThresholdLevels(Double.NaN, Double.NaN);
        }
        return new ThresholdLevels(
                parseThresholdValue(thresholds.get("critical")),
                parseThresholdValue(thresholds.get("warning")));
    }

    private static double parseThresholdValue(Object raw) {
        if (raw == null) {
            return Double.NaN;
        }
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        if (raw instanceof Map<?, ?> map) {
            Object value = map.get("value");
            if (value instanceof Number number) {
                return number.doubleValue();
            }
            if (value != null) {
                return parseThresholdValue(value);
            }
            return Double.NaN;
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            return Double.NaN;
        }
    }

    @SuppressWarnings("unchecked")
    static String extractComparator(Map<String, Object> queryItem) {
        // Front-end ruleSetting stores comparison at the query-item root (e.g. "comparison": "<").
        String fromComparison = stringValue(queryItem.get("comparison"), null);
        if (fromComparison != null) {
            return normalizeComparator(fromComparison);
        }
        Object thresholdsObj = queryItem.get("thresholds");
        if (thresholdsObj instanceof Map<?, ?> thresholds) {
            Object critical = thresholds.get("critical");
            if (critical instanceof Map<?, ?> criticalMap) {
                String comparator = stringValue(criticalMap.get("comparator"), null);
                if (comparator != null) {
                    return normalizeComparator(comparator);
                }
            }
        }
        return EventRule.COMPARATOR_GT;
    }

    public static String normalizeWay(String way) {
        if (EventRule.WAY_MUTATION.equals(way)) {
            return EventRule.WAY_MUTATION;
        }
        return EventRule.WAY_THRESHOLD;
    }

    public static String sanitizeQueryJson(String queryJson) {
        Map<String, Object> query = parseQuery(queryJson);
        if (query.isEmpty()) {
            return queryJson;
        }
        Map<String, Object> sanitized = new java.util.LinkedHashMap<>();
        Object item = query.get("1");
        if (item instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> primary = sanitizePrimaryQueryItem((Map<String, Object>) map);
            sanitized.put("1", primary);
        }
        try {
            return MAPPER.writeValueAsString(sanitized);
        } catch (JsonProcessingException e) {
            return queryJson;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sanitizePrimaryQueryItem(Map<String, Object> item) {
        Map<String, Object> sanitized = new java.util.LinkedHashMap<>(item);
        sanitized.remove("expr");
        sanitized.remove("exprName");
        sanitized.remove("no_data_timeframe");
        sanitized.remove("require_full_window");
        sanitized.remove("evaluation_delay");
        for (char letter = 'B'; letter <= 'Z'; letter++) {
            sanitized.remove(String.valueOf(letter));
        }
        Object metricObj = sanitized.get("A");
        if (metricObj instanceof Map<?, ?> metricMap) {
            Map<String, Object> metric = new java.util.LinkedHashMap<>((Map<String, Object>) metricMap);
            sanitized.put("A", metric);
        }
        sanitized.put("way", normalizeWay(stringValue(sanitized.get("way"), EventRule.WAY_THRESHOLD)));
        return sanitized;
    }

    static String mapMetricName(String metric) {
        String lower = metric == null ? "" : metric.toLowerCase();
        if (lower.contains("error") || lower.contains("fail")) {
            return EventRule.METRIC_ERROR_RATE;
        }
        if (lower.contains("request") || lower.contains("throughput") || lower.endsWith(".cnt")) {
            return EventRule.METRIC_REQUEST_COUNT;
        }
        return EventRule.METRIC_ERROR_RATE;
    }

    static String normalizeComparator(String comparator) {
        return EventRule.normalizeComparator(comparator);
    }

    public static long lookbackMinutes(Map<String, Object> queryItem) {
        long periodSec = longValue(queryItem.get("period"), 300);
        return Math.max(1, periodSec / 60);
    }

    /**
     * Comparison window length (millis) for mutation (同环比) rules. The front-end stores
     * {@code comparePeriod} in seconds; when absent it falls back to the rule's own
     * {@code lookbackMillis} so the previous window immediately precedes the current one
     * (legacy behavior).
     */
    public static long extractComparePeriodMillis(Map<String, Object> queryItem, long lookbackMillis) {
        long comparePeriodSec = longValue(queryItem.get("comparePeriod"), -1);
        if (comparePeriodSec <= 0) {
            return lookbackMillis;
        }
        return comparePeriodSec * 1000L;
    }

    /**
     * Mutation direction mode configured by the front-end: {@code valUp/valDown} compare
     * absolute change, {@code yoyUp/yoyDown} compare ratio (同比). Defaults to
     * {@code valUp} for older payloads.
     */
    public static String extractFluctuate(Map<String, Object> queryItem) {
        String fluctuate = stringValue(queryItem.get("fluctuate"), EventRule.FLUCTUATE_VAL_UP);
        return switch (fluctuate) {
            case EventRule.FLUCTUATE_VAL_UP, EventRule.FLUCTUATE_VAL_DOWN,
                    EventRule.FLUCTUATE_YOY_UP, EventRule.FLUCTUATE_YOY_DOWN -> fluctuate;
            default -> EventRule.FLUCTUATE_VAL_UP;
        };
    }

    public static boolean isYoyFluctuate(String fluctuate) {
        return EventRule.FLUCTUATE_YOY_UP.equals(fluctuate) || EventRule.FLUCTUATE_YOY_DOWN.equals(fluctuate);
    }

    private static String stringValue(Object primary, String fallback) {
        String value = primary == null ? null : String.valueOf(primary).trim();
        if (value != null && !value.isEmpty()) {
            return value;
        }
        return fallback;
    }

    private static double doubleValue(Object value, double defaultValue) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static long longValue(Object value, long defaultValue) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof Boolean bool) {
            return bool ? 1 : 0;
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
