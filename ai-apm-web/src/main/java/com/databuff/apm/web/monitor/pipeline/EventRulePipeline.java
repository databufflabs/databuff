package com.databuff.apm.web.monitor.pipeline;

import com.databuff.apm.web.monitor.eval.SingleMetricRuleEvaluator;
import com.databuff.apm.web.persistence.EventPersistence;
import com.databuff.apm.web.monitor.Alarm;
import com.databuff.apm.web.monitor.AlarmSilenceStore;
import com.databuff.apm.web.monitor.EventRule;
import com.databuff.apm.web.monitor.eval.RuleEvaluationResult;
import com.databuff.apm.web.portal.PortalTimeParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class EventRulePipeline {

    private final SingleMetricRuleEvaluator singleMetricRuleEvaluator;
    private final AlarmSilenceStore alarmSilenceStore;
    private final EventRecordFactory eventRecordFactory;
    private final EventPersistence eventPersistence;
    private final EventAlarmOpener eventAlarmOpener;
    private final AlarmResponseExecutor responseExecutor;
    private final long lookbackMillis;
    /** Last firing result per rule/group, used to emit one resolved event on recovery. */
    private final Map<Long, Map<String, RuleEvaluationResult>> activeResults = new ConcurrentHashMap<>();

    public EventRulePipeline(
            SingleMetricRuleEvaluator singleMetricRuleEvaluator,
            AlarmSilenceStore alarmSilenceStore,
            EventRecordFactory eventRecordFactory,
            EventPersistence eventPersistence,
            EventAlarmOpener eventAlarmOpener,
            AlarmResponseExecutor responseExecutor,
            @Value("${apm.alarm.lookback-minutes:5}") long lookbackMinutes) {
        this.singleMetricRuleEvaluator = singleMetricRuleEvaluator;
        this.alarmSilenceStore = alarmSilenceStore;
        this.eventRecordFactory = eventRecordFactory;
        this.eventPersistence = eventPersistence;
        this.eventAlarmOpener = eventAlarmOpener;
        this.responseExecutor = responseExecutor;
        this.lookbackMillis = lookbackMinutes * 60_000L;
    }

    public void evaluateRule(EventRule rule) {
        if (!rule.enabled()) {
            return;
        }
        if (alarmSilenceStore.isSilenced(rule.service())) {
            return;
        }
        List<RuleEvaluationResult> results =
                singleMetricRuleEvaluator.evaluateAllIncludingNormal(rule, lookbackMillis);
        Instant eventBucketAt = PortalTimeParser.eventBucketNow();
        Map<String, RuleEvaluationResult> current = new LinkedHashMap<>();
        java.util.Set<String> observed = new java.util.LinkedHashSet<>();
        for (RuleEvaluationResult result : results) {
            observed.add(lifecycleKey(result));
            if (!result.triggered()) {
                continue;
            }
            current.put(lifecycleKey(result), result);
            EventRecord eventRecord = eventRecordFactory.fromEvaluation(
                    rule.id(), rule.ruleName(), result.service(), result, false, eventBucketAt);
            eventPersistence.persist(eventRecord);
            eventAlarmOpener.openForEvent(eventRecord)
                    .ifPresent(alarm -> responseExecutor.dispatch(alarm, eventRecord));
        }
        Map<String, RuleEvaluationResult> previous = activeResults.getOrDefault(rule.id(), Map.of());
        previous.forEach((key, result) -> {
            if (observed.contains(key) && !current.containsKey(key)) {
                dispatchRecovery(rule, result, eventBucketAt);
            }
        });
        Map<String, RuleEvaluationResult> next = new LinkedHashMap<>(previous);
        observed.forEach(next::remove);
        next.putAll(current);
        if (next.isEmpty()) {
            activeResults.remove(rule.id());
        } else {
            activeResults.put(rule.id(), Map.copyOf(next));
        }
    }

    public List<EventRule> filterEnabledRules(List<EventRule> rules) {
        List<EventRule> filtered = new ArrayList<>();
        for (EventRule rule : rules) {
            if (rule.enabled()) {
                filtered.add(rule);
            }
        }
        return filtered;
    }

    private void dispatchRecovery(EventRule rule, RuleEvaluationResult previous, Instant recoveredAt) {
        String recoveryMessage = previous.message() == null || previous.message().isBlank()
                ? rule.ruleName() + " 已恢复"
                : "已恢复：" + previous.message();
        RuleEvaluationResult recovery = new RuleEvaluationResult(
                false,
                previous.level(),
                recoveryMessage,
                previous.detectionWay(),
                previous.service(),
                previous.groupKey(),
                previous.metricId(),
                previous.metricLabel(),
                previous.metricUnit(),
                null,
                previous.threshold(),
                previous.comparator());
        EventRecord recoveryEvent = eventRecordFactory.fromEvaluation(
                rule.id(), rule.ruleName(), previous.service(), recovery, false, recoveredAt);
        eventPersistence.persist(recoveryEvent);
        eventAlarmOpener.openRecoveryForEvent(recoveryEvent)
                .ifPresent(alarm -> responseExecutor.dispatch(alarm, recoveryEvent));
    }

    private static String lifecycleKey(RuleEvaluationResult result) {
        return String.valueOf(result.service()) + '\u0000' + String.valueOf(result.groupKey());
    }
}
