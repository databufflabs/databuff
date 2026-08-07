package com.databuff.apm.web.tools.local;

import com.databuff.apm.web.portal.PlatformMetricPortalService;
import com.databuff.apm.web.storage.DorisReadQueryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Platform-facing tools for digital experts (Doris business-data queries, self-monitor metrics, etc.).
 */
@Component
@Lazy
public class PlatformTools {

    private final DorisReadQueryService dorisReadQueryService;
    private final PlatformMetricPortalService platformMetricPortalService;
    private final ObjectMapper objectMapper;

    public PlatformTools(
            DorisReadQueryService dorisReadQueryService,
            PlatformMetricPortalService platformMetricPortalService,
            ObjectMapper objectMapper) {
        this.dorisReadQueryService = dorisReadQueryService;
        this.platformMetricPortalService = platformMetricPortalService;
        this.objectMapper = objectMapper;
    }

    @Tool(converter = PlainTextToolResultConverter.class, description = """
            Query Doris business/config data (read-only SQL) to troubleshoot product UI business issues —
            config tables, binding/config rows, etc. Use when verifying whether config landed correctly
            in the product UI. Do NOT use for DataBuff platform self-monitoring / platform health checks
            (ingest/web/Doris); prefer querySelfMonitorMetrics — generally avoid this tool for platform
            inspection. Only SELECT, SHOW, DESCRIBE/DESC, EXPLAIN, WITH are allowed.
            Forbidden: SELECT COUNT(*), COUNT(1), and unconstrained full-table aggregate counts
            (expensive on Doris). Prefer this over inventing JDBC/mysql/FE-HTTP connections.
            maxRows defaults to 200 (hard cap 1000).
            """)
    public String queryDorisBusinessData(
            @ToolParam(name = "sql", description = "Read-only SQL, single statement")
            String sql,
            @ToolParam(name = "maxRows", required = false, description = "Max rows to return, default 200, max 1000")
            Integer maxRows) {
        try {
            return objectMapper.writeValueAsString(dorisReadQueryService.query(sql, maxRows).toMap());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize Doris query result", e);
        }
    }

    /**
     * Self-monitoring query tool — delegates to the same {@link PlatformMetricPortalService}
     * used by {@code POST /platform/metrics/{query,summary,tagValues}}.
     */
    @Tool(converter = PlainTextToolResultConverter.class, description = """
            Query DataBuff self-monitoring metrics (metric_platform) to troubleshoot DataBuff platform itself —
            ingest failures, write backlog, query-domain errors/slowness, Doris availability, pipeline queues.
            NOT for business APM shown in app-monitor UI (use queryDorisBusinessData or queryMetricData for that).
            Reuses the same Portal API as the self-monitor UI: mode=series → /platform/metrics/query,
            mode=summary → /summary, mode=list → /tagValues (default tag=metric).
            Metric names and troubleshooting playbooks: docs/运维参考/自监控指标清单.md.
            fromTime/toTime required as yyyy-MM-dd HH:mm:ss (use getCurrentTimeRange).
            Prefer exact metrics from the catalog, or metricPrefixes/metricSuffixes to discover.
            value: cnt|avg|gauge|auto|sum|max|min. groupBy: instance|dim|metric|component.
            Do not use queryMetricData for platform self-monitoring.
            """)
    public String querySelfMonitorMetrics(
            @ToolParam(name = "fromTime", description = "Required start time, yyyy-MM-dd HH:mm:ss")
            String fromTime,
            @ToolParam(name = "toTime", description = "Required end time, yyyy-MM-dd HH:mm:ss")
            String toTime,
            @ToolParam(name = "mode", required = false,
                    description = "series (default) | summary | list — maps to Portal query/summary/tagValues")
            String mode,
            @ToolParam(name = "metrics", required = false,
                    description = "Exact qualified metric names, e.g. ingest.write.fail")
            List<String> metrics,
            @ToolParam(name = "metricPrefixes", required = false,
                    description = "Metric name prefixes, e.g. ingest.otel.")
            List<String> metricPrefixes,
            @ToolParam(name = "metricSuffixes", required = false,
                    description = "Metric name suffixes, e.g. .fail")
            List<String> metricSuffixes,
            @ToolParam(name = "components", required = false, description = "ingest and/or web")
            List<String> components,
            @ToolParam(name = "instances", required = false, description = "Filter by instance")
            List<String> instances,
            @ToolParam(name = "dims", required = false,
                    description = "Filter by dim (write table or Doris host)")
            List<String> dims,
            @ToolParam(name = "groupBy", required = false,
                    description = "Group columns: instance, dim, metric, component; default instance for series")
            List<String> groupBy,
            @ToolParam(name = "value", required = false,
                    description = "Aggregation: cnt|avg|gauge|auto|sum|max|min; default auto")
            String value,
            @ToolParam(name = "stepSeconds", required = false,
                    description = "Series step seconds, default 60 (series mode only)")
            Integer stepSeconds,
            @ToolParam(name = "tag", required = false,
                    description = "For mode=list: metric|instance|dim|component; default metric")
            String tag,
            @ToolParam(name = "limit", required = false,
                    description = "For mode=list: max values, default 200")
            Integer limit) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fromTime", fromTime);
        body.put("toTime", toTime);
        putIfNonEmpty(body, "metrics", metrics);
        putIfNonEmpty(body, "metricPrefixes", metricPrefixes);
        putIfNonEmpty(body, "metricSuffixes", metricSuffixes);
        putIfNonEmpty(body, "components", components);
        putIfNonEmpty(body, "instances", instances);
        putIfNonEmpty(body, "dims", dims);
        if (value != null && !value.isBlank()) {
            body.put("value", value.trim());
        }
        if (stepSeconds != null) {
            body.put("stepSeconds", stepSeconds);
        }
        body.put("fill", true);

        String normalized = mode == null || mode.isBlank()
                ? "series"
                : mode.trim().toLowerCase(Locale.ROOT);
        Map<String, Object> data = switch (normalized) {
            case "summary" -> {
                putIfNonEmpty(body, "groupBy", groupBy != null && !groupBy.isEmpty() ? groupBy : List.of("metric"));
                yield platformMetricPortalService.summary(body);
            }
            case "list", "tagvalues", "tag_values" -> {
                body.put("tag", (tag == null || tag.isBlank()) ? "metric" : tag.trim());
                if (limit != null) {
                    body.put("limit", limit);
                }
                yield platformMetricPortalService.tagValues(body);
            }
            default -> {
                putIfNonEmpty(body, "groupBy", groupBy != null && !groupBy.isEmpty() ? groupBy : List.of("instance"));
                yield platformMetricPortalService.query(body);
            }
        };
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("mode", normalized.equals("tagvalues") || normalized.equals("tag_values") ? "list" : normalized);
            envelope.put("data", data);
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize platform metric result", e);
        }
    }

    private static void putIfNonEmpty(Map<String, Object> body, String key, List<String> values) {
        if (values != null && !values.isEmpty()) {
            body.put(key, values);
        }
    }
}
