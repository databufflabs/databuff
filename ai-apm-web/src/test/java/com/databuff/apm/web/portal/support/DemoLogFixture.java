package com.databuff.apm.web.portal.support;

import com.databuff.apm.common.time.ApmTimeZones;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * In-memory log rows for portal log-analysis integration tests.
 * Filters rows by inspecting SQL fragments produced by {@code LogQueryBuilder}.
 */
public final class DemoLogFixture {

    public static final String SERVICE_A = "service-a";
    public static final String SERVICE_A_ID = "9bf61532d56eb7b5";
    public static final String SERVICE_B = "service-b";
    public static final String SERVICE_B_ID = "5457a0119281bb98";
    public static final String INSTANCE_A = "service-a-1";
    public static final String INSTANCE_B = "service-b-1";
    public static final String HOST_A = "demo-host-a";
    public static final String HOST_B = "demo-host-b";
    public static final String TRACE_CHECKOUT = "bc66dbbd29ebadf967e46e26c527b4dc";
    public static final String FROM_TEXT = "2026-07-04 10:50:00";
    public static final String TO_TEXT = "2026-07-04 11:05:00";
    public static final long FROM_MS = ApmTimeZones.wallClockToEpochMilli(FROM_TEXT);
    public static final long TO_MS = ApmTimeZones.wallClockToEpochMilli(TO_TEXT);
    public static final String FROM_TIME_NS = Long.toString(FROM_MS * 1_000_000L);
    public static final String TO_TIME_NS = Long.toString(TO_MS * 1_000_000L);

    private static final Pattern TIME_FROM = Pattern.compile("log_time >= '([^']+)'");
    private static final Pattern TIME_TO = Pattern.compile("log_time < '([^']+)'");
    private static final Pattern TRACE_ID = Pattern.compile("trace_id = '([^']+)'");
    private static final Pattern SPAN_ID = Pattern.compile("span_id = '([^']+)'");
    private static final Pattern SERVICE_ID = Pattern.compile("service_id = '([^']+)'");
    private static final Pattern TIME_NS = Pattern.compile("time_ns = (\\d+)");
    private static final Pattern IN_CLAUSE = Pattern.compile("(\\w+) IN \\(([^)]+)\\)");
    private static final Pattern LIKE_BODY = Pattern.compile("body LIKE '%([^']*)%'");
    private static final Pattern BUCKET_SEC = Pattern.compile("FLOOR\\(time_ns / 1000000000 / (\\d+)\\)");

    private final List<Row> rows;

    public DemoLogFixture() {
        this.rows = buildRows();
    }

    public List<Map<String, Object>> queryRows(String sql, int maxRows) {
        List<Row> filtered = filter(sql);
        if (sql.contains("GROUP BY bucket_epoch_sec")) {
            return trendBuckets(filtered, sql);
        }
        if (sql.contains("SELECT DISTINCT service_id")) {
            return distinctServices(filtered, maxRows);
        }
        if (sql.contains("SELECT DISTINCT service_instance")) {
            return distinctColumn(filtered, "service_instance", maxRows);
        }
        if (sql.contains("SELECT DISTINCT hostname")) {
            return distinctColumn(filtered, "hostname", maxRows);
        }
        if (sql.contains("SELECT DISTINCT severity")) {
            return distinctColumn(filtered, "severity", maxRows);
        }
        if (sql.contains("attributes_json")) {
            return filtered.stream()
                    .sorted((left, right) -> Long.compare(left.timeNs, right.timeNs))
                    .limit(Math.max(1, maxRows))
                    .map(this::toDetailRow)
                    .collect(Collectors.toList());
        }
        return filtered.stream()
                .sorted((left, right) -> Long.compare(left.timeNs, right.timeNs))
                .limit(Math.max(1, maxRows))
                .map(this::toSearchRow)
                .collect(Collectors.toList());
    }

    public long count(String sql) {
        return filter(sql).size();
    }

