package com.databuff.apm.web.log;

import com.databuff.apm.common.query.TimeSeriesFillUtil;
import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.common.storage.LogQueryBuilder;
import com.databuff.apm.common.time.ApmTimeZones;
import com.databuff.apm.web.config.ApmStorageProperties;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LogQueryService {

    private final ApmReadRepository readRepository;
    private final String logDatabase;

    public LogQueryService(ApmReadRepository readRepository, ApmStorageProperties storageProperties) {
        this.readRepository = readRepository;
        this.logDatabase = storageProperties.traceDatabase();
    }

    public List<Map<String, Object>> search(LogSearchCriteria criteria) throws SQLException {
        String sql = LogQueryBuilder.searchSql(
                logDatabase,
                criteria.traceId(),
                criteria.spanId(),
                criteria.serviceId(),
                criteria.serviceIds(),
                criteria.serviceNames(),
                criteria.hosts(),
                criteria.severities(),
                criteria.query(),
                criteria.fromMillis(),
                criteria.toMillis(),
                criteria.offset(),
                criteria.size());
        List<Map<String, Object>> rows = readRepository.queryRows(sql, criteria.size());
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            out.add(toPortalRow(row));
        }
        return out;
    }

    public long count(LogSearchCriteria criteria) throws SQLException {
        String sql = LogQueryBuilder.countSql(
                logDatabase,
                criteria.traceId(),
                criteria.spanId(),
                criteria.serviceId(),
                criteria.serviceIds(),
                criteria.serviceNames(),
                criteria.hosts(),
                criteria.severities(),
                criteria.query(),
                criteria.fromMillis(),
                criteria.toMillis());
        return readRepository.queryCallSpanCount(sql);
    }

    public Map<String, Object> trend(LogSearchCriteria criteria, int intervalSec) throws SQLException {
        String sql = LogQueryBuilder.trendBucketsSql(
                logDatabase,
                criteria.traceId(),
                criteria.spanId(),
                criteria.serviceId(),
                criteria.serviceIds(),
                criteria.serviceNames(),
                criteria.hosts(),
                criteria.query(),
                criteria.fromMillis(),
                criteria.toMillis(),
                intervalSec);
        List<Map<String, Object>> rows = readRepository.queryRows(sql, 10_000);

        Map<String, Number> logCnts = new LinkedHashMap<>();
        Map<String, Map<String, Long>> severityCnts = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            long bucketEpochSec = longValue(row.get("bucket_epoch_sec"));
            if (bucketEpochSec <= 0) {
                continue;
            }
            String bucket = String.valueOf(bucketEpochSec * 1000L);
            String severity = stringValue(row.get("severity"));
            long cnt = longValue(row.get("cnt"));
            if (severity.isEmpty() || cnt <= 0) {
                continue;
            }
            logCnts.merge(bucket, cnt, (left, right) -> left.longValue() + right.longValue());
            severityCnts.computeIfAbsent(bucket, key -> new LinkedHashMap<>()).merge(severity, cnt, Long::sum);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put(
                "logCnts",
                TimeSeriesFillUtil.fillStringKeyMap(
                        logCnts, criteria.fromMillis(), criteria.toMillis(), intervalSec));
        data.put(
                "severityCnts",
                TimeSeriesFillUtil.fillStringKeyObjectMap(
                        severityCnts, criteria.fromMillis(), criteria.toMillis(), intervalSec));
        return data;
    }

    public Map<String, Object> listConditions(LogSearchCriteria criteria) throws SQLException {
        List<Map<String, Object>> serviceRows = readRepository.queryRows(
                LogQueryBuilder.distinctServicesSql(
                        logDatabase,
                        criteria.traceId(),
                        criteria.spanId(),
                        criteria.hosts(),
                        criteria.severities(),
                        criteria.query(),
                        criteria.fromMillis(),
                        criteria.toMillis()),
                500);
        List<Map<String, Object>> hostRows = readRepository.queryRows(
                LogQueryBuilder.distinctHostsSql(
                        logDatabase,
                        criteria.traceId(),
                        criteria.spanId(),
                        criteria.serviceIds(),
                        criteria.severities(),
                        criteria.query(),
                        criteria.fromMillis(),
                        criteria.toMillis()),
                500);
        List<Map<String, Object>> severityRows = readRepository.queryRows(
                LogQueryBuilder.distinctSeveritiesSql(
                        logDatabase,
                        criteria.traceId(),
                        criteria.spanId(),
                        criteria.serviceIds(),
                        criteria.hosts(),
                        criteria.query(),
                        criteria.fromMillis(),
                        criteria.toMillis()),
                50);

        List<Map<String, Object>> services = new ArrayList<>();
        for (Map<String, Object> row : serviceRows) {
            String serviceId = stringValue(row.get("service_id"));
            String service = stringValue(row.get("service"));
            if (serviceId.isEmpty() && service.isEmpty()) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", serviceId);
            item.put("name", service);
            services.add(item);
        }

        List<String> hosts = new ArrayList<>();
        for (Map<String, Object> row : hostRows) {
            String hostname = stringValue(row.get("hostname"));
            if (!hostname.isEmpty()) {
                hosts.add(hostname);
            }
        }

        List<String> severities = new ArrayList<>();
        for (Map<String, Object> row : severityRows) {
            String severity = stringValue(row.get("severity"));
            if (!severity.isEmpty()) {
                severities.add(severity);
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hosts", hosts);
        data.put("services", services);
        data.put("severities", severities);
        return data;
    }

    private static Map<String, Object> toPortalRow(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        Object logTime = row.get("log_time");
        Object timeNs = row.get("time_ns");
        out.put("timestamp", formatTimestamp(logTime, timeNs));
        out.put("hostname", stringValue(row.get("hostname")));
        out.put("service", stringValue(row.get("service")));
        out.put("serviceId", stringValue(row.get("service_id")));
        out.put("traceId", stringValue(row.get("trace_id")));
        out.put("spanId", stringValue(row.get("span_id")));
        out.put("status", stringValue(row.get("status")));
        out.put("message", stringValue(row.get("message")));
        return out;
    }

    private static String formatTimestamp(Object logTime, Object timeNs) {
        long millis = epochMillis(timeNs, logTime);
        return millis > 0 ? Long.toString(millis) : "";
    }

    /** Portal contract: epoch millis as string (span-log.vue takes substring(0,13)). */
    private static long epochMillis(Object timeNs, Object logTime) {
        if (timeNs != null) {
            String text = String.valueOf(timeNs).trim();
            if (!text.isEmpty()) {
                try {
                    long ns = Long.parseLong(text);
                    if (ns > 0) {
                        return ns / 1_000_000L;
                    }
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
        }
        if (logTime == null) {
            return 0L;
        }
        String text = String.valueOf(logTime).trim();
        if (text.isEmpty()) {
            return 0L;
        }
        if (text.chars().allMatch(Character::isDigit) && text.length() <= 13) {
            long value = Long.parseLong(text);
            return text.length() > 10 ? value : value * 1000L;
        }
        try {
            return ApmTimeZones.wallClockToEpochMilli(text);
        } catch (DateTimeParseException ignored) {
            try {
                return Instant.parse(text).toEpochMilli();
            } catch (DateTimeParseException ignored2) {
                return 0L;
            }
        }
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    public record LogSearchCriteria(
            String traceId,
            String spanId,
            String serviceId,
            List<String> serviceIds,
            List<String> serviceNames,
            List<String> hosts,
            List<String> severities,
            String query,
            long fromMillis,
            long toMillis,
            int offset,
            int size) {
    }
}
