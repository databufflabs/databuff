package com.databuff.apm.web.monitor;

import com.databuff.apm.web.TestStorageSupport;
import com.databuff.apm.web.ai.TestBeanSupport;
import com.databuff.apm.web.monitor.eval.SingleMetricRuleEvaluator;
import com.databuff.apm.web.monitor.eval.RuleEvaluationResult;
import com.databuff.apm.web.monitor.eval.ThresholdAlarmMessageFormatter;
import com.databuff.apm.web.persistence.EventPersistence;
import com.databuff.apm.common.query.ApmQueryModels.ErrorRateSnapshot;
import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.web.metric.MetricCoreCatalogService;
import com.databuff.apm.web.monitor.pipeline.AlarmResponseExecutor;
import com.databuff.apm.web.monitor.pipeline.EventAlarmOpener;
import com.databuff.apm.web.monitor.pipeline.EventRulePipeline;
import com.databuff.apm.web.monitor.pipeline.EventRecord;
import com.databuff.apm.web.monitor.pipeline.EventRecordFactory;
import com.databuff.apm.web.portal.PortalTimeParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventRulePipelineTest {

    private InMemoryEventRuleStore ruleStore;
    private AlarmStore alarmStore;
    private EventRulePipeline monitorPipeline;
    private EventRuleService eventRuleService;
    private EventPersistence eventPersistence;
    private ApmReadRepository reader;
    private AlarmResponseExecutor responseExecutor;

    @BeforeEach
    void setUp() throws Exception {
        ruleStore = new InMemoryEventRuleStore();
        alarmStore = new AlarmStore(TestMonitorRecordIds.create());
        AlarmSilenceStore alarmSilenceStore = new AlarmSilenceStore();
        reader = mock(ApmReadRepository.class);
        when(reader.queryErrorRate(anyString())).thenReturn(new ErrorRateSnapshot(10, 100));
        when(reader.queryRequestCount(anyString())).thenReturn(0L);
        eventRuleService = new EventRuleService(ruleStore);
        ThresholdEvaluationService evaluationService = new ThresholdEvaluationService(reader, TestStorageSupport.storage());
        RuleMetricEvaluationService ruleMetricEvaluationService = new RuleMetricEvaluationService(
                reader, TestStorageSupport.storage(), new MetricCoreCatalogService());
        MetricCoreCatalogService metricCoreCatalogService = new MetricCoreCatalogService();
        ThresholdAlarmMessageFormatter messageFormatter = new ThresholdAlarmMessageFormatter(metricCoreCatalogService);
        SingleMetricRuleEvaluator singleMetricRuleEvaluator = new SingleMetricRuleEvaluator(
                evaluationService, ruleMetricEvaluationService, messageFormatter);
        EventRecordFactory eventRecordFactory = new EventRecordFactory(TestMonitorRecordIds.create());
        eventPersistence = mock(EventPersistence.class);
        when(eventPersistence.isPersistenceEnabled()).thenReturn(true);
        EventAlarmOpener eventAlarmOpener = TestBeanSupport.eventAlarmOpener(
                alarmStore, eventPersistence);
        responseExecutor = mock(AlarmResponseExecutor.class);
        monitorPipeline = new EventRulePipeline(
                singleMetricRuleEvaluator,
                alarmSilenceStore,
                eventRecordFactory,
                eventPersistence,
                eventAlarmOpener,
                responseExecutor,
                5);
    }

    @Test
    void opensAlertWhenThresholdBreached() {
        EventRule rule = eventRuleService.createRule(new EventRuleStore.CreateRequest(
                "checkout error rate", "checkout", 0.05, EventRule.COMPARATOR_GT, true));
        monitorPipeline.evaluateRule(rule);
        assertThat(alarmStore.findOpenByService("checkout")).isEmpty();
        assertThat(alarmStore.listRecent(1)).hasSize(1);
    }

    @Test
    void createdRuleProducesRawEventAndConvergesToOpenAlert() {
        EventRule created = eventRuleService.createRule(new EventRuleStore.CreateRequest(
                "checkout error rate",
                "checkout",
                0.05,
                EventRule.COMPARATOR_GT,
                true));

        monitorPipeline.evaluateRule(created);

        ArgumentCaptor<EventRecord> eventCaptor = ArgumentCaptor.forClass(EventRecord.class);
        verify(eventPersistence).persist(eventCaptor.capture());
        EventRecord eventRecord = eventCaptor.getValue();
        assertThat(eventRecord.id()).startsWith("E");
        assertThat(eventRecord.ruleId()).isEqualTo(created.id());
        assertThat(eventRecord.status()).isEqualTo(EventRecord.STATUS_TRIGGER);
        assertThat(eventRecord.service()).isEqualTo("checkout");
        assertThat(eventRecord.triggeredAt()).isEqualTo(PortalTimeParser.eventBucketNow());

        assertThat(alarmStore.listRecent(1))
                .hasSize(1)
                .first()
                .satisfies(alert -> {
                    assertThat(alert.status()).isEqualTo(Alarm.STATUS_RESOLVED);
                    assertThat(alert.level()).isEqualTo("critical");
                    assertThat(alert.triggeredAt()).isEqualTo(PortalTimeParser.eventBucketNow());
                    assertThat(alert.resolvedAt()).isEqualTo(PortalTimeParser.eventBucketNow());
                });
    }

    @Test
    void emitsResolvedEventWhenPreviouslyFiringRuleRecovers() throws Exception {
        when(reader.queryErrorRate(anyString()))
                .thenReturn(new ErrorRateSnapshot(10, 100))
                .thenReturn(new ErrorRateSnapshot(0, 100));
        EventRule rule = eventRuleService.createRule(new EventRuleStore.CreateRequest(
                "checkout error rate", "checkout", 0.05, EventRule.COMPARATOR_GT, true));

        monitorPipeline.evaluateRule(rule);
        monitorPipeline.evaluateRule(rule);

        ArgumentCaptor<EventRecord> persisted = ArgumentCaptor.forClass(EventRecord.class);
        verify(eventPersistence, times(2)).persist(persisted.capture());
        assertThat(persisted.getAllValues())
                .extracting(EventRecord::status)
                .containsExactly(EventRecord.STATUS_TRIGGER, EventRecord.STATUS_NORMAL);

        ArgumentCaptor<EventRecord> dispatched = ArgumentCaptor.forClass(EventRecord.class);
        verify(responseExecutor, times(2)).dispatch(
                org.mockito.ArgumentMatchers.any(Alarm.class), dispatched.capture());
        assertThat(dispatched.getAllValues().get(1).message()).startsWith("已恢复：");
    }

    @Test
    void missingDataDoesNotResolveUntilGroupIsExplicitlyObservedNormal() {
        SingleMetricRuleEvaluator evaluator = mock(SingleMetricRuleEvaluator.class);
        RuleEvaluationResult firing = new RuleEvaluationResult(
                true, "critical", "breached", "threshold", "checkout", "checkout",
                "service.error.pct", "错误率", "%", 10.0, 5.0, "gt");
        RuleEvaluationResult normal = new RuleEvaluationResult(
                false, "warning", "", "threshold", "checkout", "checkout",
                "service.error.pct", "错误率", "%", 1.0, 5.0, "gt");
        when(evaluator.evaluateAllIncludingNormal(
                org.mockito.ArgumentMatchers.any(EventRule.class), anyLong()))
                .thenReturn(List.of(firing), List.of(), List.of(normal));
        EventPersistence persistence = mock(EventPersistence.class);
        EventAlarmOpener opener = TestBeanSupport.eventAlarmOpener(
                new AlarmStore(TestMonitorRecordIds.create()), persistence);
        AlarmResponseExecutor responses = mock(AlarmResponseExecutor.class);
        EventRulePipeline pipeline = new EventRulePipeline(
                evaluator,
                new AlarmSilenceStore(),
                new EventRecordFactory(TestMonitorRecordIds.create()),
                persistence,
                opener,
                responses,
                5);
        EventRule rule = eventRuleService.createRule(new EventRuleStore.CreateRequest(
                "checkout error rate", "checkout", 0.05, EventRule.COMPARATOR_GT, true));

        pipeline.evaluateRule(rule); // firing
        pipeline.evaluateRule(rule); // no data: retain firing state, emit nothing
        pipeline.evaluateRule(rule); // explicitly observed below threshold: resolve

        ArgumentCaptor<EventRecord> events = ArgumentCaptor.forClass(EventRecord.class);
        verify(responses, times(2)).dispatch(
                org.mockito.ArgumentMatchers.any(Alarm.class), events.capture());
        assertThat(events.getAllValues())
                .extracting(EventRecord::status)
                .containsExactly(EventRecord.STATUS_TRIGGER, EventRecord.STATUS_NORMAL);
    }

}
