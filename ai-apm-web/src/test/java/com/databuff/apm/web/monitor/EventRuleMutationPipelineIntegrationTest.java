package com.databuff.apm.web.monitor;

import com.databuff.apm.web.TestMetricCoreSupport;
import com.databuff.apm.web.TestStorageSupport;
import com.databuff.apm.web.ai.TestBeanSupport;
import com.databuff.apm.web.monitor.eval.SingleMetricRuleEvaluator;
import com.databuff.apm.web.monitor.eval.ThresholdAlarmMessageFormatter;
import com.databuff.apm.web.monitor.pipeline.AlarmResponseExecutor;
import com.databuff.apm.web.monitor.pipeline.EventAlarmOpener;
import com.databuff.apm.web.monitor.pipeline.EventRecord;
import com.databuff.apm.web.monitor.pipeline.EventRecordFactory;
import com.databuff.apm.web.monitor.pipeline.EventRulePipeline;
import com.databuff.apm.web.portal.PortalTimeParser;
import com.databuff.apm.web.persistence.EventPersistence;

import com.databuff.apm.common.query.ApmQueryModels.ErrorRateSnapshot;
import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.web.metric.MetricCoreCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end alarm-detection pipeline tests for catalog (queryJson-backed) rules. Only the
 * bottom-most {@link ApmReadRepository} is mocked with realistic {@link ErrorRateSnapshot}
 * fixtures; everything above — {@link RuleMetricEvaluationService} (SQL building + scalar
 * computation), {@link SingleMetricRuleEvaluator} (threshold / mutation detection),
 * {@link EventRulePipeline}, {@link EventRecordFactory}, {@link EventAlarmOpener} and
 * {@link AlarmStore} — runs against production code.
 */
class EventRuleMutationPipelineIntegrationTest {

    private ApmReadRepository reader;
    private EventRuleService eventRuleService;
    private EventRulePipeline monitorPipeline;
    private AlarmStore alarmStore;
    private EventPersistence eventPersistence;

    @BeforeEach
    void setUp() throws Exception {
        reader = mock(ApmReadRepository.class);
        eventRuleService = new EventRuleService(new InMemoryEventRuleStore());
        alarmStore = new AlarmStore(TestMonitorRecordIds.create());
        AlarmSilenceStore alarmSilenceStore = new AlarmSilenceStore();
        ThresholdEvaluationService evaluationService =
                new ThresholdEvaluationService(reader, TestStorageSupport.storage());
        MetricCoreCatalogService catalog = TestMetricCoreSupport.catalogWithServiceMetrics();
        RuleMetricEvaluationService ruleMetricEvaluationService =
                new RuleMetricEvaluationService(reader, TestStorageSupport.storage(), catalog);
        ThresholdAlarmMessageFormatter messageFormatter = new ThresholdAlarmMessageFormatter(catalog);
        SingleMetricRuleEvaluator singleMetricRuleEvaluator = new SingleMetricRuleEvaluator(
                evaluationService, ruleMetricEvaluationService, messageFormatter);
        EventRecordFactory eventRecordFactory = new EventRecordFactory(TestMonitorRecordIds.create());
        eventPersistence = mock(EventPersistence.class);
        when(eventPersistence.isPersistenceEnabled()).thenReturn(true);
        EventAlarmOpener eventAlarmOpener =
                TestBeanSupport.eventAlarmOpener(alarmStore, eventPersistence);
        AlarmResponseExecutor responseExecutor = new AlarmResponseExecutor(
                TestBeanSupport.notifyChannelService());
        monitorPipeline = new EventRulePipeline(
                singleMetricRuleEvaluator,
                alarmSilenceStore,
                eventRecordFactory,
                eventPersistence,
                eventAlarmOpener,
                responseExecutor,
                5);
    }

