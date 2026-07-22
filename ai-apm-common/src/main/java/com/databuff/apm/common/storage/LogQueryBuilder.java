package com.databuff.apm.common.storage;

import com.databuff.apm.common.query.TimeSeriesFillUtil;
import com.databuff.apm.common.time.ApmTimeZones;

/** SQL builders for {@link DorisTableNames#LOG_DC_RECORD} portal queries. */
public final class LogQueryBuilder {

    private LogQueryBuilder() {
    }

    public static String searchSql(
            String database,
            String traceId,
            String spanId,
            String serviceId,
            java.util.List<String> serviceIds,
            java.util.List<String> serviceNames,
            java.util.List<String> serviceInstances,
            java.util.List<String> hosts,
            java.util.List<String> severities,
            String query,
            long fromMillis,
            long toMillis,
            int offset,
            int limit) {
        StringBuilder sql = new StringBuilder(384);
        sql.append("SELECT log_time, hostname, service_instance, service, service_id, trace_id, span_id, ");
        sql.append("severity AS status, body AS message, time_ns ");
        sql.append("FROM ").append(database).append('.').append(DorisTableNames.LOG_DC_RECORD);
        appendWhere(
                sql,
                traceId,
                spanId,
                serviceId,
                serviceIds,
                serviceNames,
                serviceInstances,
                hosts,
                severities,
                query,
                fromMillis,
                toMillis);
        sql.append(" ORDER BY time_ns ASC");
        sql.append(" LIMIT ").append(Math.max(1, limit));
        sql.append(" OFFSET ").append(Math.max(0, offset));
        return sql.toString();
    }

    /**
     * Full log record for expand/detail panel. List search stays lightweight; only this path reads
     * {@code attributes_json} / {@code resource_json}.
     */
    public static String detailSql(String database, long timeNs, String serviceId) {
        long millis = timeNs / 1_000_000L;
        long fromMillis = Math.max(0L, millis - 60_000L);
        long toMillis = millis + 60_000L;
        StringBuilder sql = new StringBuilder(320);
        sql.append("SELECT log_time, hostname, service_instance, service, service_id, trace_id, span_id, ");
        sql.append("severity AS status, severity_number, body AS message, attributes_json, resource_json, ");
        sql.append("time_ns, observed_time_ns ");
        sql.append("FROM ").append(database).append('.').append(DorisTableNames.LOG_DC_RECORD);
        sql.append(" WHERE time_ns = ").append(timeNs);
        if (serviceId != null && !serviceId.isBlank()) {
            sql.append(" AND service_id = '").append(escape(serviceId)).append("'");
        }
        sql.append(" AND log_time >= '").append(formatTime(fromMillis)).append("'");
        sql.append(" AND log_time < '").append(formatTime(toMillis)).append("'");
        sql.append(" LIMIT 1");
        return sql.toString();
    }

    public static String countSql(
            String database,
            String traceId,
            String spanId,
            String serviceId,
            java.util.List<String> serviceIds,
            java.util.List<String> serviceNames,
            java.util.List<String> serviceInstances,
            java.util.List<String> hosts,
            java.util.List<String> severities,
            String query,
            long fromMillis,
            long toMillis) {
        StringBuilder sql = new StringBuilder(192);
        sql.append("SELECT COUNT(*) AS total_cnt FROM ").append(database).append('.').append(DorisTableNames.LOG_DC_RECORD);
        appendWhere(
                sql,
                traceId,
                spanId,
                serviceId,
                serviceIds,
                serviceNames,
                serviceInstances,
                hosts,
                severities,
                query,
                fromMillis,
                toMillis);
        return sql.toString();
    }

    public static String distinctServicesSql(
            String database,
            String traceId,
            String spanId,
            java.util.List<String> serviceInstances,
            java.util.List<String> hosts,
            java.util.List<String> severities,
            String query,
            long fromMillis,
            long toMillis) {
        StringBuilder sql = new StringBuilder(256);
        sql.append("SELECT DISTINCT service_id, service FROM ").append(database).append('.').append(DorisTableNames.LOG_DC_RECORD);
        appendWhere(
                sql,
                traceId,
                spanId,
                null,
                java.util.List.of(),
                java.util.List.of(),
                serviceInstances,
                hosts,
                severities,
                query,
                fromMillis,
                toMillis);
        sql.append(" AND service_id != ''");
        sql.append(" ORDER BY service");
        sql.append(" LIMIT 500");
        return sql.toString();
    }

    public static String distinctServiceInstancesSql(
            String database,
            String traceId,
            String spanId,
            java.util.List<String> serviceIds,
            java.util.List<String> hosts,
            java.util.List<String> severities,
            String query,
            long fromMillis,
            long toMillis) {
        StringBuilder sql = new StringBuilder(256);
        sql.append("SELECT DISTINCT service_instance FROM ").append(database).append('.').append(DorisTableNames.LOG_DC_RECORD);
        appendWhere(
                sql,
                traceId,
                spanId,
                null,
                serviceIds,
                java.util.List.of(),
                java.util.List.of(),
                hosts,
                severities,
                query,
                fromMillis,
                toMillis);
        sql.append(" AND service_instance != ''");
        sql.append(" ORDER BY service_instance");
        sql.append(" LIMIT 500");
        return sql.toString();
    }

