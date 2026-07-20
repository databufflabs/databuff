package com.databuff.apm.web.monitor;

import com.databuff.apm.web.TestMetricCoreSupport;
import com.databuff.apm.web.TestStorageSupport;
import com.databuff.apm.web.ai.TestBeanSupport;
import com.databuff.apm.web.monitor.eval.SingleMetricRuleEvaluator;
import com.databuff.apm.web.monitor.eval.ThresholdAlarmMessageFormatter;
import com.databuff.apm.web.monitor.policy.ResponsePolicyService;
import com.databuff.apm.web.monitor.service.AlarmService;
import com.databuff.apm.web.persistence.AlarmPolicyPersistence;
import com.databuff.apm.web.persistence.EventPersistence;

import com.databuff.apm.common.query.ApmQueryModels.ErrorRateSnapshot;
import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.web.config.ApmStorageProperties;
import com.databuff.apm.web.metric.MetricCoreCatalogService;
import com.databuff.apm.web.monitor.pipeline.AlarmResponseExecutor;
import com.databuff.apm.web.monitor.pipeline.EventAlarmOpener;
import com.databuff.apm.web.monitor.pipeline.EventRulePipeline;
import com.databuff.apm.web.monitor.pipeline.EventRecord;
import com.databuff.apm.web.monitor.pipeline.EventRecordFactory;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(classes = EventRuleLocalIntegrationTest.Config.class)
@ActiveProfiles({"local", "test"})
class EventRuleLocalIntegrationTest {

    @Autowired
    private ApmReadRepository readRepository;

    @Autowired
    private EventPersistence eventPersistence;

    @Autowired
    private EventRuleService eventRuleService;

    @Autowired
    private EventRulePipeline monitorPipeline;

    @Autowired
    private AlarmStore alarmStore;

    @Autowired
    private AlarmService alarmService;

    @BeforeEach
    void setUp() throws Exception {
        reset(readRepository, eventPersistence);
        when(readRepository.queryErrorRate(anyString())).thenReturn(new ErrorRateSnapshot(12, 100));
        when(readRepository.queryRequestCount(anyString())).thenReturn(0L);
        when(readRepository.queryMetricScalar(anyString())).thenReturn(200.0);
    }

    @Test
    void localTestRuleCreateEventAndAlarmPath() {
        EventRule created = eventRuleService.createRule(new EventRuleStore.CreateRequest(
                "local/test checkout error rate",
                "checkout",
                0.05,
                EventRule.COMPARATOR_GT,
                true));

        monitorPipeline.evaluateRule(created);

        ArgumentCaptor<EventRecord> eventCaptor = ArgumentCaptor.forClass(EventRecord.class);
        verify(eventPersistence).persist(eventCaptor.capture());
        assertThat(eventCaptor.getValue().status()).isEqualTo(EventRecord.STATUS_TRIGGER);

        Alarm alarm = alarmStore.listRecent(1).get(0);
        assertThat(alarm.status()).isEqualTo(Alarm.STATUS_RESOLVED);
        assertThat(alarm.resolvedAt()).isEqualTo(alarm.triggeredAt());
        Assertions.assertThat(alarmService.findEvent(alarm.id())).contains(alarm);
        assertThat(alarmService.countAlarms(Map.of("serviceId", "checkout"))).isEqualTo(1);
    }

    @Configuration
    static class Config {

        @Bean
        ApmReadRepository apmReadRepository() {
            return mock(ApmReadRepository.class);
        }

        @Bean
        EventPersistence monitorRawEventPersistence() {
            return mock(EventPersistence.class);
        }

        @Bean
        ApmStorageProperties apmStorageProperties() {
            return TestStorageSupport.storage();
        }

        @Bean
        MetricCoreCatalogService metricCoreCatalogService() {
            return TestMetricCoreSupport.catalogWithServiceMetrics();
        }

        @Bean
        EventRuleStore eventRuleStore() {
            return new InMemoryEventRuleStore();
        }

