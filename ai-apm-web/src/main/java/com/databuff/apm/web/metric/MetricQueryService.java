package com.databuff.apm.web.metric;

import com.databuff.apm.common.query.ApmQueryModels.HttpEndpointPoint;
import com.databuff.apm.common.query.ApmQueryModels.HttpLatencyBucketPoint;
import com.databuff.apm.common.query.ApmQueryModels.MetricSeriesPoint;
import com.databuff.apm.common.query.ApmQueryModels.ServiceMetricPoint;
import com.databuff.apm.common.query.TimeSeriesFillUtil;
import com.databuff.apm.web.config.ApmStorageProperties;
import com.databuff.apm.web.portal.PortalTimeParser;

import com.databuff.apm.common.metric.MetricSchemaRegistry;
import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.common.storage.MetricIdentifierParser;
import com.databuff.apm.common.storage.MetricQueryBuilder;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class MetricQueryService {

    private final ApmReadRepository readRepository;
    private final String metricDatabase;

    public MetricQueryService(ApmReadRepository readRepository, ApmStorageProperties storageProperties) {
        this.readRepository = readRepository;
        this.metricDatabase = storageProperties.metricDatabase();
    }

    public List<ServiceMetricPoint> serviceSeries(ServiceSeriesRequest request) {
        try {
            String sql = MetricQueryBuilder.serviceSeriesSql(
                    metricDatabase, request.service(), request.from(), request.to());
            return readRepository.queryServiceMetrics(sql);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public List<HttpEndpointPoint> httpEndpoints(HttpQueryRequest request) {
        try {
            String sql = MetricQueryBuilder.httpEndpointSummarySql(
                    metricDatabase,
                    request.service(),
                    request.from(),
                    request.to(),
                    request.limit(),
                    request.httpMethod(),
                    request.httpCode(),
                    request.urlContains());
            return readRepository.queryHttpEndpoints(sql);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public List<HttpLatencyBucketPoint> httpLatencyBuckets(HttpQueryRequest request) {
        try {
            String sql = MetricQueryBuilder.httpLatencyDistributionSql(
                    metricDatabase,
                    request.service(),
                    request.from(),
                    request.to(),
                    request.httpMethod(),
                    request.httpCode(),
                    request.urlContains());
            return readRepository.queryHttpLatencyBuckets(sql);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public Map<String, List<String>> lastTags(LastTagsRequest request) {
        if (request.metrics() == null || request.metrics().isEmpty()
                || request.by() == null || request.by().isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            MetricIdentifierParser.ParsedMetric parsed = MetricIdentifierParser.parse(request.metrics().get(0));
            String table = MetricIdentifierParser.dorisTableName(parsed.measurement());
            String filters = buildFilterClause(request.filters());
            Set<String> knownTags = MetricSchemaRegistry.schema(parsed.measurement())
                    .map(schema -> new HashSet<>(schema.tagColumns()))
                    .orElse(null);
            Map<String, List<String>> result = new LinkedHashMap<>();
            for (String tag : request.by()) {
                // Schema stores Doris column names (service_id); portal requests use camelCase
                // (serviceId). Skip tags that exist in neither form — e.g. stale serviceType on
                // metric_service — so one bad column cannot wipe all filter keys.
                String column = MetricIdentifierParser.toColumnName(tag);
                if (knownTags != null && !knownTags.contains(tag) && !knownTags.contains(column)) {
                    continue;
                }
                try {
                    String sql = MetricQueryBuilder.metricTagDistinctSql(
                            metricDatabase, table, tag, request.fromMillis(), request.toMillis(), filters);
                    result.put(tag, readRepository.queryDistinctTags(sql));
                } catch (Exception e) {
                    result.put(tag, List.of());
                }
            }
            return result;
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    public List<MetricSeriesPoint> metricSeries(MetricSeriesRequest request) {
        if (request.metric() == null || request.metric().isBlank()) {
            return Collections.emptyList();
        }
        try {
            MetricIdentifierParser.ParsedMetric parsed = MetricIdentifierParser.parse(request.metric());
            if ("service.exception".equals(parsed.measurement())) {
                return serviceErrorSeries(request);
            }
            String table = MetricIdentifierParser.dorisTableName(parsed.measurement());
            String filters = buildFilterClause(request.filters());
            String fieldColumn = MetricIdentifierParser.toDorisFieldColumn(parsed);
            String sql = MetricQueryBuilder.metricFieldSeriesSql(
                    metricDatabase, table, fieldColumn, request.fromMillis(), request.toMillis(), filters);
            List<MetricSeriesPoint> points = readRepository.queryMetricSeries(sql);
            return TimeSeriesFillUtil.fillMetricSeries(
                    points, request.fromMillis(), request.toMillis(), 60);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> metricChart(Map<String, Object> body) {
        try {
            Map<String, Object> queryRoot = body.get("query") instanceof Map<?, ?> queryMap
                    ? (Map<String, Object>) queryMap
                    : Map.of();
            Map<String, Object> metricQuery = queryRoot.get("A") instanceof Map<?, ?> aMap
                    ? (Map<String, Object>) aMap
                    : Map.of();
            String metric = String.valueOf(metricQuery.getOrDefault("metric", ""));
            if (metric.isBlank()) {
                return List.of();
            }
            long start = normalizeTime(toLong(body.get("start")));
            long end = normalizeTime(toLong(body.get("end")));
            int interval = toInt(body.get("interval"), 60);
            List<MetricFilter> filters = parseFilters(metricQuery.get("from"));
            List<String> by = parseStringList(metricQuery.get("by"));
            Map<String, Object> order = metricQuery.get("order") instanceof Map<?, ?> orderMap
                    ? (Map<String, Object>) orderMap
                    : Map.of();
            int topLimit = toInt(order.get("limit"), 50);
            String aggs = stringValue(metricQuery.get("aggs"));

            MetricIdentifierParser.ParsedMetric parsed = MetricIdentifierParser.parse(metric);
            if ("service.exception".equals(parsed.measurement())) {
                return List.of(buildChartSeries(
                        serviceErrorSeries(new MetricSeriesRequest(metric, start, end, filters)),
                        Map.of(),
                        metric,
                        start,
                        end,
                        interval));
            }
            String table = MetricIdentifierParser.dorisTableName(parsed.measurement());
            String filterClause = buildFilterClause(filters);
            String fieldColumn = MetricIdentifierParser.toDorisFieldColumn(parsed);

            if (!by.isEmpty()) {
                String groupBy = by.get(0);
                String groupColumn = resolveChartGroupColumn(groupBy, parsed.measurement());
                String topSql = MetricQueryBuilder.metricTopGroupsSql(
                        metricDatabase, table, fieldColumn, groupColumn,
                        toMillis(start), toMillis(end), filterClause, topLimit, aggs);
                List<String> groups = readRepository.queryTopGroups(topSql);
                List<Map<String, Object>> series = new ArrayList<>();
                for (String groupValue : groups) {
                    String sql = MetricQueryBuilder.metricFieldSeriesByGroupSql(
                            metricDatabase, table, fieldColumn, groupColumn, groupValue,
                            toMillis(start), toMillis(end), filterClause, interval, aggs);
                    series.add(buildChartSeries(
                            readRepository.queryMetricSeries(sql),
                            Map.of(groupBy, groupValue),
                            metric,
                            start,
                            end,
                            interval));
                }
                return series;
            }

            String sql = MetricQueryBuilder.metricFieldSeriesSql(
                    metricDatabase, table, fieldColumn, toMillis(start), toMillis(end), filterClause, interval, aggs);
            return List.of(buildChartSeries(
                    readRepository.queryMetricSeries(sql), Map.of(), metric, start, end, interval));
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> buildChartSeries(
            List<MetricSeriesPoint> points,
            Map<String, String> tags,
            String metric,
            long start,
            long end,
            int intervalSec) {
        List<MetricSeriesPoint> filled = TimeSeriesFillUtil.fillMetricSeries(
                points, toMillis(start), toMillis(end), intervalSec);
        List<List<Number>> values = filled.stream()
                .map(point -> java.util.Arrays.<Number>asList(point.epochSeconds() * 1000L, point.value()))
                .toList();
        Map<String, Object> series = new HashMap<>();
        series.put("values", values);
        series.put("tags", tags);
        series.put("units", List.of("time", metricUnit(metric)));
        return series;
    }

    private static String metricUnit(String metric) {
        String lower = metric.toLowerCase();
        if (lower.contains("collection_count")) {
            return "count";
        }
        if (lower.contains("collection_time")) {
            return "ms";
        }
        if (lower.contains("duration") || lower.contains("latency")) {
            return "ns";
        }
        if (lower.contains("time")) {
            return "ns";
        }
        return "count";
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        return "null".equals(text) ? "" : text;
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private static int toInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static long toMillis(long normalizedSecondsOrMillis) {
        return normalizedSecondsOrMillis < 1_000_000_000_000L
                ? normalizedSecondsOrMillis * 1000L
                : normalizedSecondsOrMillis;
    }

    @SuppressWarnings("unchecked")
    private static List<MetricFilter> parseFilters(Object fromObject) {
        if (!(fromObject instanceof List<?> list)) {
            return List.of();
        }
        List<MetricFilter> filters = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Object operator = map.get("operator");
                Object connector = map.get("connector");
                filters.add(new MetricFilter(
                        String.valueOf(map.get("left")),
                        String.valueOf(operator != null ? operator : "="),
                        map.get("right"),
                        String.valueOf(connector != null ? connector : "AND")));
            }
        }
        return filters;
    }

    private static List<String> parseStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private List<MetricSeriesPoint> serviceErrorSeries(MetricSeriesRequest request) {
        try {
            String table = MetricIdentifierParser.dorisTableName("service.exception");
            String filterClause = buildFilterClause(request.filters());
            String sql = MetricQueryBuilder.metricFieldSeriesSql(
                    metricDatabase, table, "cnt", request.fromMillis(), request.toMillis(), filterClause);
            List<MetricSeriesPoint> points = readRepository.queryMetricSeries(sql);
            return TimeSeriesFillUtil.fillMetricSeries(
                    points, request.fromMillis(), request.toMillis(), 60);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static String normalizeFilterColumn(String column) {
        if ("exceptionName".equals(column) || "errorType".equals(column)) {
            return "exceptionName";
        }
        return column;
    }

    private static String resolveChartGroupColumn(String groupBy, String measurement) {
        if ("exceptionName".equals(groupBy)) {
            return "errorType";
        }
        if ("service.http".equals(measurement) && "resource".equals(groupBy)) {
            return "url";
        }
        return MetricIdentifierParser.toColumnName(groupBy);
    }

    private static String buildFilterClause(List<MetricFilter> filters) {
        if (filters == null || filters.isEmpty()) {
            return "";
        }
        StringBuilder clause = new StringBuilder();
        for (MetricFilter filter : filters) {
            if (filter.left() == null || filter.right() == null) {
                continue;
            }
            clause.append(MetricQueryBuilder.metricFilterClause(
                    normalizeFilterColumn(filter.left()), filter.operator(), String.valueOf(filter.right())));
        }
        return clause.toString();
    }

    public record ServiceSeriesRequest(String service, long from, long to) {
    }

    public record HttpQueryRequest(
            String service,
            long from,
            long to,
            int limit,
            String httpMethod,
            String httpCode,
            String urlContains) {

        public HttpQueryRequest(String service, long from, long to, int limit) {
            this(service, from, to, limit, null, null, null);
        }

        public HttpQueryRequest {
            if (limit <= 0) {
                limit = 100;
            }
        }
    }

    public record MetricFilter(String left, String operator, Object right, String connector) {
    }

    public record LastTagsRequest(
            long start,
            long end,
            List<String> metrics,
            List<String> by,
            @JsonProperty("from") List<MetricFilter> filters) {

        public long fromMillis() {
            return normalizeTime(start);
        }

        public long toMillis() {
            return normalizeTime(end);
        }
    }

    public record MetricSeriesRequest(
            String metric,
            long start,
            long end,
            @JsonProperty("from") List<MetricFilter> filters) {

        public long fromMillis() {
            return normalizeTime(start);
        }

        public long toMillis() {
            return normalizeTime(end);
        }
    }

    private static long normalizeTime(long value) {
        if (value <= 0) {
            return PortalTimeParser.portalEndNow();
        }
        return value < 1_000_000_000_000L ? value * 1000L : value;
    }
}
