package com.databuff.apm.common.storage;

/**
 * Doris VARCHAR upper bounds for long text columns (aligned with {@code databuff.sql} / V004).
 * Ingest truncates to these limits so a single oversized value cannot fail an entire Stream Load batch.
 */
public final class DorisVarcharLimits {

    /** HTTP / connection-pool URLs ({@code url}, {@code meta.http.url}, {@code connectionPoolUrl}). */
    public static final int URL = 4096;

    /** Span {@code resource} and {@code meta.http.url} may hold full URLs. */
    public static final int SPAN_RESOURCE = 4096;

    /** Metric AGGREGATE KEY text tags: resource / sql / command / topic / etc. */
    public static final int RESOURCE = 1024;

    public static final int SQL_CONTENT = 1024;

    /** Log attribute / resource JSON blobs. */
    public static final int JSON_BLOB = 15000;

    public static final int SPAN_META = 10000;

    public static final int SPAN_METRICS = 1000;

    public static final int LOG_BODY = 65533;

    private DorisVarcharLimits() {
    }

    public static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /** Truncates metric tag values known to be long-text columns in Doris. */
    public static String truncateMetricTag(String column, String value) {
        if (value == null || column == null) {
            return value;
        }
        return switch (column) {
            case "url", "connectionPoolUrl" -> truncate(value, URL);
            case "sqlContent" -> truncate(value, SQL_CONTENT);
            case "resource", "rootResource", "parentResource", "command", "topic" ->
                    truncate(value, RESOURCE);
            default -> value;
        };
    }
}