        @Bean
        EventRuleService eventRuleService(EventRuleStore eventRuleStore) {
            return new EventRuleService(eventRuleStore);
        }

        @Bean
        MonitorRecordIdGenerator monitorRecordIdGenerator() {
            return TestMonitorRecordIds.create();
        }

        @Bean
        AlarmStore alarmStore(MonitorRecordIdGenerator monitorRecordIdGenerator) {
            return new AlarmStore(monitorRecordIdGenerator);
        }

        @Bean
        AlarmSilenceStore alarmSilenceStore() {
            return new AlarmSilenceStore();
        }

        @Bean
        ThresholdEvaluationService thresholdEvaluationService(
                ApmReadRepository apmReadRepository,
                ApmStorageProperties apmStorageProperties) {
            return new ThresholdEvaluationService(apmReadRepository, apmStorageProperties);
        }

        @Bean
        RuleMetricEvaluationService ruleMetricEvaluationService(
                ApmReadRepository apmReadRepository,
                ApmStorageProperties apmStorageProperties,
                MetricCoreCatalogService metricCoreCatalogService) {
            return new RuleMetricEvaluationService(
                    apmReadRepository, apmStorageProperties, metricCoreCatalogService);
        }

        @Bean
        ThresholdAlarmMessageFormatter thresholdAlarmMessageFormatter(
                MetricCoreCatalogService metricCoreCatalogService) {
            return new ThresholdAlarmMessageFormatter(metricCoreCatalogService);
        }

        @Bean
        SingleMetricRuleEvaluator singleMetricRuleEvaluator(
                ThresholdEvaluationService thresholdEvaluationService,
                RuleMetricEvaluationService ruleMetricEvaluationService,
                ThresholdAlarmMessageFormatter thresholdAlarmMessageFormatter) {
            return new SingleMetricRuleEvaluator(
                    thresholdEvaluationService, ruleMetricEvaluationService, thresholdAlarmMessageFormatter);
        }

        @Bean
        AlarmPolicyPersistence alarmPolicyPersistence(
                ApmReadRepository apmReadRepository,
                ApmStorageProperties apmStorageProperties) {
            return new AlarmPolicyPersistence(apmReadRepository, apmStorageProperties);
        }

        @Bean
        EventRecordFactory monitorRawEventFactory(MonitorRecordIdGenerator monitorRecordIdGenerator) {
            return new EventRecordFactory(monitorRecordIdGenerator);
        }

        @Bean
        EventAlarmOpener eventAlarmOpener(
                AlarmStore alarmStore,
                EventPersistence eventPersistence) {
            return TestBeanSupport.eventAlarmOpener(alarmStore, eventPersistence);
        }

        @Bean
        ResponsePolicyService responsePolicyService() {
            return new ResponsePolicyService();
        }

        @Bean
        NotifyChannelService notifyChannelService() {
            return TestBeanSupport.notifyChannelService();
        }

        @Bean
        AlarmResponseExecutor alarmResponseExecutor(
                ResponsePolicyService responsePolicyService,
                NotifyChannelService notifyChannelService) {
            return new AlarmResponseExecutor(responsePolicyService, notifyChannelService);
        }

        @Bean
        EventRulePipeline monitorPipeline(
                SingleMetricRuleEvaluator singleMetricRuleEvaluator,
                AlarmSilenceStore alarmSilenceStore,
                EventRecordFactory eventRecordFactory,
                EventPersistence eventPersistence,
                EventAlarmOpener eventAlarmOpener,
                AlarmResponseExecutor responseExecutor) {
            return new EventRulePipeline(
                    singleMetricRuleEvaluator,
                    alarmSilenceStore,
                    eventRecordFactory,
                    eventPersistence,
                    eventAlarmOpener,
                    responseExecutor,
                    5);
        }

        @Bean
        AlarmService alarmService(
                AlarmStore alarmStore,
                EventRuleService eventRuleService,
                EventPersistence eventPersistence) {
            return new AlarmService(alarmStore, eventRuleService, eventPersistence);
        }
    }
}