    private List<Row> filter(String sql) {
        String from = extract(TIME_FROM, sql);
        String to = extract(TIME_TO, sql);
        String traceId = extract(TRACE_ID, sql);
        String spanId = extract(SPAN_ID, sql);
        String serviceId = extract(SERVICE_ID, sql);
        Set<String> serviceIds = inValues(sql, "service_id");
        Set<String> services = inValues(sql, "service");
        Set<String> instances = inValues(sql, "service_instance");
        Set<String> hosts = inValues(sql, "hostname");
        Set<String> severities = inValues(sql, "severity");
        String query = extract(LIKE_BODY, sql);
        Long timeNs = extractLong(TIME_NS, sql);

        List<Row> out = new ArrayList<>();
        for (Row row : rows) {
            if (from != null && row.logTime.compareTo(from) < 0) {
                continue;
            }
            if (to != null && row.logTime.compareTo(to) >= 0) {
                continue;
            }
            if (timeNs != null && row.timeNs != timeNs) {
                continue;
            }
            if (traceId != null && !traceId.equals(row.traceId)) {
                continue;
            }
            if (spanId != null && !spanId.equals(row.spanId)) {
                continue;
            }
            if (serviceId != null && !serviceId.equals(row.serviceId)) {
                continue;
            }
            if (!serviceIds.isEmpty() && !serviceIds.contains(row.serviceId)) {
                continue;
            }
            if (!services.isEmpty() && !services.contains(row.service)) {
                continue;
            }
            if (!instances.isEmpty() && !instances.contains(row.serviceInstance)) {
                continue;
            }
            if (!hosts.isEmpty() && !hosts.contains(row.hostname)) {
                continue;
            }
            if (!severities.isEmpty() && !severities.contains(row.severity)) {
                continue;
            }
            if (query != null && (row.message == null || !row.message.contains(query))) {
                continue;
            }
            if (sql.contains(" AND severity != ''") && row.severity.isEmpty()) {
                continue;
            }
            out.add(row);
        }
        return out;
    }

    private List<Map<String, Object>> trendBuckets(List<Row> filtered, String sql) {
        int bucketSec = 60;
        Matcher matcher = BUCKET_SEC.matcher(sql);
        if (matcher.find()) {
            bucketSec = Integer.parseInt(matcher.group(1));
        }
        Map<Long, Map<String, Long>> buckets = new LinkedHashMap<>();
        for (Row row : filtered) {
            if (row.severity.isEmpty()) {
                continue;
            }
            long bucketEpochSec = (row.timeNs / 1_000_000_000L / bucketSec) * bucketSec;
            buckets.computeIfAbsent(bucketEpochSec, key -> new LinkedHashMap<>())
                    .merge(row.severity, 1L, Long::sum);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<Long, Map<String, Long>> bucket : buckets.entrySet()) {
            for (Map.Entry<String, Long> severity : bucket.getValue().entrySet()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("bucket_epoch_sec", bucket.getKey());
                row.put("severity", severity.getKey());
                row.put("cnt", severity.getValue());
                out.add(row);
            }
        }
        return out;
    }

    private List<Map<String, Object>> distinctServices(List<Row> filtered, int maxRows) {
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Row row : filtered) {
            if (row.serviceId.isEmpty()) {
                continue;
            }
            String key = row.serviceId + "|" + row.service;
            if (!seen.add(key)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("service_id", row.serviceId);
            item.put("service", row.service);
            out.add(item);
            if (out.size() >= maxRows) {
                break;
            }
        }
        return out;
    }

    private List<Map<String, Object>> distinctColumn(List<Row> filtered, String column, int maxRows) {
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Row row : filtered) {
            String value = columnValue(row, column);
            if (value.isEmpty() || !seen.add(value)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put(column, value);
            out.add(item);
            if (out.size() >= maxRows) {
                break;
            }
        }
        return out;
    }

    private Map<String, Object> toSearchRow(Row row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("log_time", row.logTime);
        out.put("time_ns", Long.toString(row.timeNs));
        out.put("hostname", row.hostname);
        out.put("service_instance", row.serviceInstance);
        out.put("service", row.service);
        out.put("service_id", row.serviceId);
        out.put("trace_id", row.traceId);
        out.put("span_id", row.spanId);
        out.put("status", row.severity);
        out.put("message", row.message);
        return out;
    }

    private Map<String, Object> toDetailRow(Row row) {
        Map<String, Object> out = toSearchRow(row);
        out.put("severity_number", row.severityNumber);
        out.put("observed_time_ns", Long.toString(row.timeNs));
        out.put("attributes_json", row.attributesJson);
        out.put("resource_json", row.resourceJson);
        return out;
    }

    private static String columnValue(Row row, String column) {
        return switch (column) {
            case "service_instance" -> row.serviceInstance;
            case "hostname" -> row.hostname;
            case "severity" -> row.severity;
            default -> "";
        };
    }

