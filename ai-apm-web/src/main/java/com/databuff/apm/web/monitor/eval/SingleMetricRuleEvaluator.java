package com.databuff.apm.web.monitor.eval;

import com.databuff.apm.web.monitor.EventRule;
import com.databuff.apm.web.monitor.RuleMetricEvaluationService;
import com.databuff.apm.web.monitor.RuleMetricEvaluationService.GroupMetricValue;
import com.databuff.apm.web.monitor.ThresholdAlarmCheck;
import com.databuff.apm.web.monitor.ThresholdEvaluationService;
import com.databuff.apm.web.portal.PortalTimeParser;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class SingleMetricRuleEvaluator {

    private final ThresholdEvaluationService evaluationService;
    private final RuleMetricEvaluationService ruleMetricEvaluationService;
    private final ThresholdAlarmMessageFormatter messageFormatter;

    public SingleMetricRuleEvaluator(
            ThresholdEvaluationService evaluationService,
            RuleMetricEvaluationService ruleMetricEvaluationService,
            ThresholdAlarmMessageFormatter messageFormatter) {
        this.evaluationService = evaluationService;
        this.ruleMetricEvaluationService = ruleMetricEvaluationService;
        this.messageFormatter = messageFormatter;
    }

    public RuleEvaluationResult evaluate(EventRule rule, long lookbackMillis) {
        List<RuleEvaluationResult> results = evaluateAll(rule, lookbackMillis);
        return results.isEmpty() ? RuleEvaluationResult.normal() : results.get(0);
    }

    public List<RuleEvaluationResult> evaluateAll(EventRule rule, long lookbackMillis) {
        return evaluateAllInternal(rule, lookbackMillis, false);
    }

    /** Includes explicitly observed non-breaching groups so the pipeline can emit recovery. */
    public List<RuleEvaluationResult> evaluateAllIncludingNormal(EventRule rule, long lookbackMillis) {
        return evaluateAllInternal(rule, lookbackMillis, true);
    }

    private List<RuleEvaluationResult> evaluateAllInternal(
            EventRule rule,
            long lookbackMillis,
            boolean includeNormal) {
        Map<String, Object> query = EventRulePayloadParser.parseQuery(rule.queryJson());
        Map<String, Object> primary = EventRulePayloadParser.primaryQueryItem(query);
        if (!primary.isEmpty()) {
            lookbackMillis = EventRulePayloadParser.lookbackMinutes(primary) * 60_000L;
        }
        String way = EventRulePayloadParser.normalizeWay(
                !primary.isEmpty()
                        ? String.valueOf(primary.getOrDefault("way", rule.detectionWay()))
                        : rule.detectionWay());
        boolean catalogRule = rule.queryJson() != null && !rule.queryJson().isBlank();
        if (catalogRule) {
            if (EventRule.WAY_MUTATION.equals(way)) {
                return evaluateCatalogMutationAll(rule, lookbackMillis, includeNormal);
            }
            return evaluateCatalogThresholdAll(rule, lookbackMillis, includeNormal);
        }
        RuleEvaluationResult single = evaluateLegacy(rule, lookbackMillis, way);
        if (!single.triggered() && !includeNormal) {
            return List.of();
        }
        return List.of(single);
    }

    private RuleEvaluationResult evaluateLegacy(EventRule rule, long lookbackMillis, String way) {
        if (EventRule.WAY_MUTATION.equals(way)) {
            return evaluateMutation(rule, lookbackMillis);
        }
        return evaluateThreshold(rule, lookbackMillis);
    }

    private RuleEvaluationResult evaluateThreshold(EventRule rule, long lookbackMillis) {
        double rate = evaluationService.currentErrorRate(rule.service(), lookbackMillis);
        if (ThresholdAlarmCheck.breached(rate, rule.threshold(), rule.comparator())) {
            String message = messageFormatter.legacyErrorRateThresholdMessage(
                    rule.service(), rate, rule.threshold());
            return new RuleEvaluationResult(
                    true,
                    EventRule.LEVEL_CRITICAL,
                    message,
                    EventRule.WAY_THRESHOLD,
                    rule.service(),
                    rule.service(),
                    "service.error.pct",
                    "错误率",
                    "%",
                    rate * 100,
                    rule.threshold() * 100,
                    EventRule.normalizeComparator(rule.comparator()));
        }
        return new RuleEvaluationResult(
                false,
                "warning",
                "",
                EventRule.WAY_THRESHOLD,
                rule.service(),
                rule.service(),
                "service.error.pct",
                "错误率",
                "%",
                rate * 100,
                rule.threshold() * 100,
                EventRule.normalizeComparator(rule.comparator()));
    }

    private RuleEvaluationResult evaluateMutation(EventRule rule, long lookbackMillis) {
        long to = PortalTimeParser.portalEndNow();
        long currentFrom = to - lookbackMillis;
        double current = evaluationService.errorRateBetween(rule.service(), currentFrom, to);
        double previous = evaluationService.errorRateBetween(
                rule.service(), to - lookbackMillis * 2, currentFrom);
        double delta = Math.abs(current - previous);
        if (ThresholdAlarmCheck.breached(delta, rule.threshold(), rule.comparator())) {
            String message = messageFormatter.legacyErrorRateMutationMessage(
                    rule.service(), delta, rule.threshold());
            return new RuleEvaluationResult(
                    true,
                    EventRule.LEVEL_CRITICAL,
                    message,
                    EventRule.WAY_MUTATION,
                    rule.service(),
                    rule.service(),
                    "service.error.pct",
                    "错误率",
                    "%",
                    delta * 100,
                    rule.threshold() * 100,
                    EventRule.normalizeComparator(rule.comparator()));
        }
        return new RuleEvaluationResult(
                false,
                "warning",
                "",
                EventRule.WAY_MUTATION,
                rule.service(),
                rule.service(),
                "service.error.pct",
                "错误率",
                "%",
                delta * 100,
                rule.threshold() * 100,
                EventRule.normalizeComparator(rule.comparator()));
    }

    private List<RuleEvaluationResult> evaluateCatalogThresholdAll(
            EventRule rule,
            long lookbackMillis,
            boolean includeNormal) {
        List<GroupMetricValue> groups = ruleMetricEvaluationService.evaluateRuleGroups(rule, lookbackMillis);
        String comparator = resolveComparator(rule);
        EventRulePayloadParser.ThresholdLevels levels = resolveThresholdLevels(rule);
        ThresholdAlarmMessageFormatter.MetricMeta metricMeta = messageFormatter.metricMeta(rule);
        List<RuleEvaluationResult> results = new ArrayList<>();
        for (GroupMetricValue group : groups) {
            String level = ThresholdAlarmCheck.resolveLevel(
                    group.value(), comparator, levels.critical(), levels.warning());
            if (level == null) {
                if (includeNormal) {
                    results.add(new RuleEvaluationResult(
                            false,
                            "warning",
                            "",
                            EventRule.WAY_THRESHOLD,
                            group.service(),
                            group.groupKey(),
                            metricMeta.metricId(),
                            metricMeta.label(),
                            metricMeta.unit(),
                            group.value(),
                            levels.critical(),
                            comparator));
                }
                continue;
            }
            double breachedThreshold = ThresholdAlarmCheck.resolveBreachedThreshold(
                    level, levels.critical(), levels.warning());
            String message = messageFormatter.thresholdMessage(
                    rule.withComparator(comparator),
                    group.value(),
                    breachedThreshold,
                    group.groupKey(),
                    group.service());
            results.add(new RuleEvaluationResult(
                    true,
                    level,
                    message,
                    EventRule.WAY_THRESHOLD,
                    group.service(),
                    group.groupKey(),
                    metricMeta.metricId(),
                    metricMeta.label(),
                    metricMeta.unit(),
                    group.value(),
                    breachedThreshold,
                    comparator));
        }
        return results;
    }

    private List<RuleEvaluationResult> evaluateCatalogMutationAll(
            EventRule rule,
            long lookbackMillis,
            boolean includeNormal) {
        long to = PortalTimeParser.portalEndNow();
        long currentFrom = to - lookbackMillis;
        Map<String, Object> query = EventRulePayloadParser.parseQuery(rule.queryJson());
        Map<String, Object> primary = EventRulePayloadParser.primaryQueryItem(query);
        long comparePeriodMillis = EventRulePayloadParser.extractComparePeriodMillis(primary, lookbackMillis);
        String fluctuate = EventRulePayloadParser.extractFluctuate(primary);
        boolean yoy = EventRulePayloadParser.isYoyFluctuate(fluctuate);
        long previousTo = to - comparePeriodMillis;
        long previousFrom = previousTo - lookbackMillis;
        List<GroupMetricValue> currentGroups = ruleMetricEvaluationService.evaluateRuleGroups(rule, currentFrom, to);
        List<GroupMetricValue> previousGroups = ruleMetricEvaluationService.evaluateRuleGroups(
                rule, previousFrom, previousTo);
        Map<String, GroupMetricValue> previousByGroup = previousGroups.stream()
                .collect(Collectors.toMap(GroupMetricValue::groupKey, Function.identity(), (left, right) -> left));
        String comparator = resolveComparator(rule);
        EventRulePayloadParser.ThresholdLevels levels = resolveThresholdLevels(rule);
        ThresholdAlarmMessageFormatter.MetricMeta metricMeta = messageFormatter.metricMeta(rule);
        List<RuleEvaluationResult> results = new ArrayList<>();
        for (GroupMetricValue current : currentGroups) {
            GroupMetricValue previous = previousByGroup.get(current.groupKey());
            double previousValue = previous == null ? 0 : previous.value();
            double change = computeMutationChange(fluctuate, current.value(), previousValue);
            if (Double.isNaN(change) || Double.isInfinite(change)) {
                continue;
            }
            double displayDelta = yoy ? change * 100 : change;
            double displayCritical = yoy ? levels.critical() * 100 : levels.critical();
            String level = ThresholdAlarmCheck.resolveLevel(
                    change, comparator, levels.critical(), levels.warning());
            if (level == null) {
                if (includeNormal) {
                    results.add(new RuleEvaluationResult(
                            false,
                            "warning",
                            "",
                            EventRule.WAY_MUTATION,
                            current.service(),
                            current.groupKey(),
                            metricMeta.metricId(),
                            metricMeta.label(),
                            metricMeta.unit(),
                            displayDelta,
                            displayCritical,
                            comparator));
                }
                continue;
            }
            double breachedThreshold = ThresholdAlarmCheck.resolveBreachedThreshold(
                    level, levels.critical(), levels.warning());
            double displayThreshold = yoy ? breachedThreshold * 100 : breachedThreshold;
            String message = messageFormatter.mutationMessage(
                    rule, displayDelta, displayThreshold, current.groupKey(), current.service());
            results.add(new RuleEvaluationResult(
                    true,
                    level,
                    message,
                    EventRule.WAY_MUTATION,
                    current.service(),
                    current.groupKey(),
                    metricMeta.metricId(),
                    metricMeta.label(),
                    metricMeta.unit(),
                    displayDelta,
                    displayThreshold,
                    comparator));
        }
        return results;
    }

    /**
     * Prefer the front-end {@code comparison} (or nested critical comparator) from query JSON so
     * already-persisted rules keep working after comparator support is fixed, even when the
     * denormalized {@code EventRule.comparator} column still says {@code gt}.
     * <p>
     * When query JSON has no comparison hint, {@link EventRulePayloadParser#extractComparator}
     * returns {@code gt}; that is only used when the rule also has no denormalized comparator.
     */
    static String resolveComparator(EventRule rule) {
        Map<String, Object> query = EventRulePayloadParser.parseQuery(rule.queryJson());
        Map<String, Object> primary = EventRulePayloadParser.primaryQueryItem(query);
        if (!primary.isEmpty()) {
            // Front-end stores "comparison"; nested API stores thresholds.critical.comparator.
            if (primary.containsKey("comparison")
                    || hasNestedCriticalComparator(primary)) {
                return EventRulePayloadParser.extractComparator(primary);
            }
        }
        return EventRule.normalizeComparator(rule.comparator());
    }

    /**
     * Reads critical/warning from query JSON; falls back to denormalized {@link EventRule#threshold()}
     * for critical when the query band is absent (legacy rules).
     */
    static EventRulePayloadParser.ThresholdLevels resolveThresholdLevels(EventRule rule) {
        Map<String, Object> query = EventRulePayloadParser.parseQuery(rule.queryJson());
        Map<String, Object> primary = EventRulePayloadParser.primaryQueryItem(query);
        EventRulePayloadParser.ThresholdLevels fromQuery =
                EventRulePayloadParser.extractThresholdLevels(primary);
        double critical = fromQuery.hasCritical() ? fromQuery.critical() : rule.threshold();
        return new EventRulePayloadParser.ThresholdLevels(critical, fromQuery.warning());
    }

    private static boolean hasNestedCriticalComparator(Map<String, Object> primary) {
        Object thresholdsObj = primary.get("thresholds");
        if (!(thresholdsObj instanceof Map<?, ?> thresholds)) {
            return false;
        }
        Object critical = thresholds.get("critical");
        if (!(critical instanceof Map<?, ?> criticalMap)) {
            return false;
        }
        Object comparator = criticalMap.get("comparator");
        return comparator != null && !String.valueOf(comparator).isBlank();
    }

    /**
     * Signed change used by mutation (同环比) detection. A positive result means the metric
     * moved in the configured direction ({@code valUp/yoyUp} = increase, {@code valDown/yoyDown}
     * = decrease); a negative result means it moved the other way and will not breach a
     * non-negative threshold. yoy modes return a ratio (fraction) matching the front-end
     * {@code _scale=0.01} threshold convention; val modes return the absolute difference in
     * the metric's native display unit. Returns {@code NaN} when the yoy ratio is undefined
     * (previous == 0) so the caller can skip the group instead of misfiring.
     */
    static double computeMutationChange(String fluctuate, double current, double previous) {
        if (EventRulePayloadParser.isYoyFluctuate(fluctuate)) {
            if (previous == 0) {
                return Double.NaN;
            }
            double ratio = (current - previous) / previous;
            return EventRule.FLUCTUATE_YOY_DOWN.equals(fluctuate) ? -ratio : ratio;
        }
        return EventRule.FLUCTUATE_VAL_DOWN.equals(fluctuate) ? previous - current : current - previous;
    }
}