    private EventRule createMutationRule(String fluctuate, double threshold, String comparePeriodSec) {
        String queryJson = "{\"1\":{\"way\":\"mutation\",\"period\":300"
                + (comparePeriodSec == null ? "" : ",\"comparePeriod\":" + comparePeriodSec)
                + ",\"fluctuate\":\"" + fluctuate + "\""
                + ",\"view_unit\":\"%\""
                + ",\"thresholds\":{\"critical\":" + threshold + "}"
                + ",\"A\":{\"metric\":\"service.error.pct\",\"from\":[]}}}";
        return eventRuleService.createRule(new EventRuleStore.CreateRequest(
                "mutation pipeline rule",
                EventRule.CLASSIFY_SINGLE,
                "checkout",
                EventRule.METRIC_ERROR_RATE,
                threshold,
                EventRule.COMPARATOR_GT,
                true,
                EventRule.WAY_MUTATION,
                queryJson));
    }

    private EventRule createCatalogThresholdRule(double threshold) {
        String queryJson = "{\"1\":{\"way\":\"threshold\",\"period\":300"
                + ",\"view_unit\":\"%\""
                + ",\"thresholds\":{\"critical\":" + threshold + "}"
                + ",\"A\":{\"metric\":\"service.error.pct\",\"from\":[]}}}";
        return eventRuleService.createRule(new EventRuleStore.CreateRequest(
                "threshold pipeline rule",
                EventRule.CLASSIFY_SINGLE,
                "checkout",
                EventRule.METRIC_ERROR_RATE,
                threshold,
                EventRule.COMPARATOR_GT,
                true,
                EventRule.WAY_THRESHOLD,
                queryJson));
    }

    @Test
    void mutationYoyUpOpensAlarmThroughRealPipeline() throws Exception {
        // current 8%, previous 5% -> ratio 0.6 > threshold 0.5
        when(reader.queryErrorRate(anyString()))
                .thenReturn(new ErrorRateSnapshot(8, 100))
                .thenReturn(new ErrorRateSnapshot(5, 100));

        EventRule rule = createMutationRule("yoyUp", 0.5, "3600");
        monitorPipeline.evaluateRule(rule);

        ArgumentCaptor<EventRecord> eventCaptor = ArgumentCaptor.forClass(EventRecord.class);
        verify(eventPersistence).persist(eventCaptor.capture());
        EventRecord event = eventCaptor.getValue();
        assertThat(event.status()).isEqualTo(EventRecord.STATUS_TRIGGER);
        assertThat(event.detectionWay()).isEqualTo(EventRule.WAY_MUTATION);
        assertThat(event.service()).isEqualTo("checkout");
        assertThat(event.message()).isEqualTo("错误率的变化值60%超过阈值50%");

        assertThat(alarmStore.listRecent(1)).hasSize(1);
        Alarm alarm = alarmStore.listRecent(1).get(0);
        assertThat(alarm.message()).isEqualTo("错误率的变化值60%超过阈值50%");
        assertThat(alarm.detectionWay()).isEqualTo(EventRule.WAY_MUTATION);
        assertThat(alarm.service()).isEqualTo("checkout");
    }

    @Test
    void mutationYoyUpDecreaseDoesNotOpenAlarmThroughRealPipeline() throws Exception {
        // current 4%, previous 5% -> ratio -0.2, wrong direction -> no event, no alarm
        when(reader.queryErrorRate(anyString()))
                .thenReturn(new ErrorRateSnapshot(4, 100))
                .thenReturn(new ErrorRateSnapshot(5, 100));

        EventRule rule = createMutationRule("yoyUp", 0.5, "3600");
        monitorPipeline.evaluateRule(rule);

        verify(eventPersistence, never()).persist(org.mockito.ArgumentMatchers.any());
        assertThat(alarmStore.listRecent(10)).isEmpty();
    }