    private static String extract(Pattern pattern, String sql) {
        Matcher matcher = pattern.matcher(sql);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static Long extractLong(Pattern pattern, String sql) {
        String text = extract(pattern, sql);
        if (text == null) {
            return null;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Set<String> inValues(String sql, String column) {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = IN_CLAUSE.matcher(sql);
        while (matcher.find()) {
            if (!column.equals(matcher.group(1))) {
                continue;
            }
            for (String token : matcher.group(2).split(",")) {
                String text = token.trim();
                if (text.startsWith("'") && text.endsWith("'")) {
                    values.add(text.substring(1, text.length() - 1));
                }
            }
        }
        return values;
    }

    private static List<Row> buildRows() {
        List<Row> out = new ArrayList<>();
        for (int minute = 0; minute < 2; minute++) {
            String logTime = String.format(Locale.ROOT, "2026-07-04 10:%02d:27", 50 + minute);
            long epochMs = ApmTimeZones.wallClockToEpochMilli(logTime);
            long timeNs = epochMs * 1_000_000L;
            out.add(row(logTime, timeNs, HOST_A, INSTANCE_A, SERVICE_A, SERVICE_A_ID, TRACE_CHECKOUT,
                    "span-a-" + minute, "INFO", 9, "Received checkout request orderId=10001 channel=web",
                    "{\"order.id\":\"10001\",\"channel\":\"web\"}",
                    "{\"host.name\":\"" + HOST_A + "\",\"service.name\":\"" + SERVICE_A
                            + "\",\"service.instance.id\":\"" + INSTANCE_A + "\"}"));
            out.add(row(logTime, timeNs + 1, HOST_A, INSTANCE_A, SERVICE_A, SERVICE_A_ID, TRACE_CHECKOUT,
                    "span-a-" + minute, "INFO", 9, "Validating cart contents for user demo-user",
                    "{\"user.id\":\"demo-user\"}",
                    "{\"host.name\":\"" + HOST_A + "\",\"service.name\":\"" + SERVICE_A
                            + "\",\"service.instance.id\":\"" + INSTANCE_A + "\"}"));
            out.add(row(logTime, timeNs + 2, HOST_A, INSTANCE_A, SERVICE_A, SERVICE_A_ID, TRACE_CHECKOUT,
                    "span-a-" + minute, "INFO", 9, "Checkout started orderId=10001",
                    "{\"order.id\":\"10001\"}",
                    "{\"host.name\":\"" + HOST_A + "\",\"service.name\":\"" + SERVICE_A
                            + "\",\"service.instance.id\":\"" + INSTANCE_A + "\"}"));
            out.add(row(logTime, timeNs + 3, HOST_B, INSTANCE_B, SERVICE_B, SERVICE_B_ID, TRACE_CHECKOUT,
                    "span-b-" + minute, "INFO", 9, "Inventory lookup sku DEMO-10001",
                    "{\"sku\":\"DEMO-10001\"}",
                    "{\"host.name\":\"" + HOST_B + "\",\"service.name\":\"" + SERVICE_B
                            + "\",\"service.instance.id\":\"" + INSTANCE_B + "\",\"k8s.namespace.name\":\"demo\"}"));
            out.add(row(logTime, timeNs + 4, HOST_B, INSTANCE_B, SERVICE_B, SERVICE_B_ID, TRACE_CHECKOUT,
                    "span-b-" + minute, "WARN", 13, "Available stock below threshold (2 units)",
                    "{\"order.id\":\"10001\",\"stock\":\"2\"}",
                    "{\"host.name\":\"" + HOST_B + "\",\"service.name\":\"" + SERVICE_B
                            + "\",\"service.instance.id\":\"" + INSTANCE_B + "\",\"k8s.namespace.name\":\"demo\"}"));
            out.add(row(logTime, timeNs + 5, HOST_B, INSTANCE_B, SERVICE_B, SERVICE_B_ID, TRACE_CHECKOUT,
                    "span-b-" + minute, "ERROR", 17, "InsufficientStockException: inventory unavailable for sku DEMO-10001",
                    "{\"sku\":\"DEMO-10001\",\"exception.type\":\"InsufficientStockException\"}",
                    "{\"host.name\":\"" + HOST_B + "\",\"service.name\":\"" + SERVICE_B
                            + "\",\"service.instance.id\":\"" + INSTANCE_B + "\",\"k8s.namespace.name\":\"demo\"}"));
        }
        return List.copyOf(out);
    }

    private static Row row(
            String logTime,
            long timeNs,
            String hostname,
            String serviceInstance,
            String service,
            String serviceId,
            String traceId,
            String spanId,
            String severity,
            int severityNumber,
            String message,
            String attributesJson,
            String resourceJson) {
        return new Row(
                logTime,
                timeNs,
                hostname,
                serviceInstance,
                service,
                serviceId,
                traceId,
                spanId,
                severity,
                severityNumber,
                message,
                attributesJson,
                resourceJson);
    }

    private record Row(
            String logTime,
            long timeNs,
            String hostname,
            String serviceInstance,
            String service,
            String serviceId,
            String traceId,
            String spanId,
            String severity,
            int severityNumber,
            String message,
            String attributesJson,
            String resourceJson) {
    }
}
