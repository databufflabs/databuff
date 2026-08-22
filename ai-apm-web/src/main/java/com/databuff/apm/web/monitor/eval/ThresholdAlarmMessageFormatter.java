package com.databuff.apm.web.monitor.eval;

import com.databuff.apm.common.metric.MetricQueryDefinition;
import com.databuff.apm.web.metric.MetricCoreCatalogService;
import com.databuff.apm.web.monitor.EventRule;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

@Component
public class ThresholdAlarmMessageFormatter {

    private final MetricCoreCatalogService catalogService;

    public ThresholdAlarmMessageFormatter(MetricCoreCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /** Structured metric identity for webhook payloads: id + display label + unit. */
    public record MetricMeta(String metricId, String label, String unit) {
    }

    /** Resolves the metric id/label/unit a rule's numbers should be reported under. */
    public MetricMeta metricMeta(EventRule rule) {
        MetricContext context = resolveContext(rule);
        String unit = context.unitSuffix().isBlank() ? null : context.unitSuffix();
        return new MetricMeta(context.metricId(), context.metricLabel(), unit);
    }

    public String thresholdMessage(
            EventRule rule,
            double value,
            double threshold,
            String groupKey,
            String service) {
        MetricContext context = resolveContext(rule);
        String valueText = formatNumber(value, context);
        String thresholdText = formatNumber(threshold, context);
        String verb = EventRule.isLowerBoundComparator(rule.comparator()) ? "低于" : "超过";
        return formatMessage(context.metricLabel(), valueText, thresholdText, groupKey, service, verb);
    }

    public String mutationMessage(
            EventRule rule,
            double delta,
            double threshold,
            String groupKey,
            String service) {
        MetricContext context = resolveContext(rule);
        String deltaText = formatNumber(delta, context);
        String thresholdText = formatNumber(threshold, context);
        String metricLabel = context.metricLabel();
        if (hasDistinctGroup(groupKey, service)) {
            return "%s（%s）的变化值%s超过阈值%s"
                    .formatted(metricLabel, groupKey, deltaText, thresholdText);
        }
        return "%s的变化值%s超过阈值%s".formatted(metricLabel, deltaText, thresholdText);
    }

    public String legacyErrorRateThresholdMessage(String service, double rate, double threshold) {
        return formatMessage("错误率", formatPercent(rate * 100), formatPercent(threshold * 100), service, service, "超过");
    }

    public String legacyErrorRateMutationMessage(String service, double delta, double threshold) {
        return "错误率的变化值%s超过阈值%s"
                .formatted(formatPercent(delta * 100), formatPercent(threshold * 100));
    }

    private String formatMessage(
            String metricLabel,
            String valueText,
            String thresholdText,
            String groupKey,
            String service,
            String verb) {
        if (hasDistinctGroup(groupKey, service)) {
            return "%s（%s）的%s值%s阈值%s"
                    .formatted(metricLabel, groupKey, valueText, verb, thresholdText);
        }
        return "%s的%s值%s阈值%s".formatted(metricLabel, valueText, verb, thresholdText);
    }

    private static boolean hasDistinctGroup(String groupKey, String service) {
        return groupKey != null && !groupKey.isBlank() && !groupKey.equals(service);
    }

    private MetricContext resolveContext(EventRule rule) {
        Map<String, Object> query = EventRulePayloadParser.parseQuery(rule.queryJson());
        Map<String, Object> primary = EventRulePayloadParser.primaryQueryItem(query);
        String metricId = EventRulePayloadParser.extractMetricIdentifier(rule, primary);
        String viewUnit = EventRulePayloadParser.extractViewUnit(primary);
        MetricQueryDefinition definition = catalogService.findOpenByIdentifier(metricId);
        String metricLabel = resolveMetricLabel(metricId, definition);
        boolean percent = isPercentMetric(metricId, viewUnit, definition);
        String unitSuffix = resolveUnitSuffix(viewUnit, definition, percent);
        return new MetricContext(metricId, metricLabel, percent, unitSuffix);
    }

    private static String resolveMetricLabel(String metricId, MetricQueryDefinition definition) {
        if (definition != null) {
            String metricCn = definition.getMetricCn();
            if (metricCn != null && !metricCn.isBlank()) {
                return metricCn;
            }
        }
        if (EventRule.METRIC_REQUEST_COUNT.equals(metricId) || "service.cnt".equals(metricId)) {
            return "请求次数";
        }
        if (EventRule.METRIC_ERROR_RATE.equals(metricId) || "service.error.pct".equals(metricId)) {
            return "错误率";
        }
        return metricId == null || metricId.isBlank() ? "指标" : metricId;
    }

    private static boolean isPercentMetric(String metricId, String viewUnit, MetricQueryDefinition definition) {
        if (metricId != null && metricId.endsWith(".pct")) {
            return true;
        }
        if (definition != null) {
            String unit = definition.getUnit();
            if ("percent".equalsIgnoreCase(unit) || "%".equals(definition.getUnitCn())) {
                return true;
            }
            if (unit != null && !unit.isBlank() && !"percent".equalsIgnoreCase(unit)) {
                return false;
            }
        }
        return "%".equals(viewUnit) || "percent".equalsIgnoreCase(viewUnit);
    }

    private static String resolveUnitSuffix(String viewUnit, MetricQueryDefinition definition, boolean percent) {
        if (percent) {
            return "%";
        }
        if (viewUnit != null && !viewUnit.isBlank() && !"percent".equalsIgnoreCase(viewUnit)) {
            return viewUnit;
        }
        if (definition != null) {
            String unitCn = definition.getUnitCn();
            if (unitCn != null && !unitCn.isBlank() && !"%".equals(unitCn)) {
                return unitCn;
            }
        }
        return "";
    }

    private static String formatNumber(double value, MetricContext context) {
        if (context.percent()) {
            return formatPercent(value);
        }
        String formatted = stripTrailingZeros(value);
        return context.unitSuffix().isBlank() ? formatted : formatted + context.unitSuffix();
    }

    private static String formatPercent(double value) {
        return stripTrailingZeros(value) + "%";
    }

    private static String stripTrailingZeros(double value) {
        return String.format(Locale.ROOT, "%.4f", value)
                .replaceAll("0+$", "")
                .replaceAll("\\.$", "");
    }

    private record MetricContext(String metricId, String metricLabel, boolean percent, String unitSuffix) {
    }
}