    @Test
    void mutationValDownOpensAlarmWhenMetricDecreasedThroughRealPipeline() throws Exception {
        // current 3%, previous 5% -> valDown change = 5 - 3 = 2 > threshold 2? use 1.5 to be safe
        when(reader.queryErrorRate(anyString()))
                .thenReturn(new ErrorRateSnapshot(3, 100))
                .thenReturn(new ErrorRateSnapshot(5, 100));

        EventRule rule = createMutationRule("valDown", 1.5, "3600");
        monitorPipeline.evaluateRule(rule);

        ArgumentCaptor<EventRecord> eventCaptor = ArgumentCaptor.forClass(EventRecord.class);
        verify(eventPersistence).persist(eventCaptor.capture());
        assertThat(eventCaptor.getValue().status()).isEqualTo(EventRecord.STATUS_TRIGGER);
        assertThat(eventCaptor.getValue().message()).isEqualTo("错误率的变化值2%超过阈值1.5%");
        assertThat(alarmStore.listRecent(1)).hasSize(1);
    }

    @Test
    void catalogThresholdOpensAlarmThroughRealPipeline() throws Exception {
        // current 12% > threshold 5 (percentage points)
        when(reader.queryErrorRate(anyString())).thenReturn(new ErrorRateSnapshot(12, 100));

        EventRule rule = createCatalogThresholdRule(5.0);
        monitorPipeline.evaluateRule(rule);

        ArgumentCaptor<EventRecord> eventCaptor = ArgumentCaptor.forClass(EventRecord.class);
        verify(eventPersistence).persist(eventCaptor.capture());
        EventRecord event = eventCaptor.getValue();
        assertThat(event.status()).isEqualTo(EventRecord.STATUS_TRIGGER);
        assertThat(event.detectionWay()).isEqualTo(EventRule.WAY_THRESHOLD);
        assertThat(event.message()).isEqualTo("错误率的12%值超过阈值5%");

        assertThat(alarmStore.listRecent(1)).hasSize(1);
        assertThat(alarmStore.listRecent(1).get(0).message()).isEqualTo("错误率的12%值超过阈值5%");
    }

    @Test
    void catalogThresholdBelowThresholdDoesNotOpenAlarmThroughRealPipeline() throws Exception {
        // current 3% < threshold 5 -> no alarm
        when(reader.queryErrorRate(anyString())).thenReturn(new ErrorRateSnapshot(3, 100));

        EventRule rule = createCatalogThresholdRule(5.0);
        monitorPipeline.evaluateRule(rule);

        verify(eventPersistence, never()).persist(org.mockito.ArgumentMatchers.any());
        assertThat(alarmStore.listRecent(10)).isEmpty();
    }

    @Test
    void disabledRuleIsNotEvaluated() throws Exception {
        when(reader.queryErrorRate(anyString())).thenReturn(new ErrorRateSnapshot(99, 100));

        EventRule rule = eventRuleService.createRule(new EventRuleStore.CreateRequest(
                "disabled threshold rule",
                EventRule.CLASSIFY_SINGLE,
                "checkout",
                EventRule.METRIC_ERROR_RATE,
                5.0,
                EventRule.COMPARATOR_GT,
                false,
                EventRule.WAY_THRESHOLD,
                "{\"1\":{\"way\":\"threshold\",\"period\":300,\"thresholds\":{\"critical\":5.0},"
                        + "\"A\":{\"metric\":\"service.error.pct\",\"from\":[]}}}"));
        monitorPipeline.evaluateRule(rule);

        verify(reader, never()).queryErrorRate(anyString());
        verify(eventPersistence, never()).persist(org.mockito.ArgumentMatchers.any());
        assertThat(alarmStore.listRecent(10)).isEmpty();
    }

    @Test
    void mutationEventCarriesEventBucketTimestamp() throws Exception {
        when(reader.queryErrorRate(anyString()))
                .thenReturn(new ErrorRateSnapshot(8, 100))
                .thenReturn(new ErrorRateSnapshot(5, 100));

        EventRule rule = createMutationRule("yoyUp", 0.5, "3600");
        monitorPipeline.evaluateRule(rule);

        ArgumentCaptor<EventRecord> eventCaptor = ArgumentCaptor.forClass(EventRecord.class);
        verify(eventPersistence).persist(eventCaptor.capture());
        assertThat(eventCaptor.getValue().triggeredAt()).isEqualTo(PortalTimeParser.eventBucketNow());
    }
}
