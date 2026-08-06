package com.databuff.apm.web.portal;

import com.databuff.apm.common.query.TimeSeriesFillUtil;
import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.common.storage.PlatformMetricQueryBuilder;
import com.databuff.apm.web.config.ApmStorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Generic {@code metric_platform} query service for self-monitor UI. */
@Service
public class PlatformMetricPortalService {

    private static final Logger log = LoggerFactory.getLogger(PlatformMetricPortalService.class);
    private static final int MAX_SERIES = 200;

    private final ApmReadRepository readRepository;
    private final String metricDatabase;

    public PlatformMetricPortalService(ApmReadRepository readRepository, ApmStorageProperties storageProperties) {
        this.readRepository = readRepository;
        this.metricDatabase = storageProperties.metricDatabase();
    }

    public Map<String, Object> query(Map<String, Object> body) {
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        long[] range = PortalTimeParser.normalizeMetricQueryRange(from, to);
        from = range[0];
        to = range[1];

        List<String> metrics = stringList(body.get("metrics"));
        List<String> metricPrefixes = stringList(body.get("metricPrefixes"));
        if (metricPrefixes.isEmpty()) {
            metricPrefixes = stringList(body.get("metricPrefix"));
        }
        List<String> metricSuffixes = stringList(body.get("metricSuffixes"));
        if (metricSuffixes.isEmpty()) {
            metricSuffixes = stringList(body.get("metricSuffix"));
        }
        if (!PlatformMetricQueryBuilder.hasMetricSelector(metrics, metricPrefixes, metricSuffixes)) {
            return emptySeries(PlatformMetricQueryBuilder.clampStep(intValue(body.get("stepSeconds"), 60)));
        }

        List<String> components = stringList(body.get("components"));
        if (components.isEmpty()) {
            String component = stringValue(body.get("component"));
            if (component != null) {
                components = List.of(component);
            }
        }
        List<String> instances = stringList(body.get("instances"));
        if (instances.isEmpty()) {
            String instance = stringValue(body.get("instance"));
            if (instance != null) {
                instances = List.of(instance);
            }
        }
        List<String> dims = stringList(body.get("dims"));
        if (dims.isEmpty()) {
            String dim = stringValue(body.get("dim"));
            if (dim != null) {
                dims = List.of(dim);
            }
        }
        List<String> groupBy = PlatformMetricQueryBuilder.normalizeGroupBy(stringList(body.get("groupBy")));
        String valueMode = PlatformMetricQueryBuilder.normalizeValueMode(stringValue(body.get("value")));
        int stepSeconds = PlatformMetricQueryBuilder.clampStep(intValue(body.get("stepSeconds"), 60));
        boolean fill = body.get("fill") == null || Boolean.TRUE.equals(body.get("fill"))
                || "true".equalsIgnoreCase(String.valueOf(body.get("fill")));

        try {
            String sql = PlatformMetricQueryBuilder.seriesSql(
                    metricDatabase, from, to, metrics, metricPrefixes, metricSuffixes,
                    components, instances, dims, groupBy, valueMode, stepSeconds);
            List<Map<String, Object>> rows = readRepository.queryRows(sql, 50_000);
            if (fill) {
                rows = TimeSeriesFillUtil.fillGroupedTimeSeriesRows(
                        rows, from, to, stepSeconds, "epoch_sec", groupBy, List.of("metric_value"));
            }
            List<Map<String, Object>> series = toSeries(rows, groupBy);
            if (series.size() > MAX_SERIES) {
                series = series.subList(0, MAX_SERIES);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("from", from);
            data.put("to", to);
            data.put("stepSeconds", stepSeconds);
            data.put("value", valueMode);
            data.put("groupBy", groupBy);
            data.put("series", series);
            return data;
        } catch (Exception e) {
            log.warn("platform metric query failed: {}", e.toString());
            return emptySeries(stepSeconds);
        }
    }

    public Map<String, Object> summary(Map<String, Object> body) {
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        long[] range = PortalTimeParser.normalizeMetricQueryRange(from, to);
        from = range[0];
        to = range[1];

        List<String> metrics = stringList(body.get("metrics"));
        List<String> metricPrefixes = stringList(body.get("metricPrefixes"));
        if (metricPrefixes.isEmpty()) {
            metricPrefixes = stringList(body.get("metricPrefix"));
        }
        List<String> metricSuffixes = stringList(body.get("metricSuffixes"));
        if (metricSuffixes.isEmpty()) {
            metricSuffixes = stringList(body.get("metricSuffix"));
        }
        if (!PlatformMetricQueryBuilder.hasMetricSelector(metrics, metricPrefixes, metricSuffixes)) {
            return Map.of("from", from, "to", to, "items", List.of());
        }

        List<String> components = stringList(body.get("components"));
        List<String> instances = stringList(body.get("instances"));
        List<String> dims = stringList(body.get("dims"));
        List<String> groupBy = PlatformMetricQueryBuilder.normalizeGroupBy(stringList(body.get("groupBy")));
        String valueMode = PlatformMetricQueryBuilder.normalizeValueMode(stringValue(body.get("value")));

        try {
            String sql = PlatformMetricQueryBuilder.summarySql(
                    metricDatabase, from, to, metrics, metricPrefixes, metricSuffixes,
                    components, instances, dims, groupBy, valueMode);
            List<Map<String, Object>> rows = readRepository.queryRows(sql, MAX_SERIES);
            List<Map<String, Object>> items = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Map<String, Object> tags = new LinkedHashMap<>();
                for (String g : groupBy) {
                    tags.put(g, stringOrEmpty(row.get(g)));
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", seriesName(tags, groupBy));
                item.put("tags", tags);
                item.put("value", toDouble(row.get("metric_value")));
                items.add(item);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("from", from);
            data.put("to", to);
            data.put("value", valueMode);
            data.put("groupBy", groupBy);
            data.put("items", items);
            return data;
        } catch (Exception e) {
            log.warn("platform metric summary failed: {}", e.toString());
            return Map.of("from", from, "to", to, "items", List.of());
        }
    }

    public Map<String, Object> tagValues(Map<String, Object> body) {
        long now = System.currentTimeMillis();
        long from = PortalTimeParser.rangeFrom(body, now - 3_600_000L);
        long to = PortalTimeParser.rangeTo(body, now);
        long[] range = PortalTimeParser.normalizeMetricQueryRange(from, to);
        from = range[0];
        to = range[1];

        String tag;
        try {
            tag = PlatformMetricQueryBuilder.normalizeTag(stringValue(body.get("tag")));
        } catch (IllegalArgumentException e) {
            return Map.of("tag", "", "values", List.of());
        }

        List<String> metrics = stringList(body.get("metrics"));
        List<String> metricPrefixes = stringList(body.get("metricPrefixes"));
        if (metricPrefixes.isEmpty()) {
            metricPrefixes = stringList(body.get("metricPrefix"));
        }
        List<String> metricSuffixes = stringList(body.get("metricSuffixes"));
        if (metricSuffixes.isEmpty()) {
            metricSuffixes = stringList(body.get("metricSuffix"));
        }
        List<String> components = stringList(body.get("components"));
        List<String> instances = stringList(body.get("instances"));
        List<String> dims = stringList(body.get("dims"));
        int limit = intValue(body.get("limit"), 200);

        try {
            String sql = PlatformMetricQueryBuilder.tagValuesSql(
                    metricDatabase, from, to, tag, metrics, metricPrefixes, metricSuffixes,
                    components, instances, dims, limit);
            List<Map<String, Object>> rows = readRepository.queryRows(sql, limit);
            List<String> values = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                String v = stringOrEmpty(row.get("tag_value"));
                if (!v.isEmpty()) {
                    values.add(v);
                }
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("tag", tag);
            data.put("values", values);
            return data;
        } catch (Exception e) {
            log.warn("platform metric tagValues failed: {}", e.toString());
            return Map.of("tag", tag, "values", List.of());
        }
    }

    private static Map<String, Object> emptySeries(int stepSeconds) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stepSeconds", stepSeconds);
        data.put("series", List.of());
        return data;
    }

    private static List<Map<String, Object>> toSeries(List<Map<String, Object>> rows, List<String> groupBy) {
        Map<String, Map<String, Object>> seriesByKey = new LinkedHashMap<>();
        if (rows == null) {
            return List.of();
        }
        for (Map<String, Object> row : rows) {
            Map<String, Object> tags = new LinkedHashMap<>();
            for (String g : groupBy) {
                tags.put(g, stringOrEmpty(row.get(g)));
            }
            String key = seriesName(tags, groupBy);
            Map<String, Object> series = seriesByKey.computeIfAbsent(key, k -> {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("name", k);
                s.put("tags", tags);
                s.put("points", new ArrayList<Map<String, Object>>());
                return s;
            });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> points = (List<Map<String, Object>>) series.get("points");
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("t", toLong(row.get("epoch_sec")));
            point.put("v", toDouble(row.get("metric_value")));
            points.add(point);
        }
        return new ArrayList<>(seriesByKey.values());
    }

    private static String seriesName(Map<String, Object> tags, List<String> groupBy) {
        if (groupBy.size() == 1) {
            return stringOrEmpty(tags.get(groupBy.get(0)));
        }
        StringBuilder sb = new StringBuilder();
        for (String g : groupBy) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(stringOrEmpty(tags.get(g)));
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    String s = String.valueOf(item).trim();
                    if (!s.isEmpty()) {
                        out.add(s);
                    }
                }
            }
            return out;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return List.of();
        }
        if (text.contains(",")) {
            List<String> out = new ArrayList<>();
            for (String part : text.split(",")) {
                String s = part.trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
            return out;
        }
        return List.of(text);
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private static String stringOrEmpty(Object value) {
        String s = stringValue(value);
        return s == null ? "" : s;
    }

    private static int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            double d = number.doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
        }
        try {
            double d = Double.parseDouble(String.valueOf(value).trim());
            return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
