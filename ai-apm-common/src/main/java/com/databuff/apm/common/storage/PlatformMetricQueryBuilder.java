package com.databuff.apm.common.storage;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SQL builder for self-monitoring table {@link DorisTableNames#METRIC_PLATFORM}.
 * Generic filters: metrics / metricPrefixes / component / instance / dim + groupBy + value mode.
 */
public final class PlatformMetricQueryBuilder {

    public static final Set<String> GROUP_COLUMNS = Set.of("metric", "instance", "dim", "component");
    public static final Set<String> VALUE_MODES = Set.of(
            "auto", "cnt", "sum", "avg", "max", "min", "gauge");

    private static final int MAX_LIST = 200;
    private static final int MIN_STEP_SEC = 60;
    private static final int MAX_STEP_SEC = 3600;

    private PlatformMetricQueryBuilder() {
    }

    public static String seriesSql(
            String database,
            long fromMillis,
            long toMillis,
            List<String> metrics,
            List<String> metricPrefixes,
            List<String> metricSuffixes,
            List<String> components,
            List<String> instances,
            List<String> dims,
            List<String> groupBy,
            String valueMode,
            int stepSeconds) {
        int bucketSec = clampStep(stepSeconds);
        List<String> groups = normalizeGroupBy(groupBy);
        String valueExpr = valueExpression(valueMode);
        StringBuilder select = new StringBuilder();
        select.append("CAST(FLOOR(`ts` / 1000 / ").append(bucketSec).append(") * ")
                .append(bucketSec).append(" AS BIGINT) AS epoch_sec");
        for (String g : groups) {
            select.append(", `").append(g).append("`");
        }
        select.append(", ").append(valueExpr).append(" AS metric_value");

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(select)
                .append(" FROM ").append(qualifiedTable(database))
                .append(" WHERE ").append(tsWhere(fromMillis, toMillis))
                .append(metricFilter(metrics, metricPrefixes, metricSuffixes))
                .append(inFilter("component", components))
                .append(inFilter("instance", instances))
                .append(inFilter("dim", dims))
                .append(" GROUP BY epoch_sec");
        for (String g : groups) {
            sql.append(", `").append(g).append('`');
        }
        sql.append(" ORDER BY epoch_sec ASC");
        for (String g : groups) {
            sql.append(", `").append(g).append("` ASC");
        }
        return sql.toString();
    }

    public static String summarySql(
            String database,
            long fromMillis,
            long toMillis,
            List<String> metrics,
            List<String> metricPrefixes,
            List<String> metricSuffixes,
            List<String> components,
            List<String> instances,
            List<String> dims,
            List<String> groupBy,
            String valueMode) {
        List<String> groups = normalizeGroupBy(groupBy);
        String valueExpr = valueExpression(valueMode);
        StringBuilder select = new StringBuilder();
        for (int i = 0; i < groups.size(); i++) {
            if (i > 0) {
                select.append(", ");
            }
            select.append('`').append(groups.get(i)).append('`');
        }
        if (!groups.isEmpty()) {
            select.append(", ");
        }
        select.append(valueExpr).append(" AS metric_value");

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(select)
                .append(" FROM ").append(qualifiedTable(database))
                .append(" WHERE ").append(tsWhere(fromMillis, toMillis))
                .append(metricFilter(metrics, metricPrefixes, metricSuffixes))
                .append(inFilter("component", components))
                .append(inFilter("instance", instances))
                .append(inFilter("dim", dims));
        if (!groups.isEmpty()) {
            sql.append(" GROUP BY ");
            for (int i = 0; i < groups.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append('`').append(groups.get(i)).append('`');
            }
        }
        sql.append(" ORDER BY ");
        if (!groups.isEmpty()) {
            for (int i = 0; i < groups.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append('`').append(groups.get(i)).append("` ASC");
            }
        } else {
            sql.append("metric_value DESC");
        }
        return sql.toString();
    }

    public static String tagValuesSql(
            String database,
            long fromMillis,
            long toMillis,
            String tag,
            List<String> metrics,
            List<String> metricPrefixes,
            List<String> metricSuffixes,
            List<String> components,
            List<String> instances,
            List<String> dims,
            int limit) {
        String column = normalizeTag(tag);
        int lim = Math.max(1, Math.min(limit <= 0 ? 200 : limit, MAX_LIST));
        // Tag browse may omit metric selector (e.g. list all instances in window).
        String metricClause = hasMetricSelector(metrics, metricPrefixes, metricSuffixes)
                ? metricFilter(metrics, metricPrefixes, metricSuffixes)
                : "";
        return "SELECT DISTINCT `" + column + "` AS tag_value"
                + " FROM " + qualifiedTable(database)
                + " WHERE " + tsWhere(fromMillis, toMillis)
                + metricClause
                + inFilter("component", components)
                + inFilter("instance", instances)
                + inFilter("dim", dims)
                + " AND `" + column + "` IS NOT NULL AND `" + column + "` != ''"
                + " ORDER BY tag_value ASC"
                + " LIMIT " + lim;
    }

    public static boolean hasMetricSelector(
            List<String> metrics, List<String> metricPrefixes, List<String> metricSuffixes) {
        return !normalizeList(metrics).isEmpty()
                || !normalizeList(metricPrefixes).isEmpty()
                || !normalizeList(metricSuffixes).isEmpty();
    }

    /** @deprecated use {@link #hasMetricSelector(List, List, List)} */
    public static boolean hasMetricSelector(List<String> metrics, List<String> metricPrefixes) {
        return hasMetricSelector(metrics, metricPrefixes, List.of());
    }

    public static List<String> normalizeGroupBy(List<String> groupBy) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (groupBy != null) {
            for (String raw : groupBy) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                String g = raw.trim().toLowerCase(Locale.ROOT);
                if (GROUP_COLUMNS.contains(g)) {
                    out.add(g);
                }
            }
        }
        if (out.isEmpty()) {
            out.add("metric");
        }
        return new ArrayList<>(out);
    }

    public static String normalizeValueMode(String valueMode) {
        if (valueMode == null || valueMode.isBlank()) {
            return "auto";
        }
        String mode = valueMode.trim().toLowerCase(Locale.ROOT);
        return VALUE_MODES.contains(mode) ? mode : "auto";
    }

    public static String normalizeTag(String tag) {
        if (tag == null || tag.isBlank()) {
            throw new IllegalArgumentException("tag is required");
        }
        String t = tag.trim().toLowerCase(Locale.ROOT);
        if (!GROUP_COLUMNS.contains(t)) {
            throw new IllegalArgumentException("unsupported tag: " + tag);
        }
        return t;
    }

    public static int clampStep(int stepSeconds) {
        if (stepSeconds <= 0) {
            return MIN_STEP_SEC;
        }
        int aligned = Math.max(MIN_STEP_SEC, stepSeconds);
        aligned = (aligned / MIN_STEP_SEC) * MIN_STEP_SEC;
        return Math.min(MAX_STEP_SEC, aligned);
    }

    static String valueExpression(String valueMode) {
        return switch (normalizeValueMode(valueMode)) {
            case "cnt" -> "SUM(`cnt`)";
            case "sum" -> "SUM(`val_sum`)";
            case "avg" -> "SUM(`val_sum`) / NULLIF(SUM(`cnt`), 0)";
            case "max" -> "MAX(`val_max`)";
            case "min" -> "MIN(`val_min`)";
            case "gauge" -> "MAX(`val_gauge`)";
            default ->
                // Counter → SUM(cnt); Timer → avg ms; Gauge → MAX(val_gauge)
                "CASE"
                        + " WHEN SUM(`val_sum`) IS NOT NULL AND SUM(`cnt`) IS NOT NULL"
                        + " THEN SUM(`val_sum`) / NULLIF(SUM(`cnt`), 0)"
                        + " WHEN SUM(`cnt`) IS NOT NULL THEN SUM(`cnt`)"
                        + " ELSE MAX(`val_gauge`) END";
        };
    }

    private static String qualifiedTable(String database) {
        String db = database == null || database.isBlank() ? "databuff" : database.trim();
        return db + ".`" + DorisTableNames.METRIC_PLATFORM + "`";
    }

    private static String tsWhere(long fromMillis, long toMillis) {
        return "`ts` >= " + fromMillis + " AND `ts` < " + toMillis
                + " AND " + MetricQueryBuilder.partitionWallClockRange("metric_time", fromMillis, toMillis);
    }

    private static String metricFilter(
            List<String> metrics, List<String> metricPrefixes, List<String> metricSuffixes) {
        List<String> exact = normalizeList(metrics);
        List<String> prefixes = normalizeList(metricPrefixes);
        List<String> suffixes = normalizeList(metricSuffixes);
        if (exact.isEmpty() && prefixes.isEmpty() && suffixes.isEmpty()) {
            return " AND 1 = 0";
        }
        StringBuilder sb = new StringBuilder();
        if (!exact.isEmpty() || !prefixes.isEmpty()) {
            sb.append(" AND (");
            boolean first = true;
            if (!exact.isEmpty()) {
                sb.append("`metric` IN (");
                for (int i = 0; i < exact.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append('\'').append(escapeLiteral(exact.get(i))).append('\'');
                }
                sb.append(')');
                first = false;
            }
            for (String prefix : prefixes) {
                if (!first) {
                    sb.append(" OR ");
                }
                sb.append("`metric` LIKE '").append(escapeLiteral(prefix)).append("%'");
                first = false;
            }
            sb.append(')');
        }
        if (!suffixes.isEmpty()) {
            sb.append(" AND (");
            for (int i = 0; i < suffixes.size(); i++) {
                if (i > 0) {
                    sb.append(" OR ");
                }
                sb.append("`metric` LIKE '%").append(escapeLiteral(suffixes.get(i))).append("'");
            }
            sb.append(')');
        }
        return sb.toString();
    }

    private static String inFilter(String column, List<String> values) {
        List<String> normalized = normalizeList(values);
        if (normalized.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(" AND `").append(column).append("` IN (");
        for (int i = 0; i < normalized.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('\'').append(escapeLiteral(normalized.get(i))).append('\'');
        }
        sb.append(')');
        return sb.toString();
    }

    private static List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : values) {
            if (raw == null) {
                continue;
            }
            String v = raw.trim();
            if (v.isEmpty() || v.length() > 256) {
                continue;
            }
            out.add(v);
            if (out.size() >= MAX_LIST) {
                break;
            }
        }
        return new ArrayList<>(out);
    }

    private static String escapeLiteral(String value) {
        return value == null ? "" : value.replace("'", "''");
    }
}