    public static String distinctHostsSql(
            String database,
            String traceId,
            String spanId,
            java.util.List<String> serviceIds,
            java.util.List<String> serviceInstances,
            java.util.List<String> severities,
            String query,
            long fromMillis,
            long toMillis) {
        StringBuilder sql = new StringBuilder(256);
        sql.append("SELECT DISTINCT hostname FROM ").append(database).append('.').append(DorisTableNames.LOG_DC_RECORD);
        appendWhere(
                sql,
                traceId,
                spanId,
                null,
                serviceIds,
                java.util.List.of(),
                serviceInstances,
                java.util.List.of(),
                severities,
                query,
                fromMillis,
                toMillis);
        sql.append(" AND hostname != ''");
        sql.append(" ORDER BY hostname");
        sql.append(" LIMIT 500");
        return sql.toString();
    }

    public static String distinctSeveritiesSql(
            String database,
            String traceId,
            String spanId,
            java.util.List<String> serviceIds,
            java.util.List<String> serviceInstances,
            java.util.List<String> hosts,
            String query,
            long fromMillis,
            long toMillis) {
        StringBuilder sql = new StringBuilder(256);
        sql.append("SELECT DISTINCT severity FROM ").append(database).append('.').append(DorisTableNames.LOG_DC_RECORD);
        appendWhere(
                sql,
                traceId,
                spanId,
                null,
                serviceIds,
                java.util.List.of(),
                serviceInstances,
                hosts,
                java.util.List.of(),
                query,
                fromMillis,
                toMillis);
        sql.append(" AND severity != ''");
        sql.append(" ORDER BY severity");
        sql.append(" LIMIT 50");
        return sql.toString();
    }

    /**
     * Time-bucket log counts grouped by severity. When {@code severities} is empty, all severities
     * are included so the portal can render a full severity breakdown chart.
     */
    public static String trendBucketsSql(
            String database,
            String traceId,
            String spanId,
            String serviceId,
            java.util.List<String> serviceIds,
            java.util.List<String> serviceNames,
            java.util.List<String> serviceInstances,
            java.util.List<String> hosts,
            java.util.List<String> severities,
            String query,
            long fromMillis,
            long toMillis,
            int intervalSec) {
        int bucketSec = TimeSeriesFillUtil.bucketSec(intervalSec);
        StringBuilder sql = new StringBuilder(384);
        sql.append("SELECT CAST(FLOOR(time_ns / 1000000000 / ").append(bucketSec).append(") * ");
        sql.append(bucketSec).append(" AS BIGINT) AS bucket_epoch_sec, severity, COUNT(*) AS cnt ");
        sql.append("FROM ").append(database).append('.').append(DorisTableNames.LOG_DC_RECORD);
        appendWhere(
                sql,
                traceId,
                spanId,
                serviceId,
                serviceIds,
                serviceNames,
                serviceInstances,
                hosts,
                severities,
                query,
                fromMillis,
                toMillis);
        sql.append(" AND severity != ''");
        sql.append(" GROUP BY bucket_epoch_sec, severity");
        sql.append(" ORDER BY bucket_epoch_sec");
        return sql.toString();
    }

    /** Distinct span_id values that have at least one log row for the trace. */
    public static String spanIdsWithLogsSql(String database, String traceId) {
        StringBuilder sql = new StringBuilder(160);
        sql.append("SELECT DISTINCT span_id FROM ").append(database).append('.').append(DorisTableNames.LOG_DC_RECORD);
        sql.append(" WHERE trace_id = '").append(escape(traceId)).append("'");
        sql.append(" AND span_id != ''");
        return sql.toString();
    }

    private static void appendWhere(
            StringBuilder sql,
            String traceId,
            String spanId,
            String serviceId,
            java.util.List<String> serviceIds,
            java.util.List<String> serviceNames,
            java.util.List<String> serviceInstances,
            java.util.List<String> hosts,
            java.util.List<String> severities,
            String query,
            long fromMillis,
            long toMillis) {
        sql.append(" WHERE log_time >= '").append(formatTime(fromMillis)).append("'");
        sql.append(" AND log_time < '").append(formatTime(toMillis)).append("'");
        if (traceId != null && !traceId.isBlank()) {
            sql.append(" AND trace_id = '").append(escape(traceId)).append("'");
        }
        if (spanId != null && !spanId.isBlank()) {
            sql.append(" AND span_id = '").append(escape(spanId)).append("'");
        }
        if (serviceId != null && !serviceId.isBlank()) {
            sql.append(" AND service_id = '").append(escape(serviceId)).append("'");
        } else if (serviceIds != null && !serviceIds.isEmpty()) {
            appendIn(sql, "service_id", serviceIds);
        } else if (serviceNames != null && !serviceNames.isEmpty()) {
            appendIn(sql, "service", serviceNames);
        }
        if (serviceInstances != null && !serviceInstances.isEmpty()) {
            appendIn(sql, "service_instance", serviceInstances);
        }
        if (hosts != null && !hosts.isEmpty()) {
            appendIn(sql, "hostname", hosts);
        }
        if (severities != null && !severities.isEmpty()) {
            appendIn(sql, "severity", severities);
        }
        if (query != null && !query.isBlank()) {
            sql.append(" AND body LIKE '%").append(escapeLike(query)).append("%'");
        }
    }

    private static void appendIn(StringBuilder sql, String column, java.util.List<String> values) {
        sql.append(" AND ").append(column).append(" IN (");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append('\'').append(escape(values.get(i))).append('\'');
        }
        sql.append(')');
    }

    private static String formatTime(long millis) {
        return ApmTimeZones.WALL_CLOCK.format(java.time.Instant.ofEpochMilli(millis));
    }

    private static String escape(String value) {
        return value.replace("'", "''");
    }

    private static String escapeLike(String value) {
        return escape(value).replace("%", "\\%").replace("_", "\\_");
    }
}
