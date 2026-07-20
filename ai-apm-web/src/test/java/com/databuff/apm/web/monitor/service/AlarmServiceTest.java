package com.databuff.apm.web.monitor.service;

import com.databuff.apm.common.time.ApmTimeZones;
import com.databuff.apm.common.storage.ApmConfigRepository;
import com.databuff.apm.web.monitor.Alarm;
import com.databuff.apm.web.monitor.AlarmStore;
import com.databuff.apm.web.monitor.EventRule;
import com.databuff.apm.web.monitor.EventRuleService;
import com.databuff.apm.web.portal.PortalTimeParser;
import com.databuff.apm.web.monitor.TestMonitorRecordIds;
import com.databuff.apm.web.persistence.EventPersistence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AlarmServiceTest {

    private AlarmStore alarmStore;
    private AlarmService alarmService;
    private EventRuleService eventRuleService;

    @BeforeEach
    void setUp() {
        alarmStore = new AlarmStore(TestMonitorRecordIds.create());
        eventRuleService = mock(EventRuleService.class);
        alarmService = new AlarmService(alarmStore, eventRuleService, null);
        alarmStore.open("demo-order", EventRule.WAY_THRESHOLD, "critical", "error rate exceeded");
    }

    @Test
    void listReturnsPortalAlarms() {
        long now = System.currentTimeMillis();
        Map<String, Object> response = alarmService.list(Map.of(
                "fromTime", now - 86_400_000L,
                "toTime", now + 60_000L));
        assertThat(response.get("status")).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("serviceName")).isEqualTo("demo-order");
        assertThat(((Number) data.get("total")).longValue()).isEqualTo(1L);
        assertThat(((Number) list.get(0).get("eventCnt")).longValue()).isEqualTo(1L);
    }

    @Test
    void countMatchesOpenAlerts() {
        long now = System.currentTimeMillis();
        assertThat(alarmService.countAlarms(Map.of(
                "fromTime", now - 86_400_000L,
                "toTime", now + 60_000L))).isEqualTo(1);
    }

    @Test
    void listUsesAlarmTriggeredAtAsTimestamp() {
        Instant triggeredAt = Instant.now().minusSeconds(300);
        Alarm alarm = alarmStore.findOpenByService("demo-order").orElseThrow();
        alarmStore.persistExisting(new Alarm(
                alarm.id(),
                alarm.policyId(),
                alarm.service(),
                alarm.detectionWay(),
                alarm.level(),
                alarm.message(),
                alarm.status(),
                triggeredAt,
                alarm.resolvedAt()));

        long now = System.currentTimeMillis();
        Map<String, Object> response = alarmService.list(Map.of(
                "fromTime", now - 86_400_000L,
                "toTime", now + 60_000L));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
        assertThat(((Number) list.get(0).get("startTriggerTime")).longValue()).isEqualTo(triggeredAt.toEpochMilli());
        assertThat(((Number) list.get(0).get("timestamp")).longValue()).isEqualTo(triggeredAt.toEpochMilli());
        assertThat(((Number) list.get(0).get("eventCnt")).longValue()).isEqualTo(1L);
    }

    @Test
    void listUsesAlarmMessageAsDescription() {
        long now = System.currentTimeMillis();
        Map<String, Object> response = alarmService.list(Map.of(
                "fromTime", now - 86_400_000L,
                "toTime", now + 60_000L));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) ((Map<?, ?>) response.get("data")).get("list");
        assertThat(list.get(0).get("description")).isEqualTo("error rate exceeded");
    }

    @Test
    void listUsesResolvedTimeForEndTriggerTimeWithDurationPlusOneMinute() {
        Instant triggeredAt = Instant.parse("2026-06-09T10:56:00Z");
        Instant resolvedAt = triggeredAt.plusSeconds(362);
        Alarm alarm = alarmStore.findOpenByService("demo-order").orElseThrow();
        alarmStore.persistExisting(new Alarm(
                alarm.id(),
                alarm.policyId(),
                alarm.service(),
                alarm.detectionWay(),
                alarm.level(),
                alarm.message(),
                Alarm.STATUS_RESOLVED,
                triggeredAt,
                resolvedAt));

        Map<String, Object> response = alarmService.list(Map.of(
                "fromTime", triggeredAt.minusSeconds(60).toEpochMilli(),
                "toTime", triggeredAt.plusSeconds(600).toEpochMilli()));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) ((Map<?, ?>) response.get("data")).get("list");
        assertThat(list).hasSize(1);
        // duration = (end - start) + 1 minute
        assertThat(((Number) list.get(0).get("duration")).longValue()).isEqualTo(422L);
        assertThat(((Number) list.get(0).get("endTriggerTime")).longValue())
                .isEqualTo(resolvedAt.toEpochMilli());
    }

    @Test
    void listUsesOneMinuteDurationWhenResolvedAtEqualsTriggeredAt() {
        Instant triggeredAt = Instant.parse("2026-06-09T10:56:00Z");
        Alarm alarm = alarmStore.findOpenByService("demo-order").orElseThrow();
        alarmStore.persistExisting(new Alarm(
                alarm.id(),
                alarm.policyId(),
                alarm.service(),
                alarm.detectionWay(),
                alarm.level(),
                alarm.message(),
                Alarm.STATUS_RESOLVED,
                triggeredAt,
                triggeredAt));

        Map<String, Object> response = alarmService.list(Map.of(
                "fromTime", triggeredAt.minusSeconds(60).toEpochMilli(),
                "toTime", triggeredAt.plusSeconds(600).toEpochMilli()));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) ((Map<?, ?>) response.get("data")).get("list");
        assertThat(((Number) list.get(0).get("duration")).longValue()).isEqualTo(60L);
    }

    @Test
    void detailReturnsEnrichedPortalFields() {
        when(eventRuleService.listRules()).thenReturn(List.of(demoOrderRule()));
        Alarm alarm = alarmStore.findOpenByService("demo-order").orElseThrow();

        Map<String, Object> response = alarmService.detail(alarm.id());
        @SuppressWarnings("unchecked")
        Map<String, Object> detail = (Map<String, Object>) response.get("data");
        assertThat(detail.get("type")).isEqualTo("convergence");
        assertThat(detail.get("triggerObject")).isEqualTo("demo-order");
        assertThat(detail.get("abnormalMetrics")).isEqualTo(List.of("service.http.cnt"));
        assertThat(detail.get("eventId")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> tags = (Map<String, Object>) detail.get("tags");
        assertThat(tags.get("service")).isEqualTo(List.of("demo-order"));
        assertThat(tags.get("ruleName")).isEqualTo(List.of("checkout errors"));
    }

    @Test
    void trendReturnsPortalCompatibleBuckets() {
        long now = System.currentTimeMillis();
        Map<String, Object> response = alarmService.trend(Map.of(
                "fromTime", now - 86_400_000L,
                "toTime", now + 60_000L,
                "interval", 60));
        assertThat(response.get("status")).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> trendData = (Map<String, Object>) data.get("data");
        assertThat(trendData).isNotEmpty();
        long total = trendData.values().stream()
                .mapToLong(value -> ((Number) ((Map<?, ?>) value).get("count")).longValue())
                .sum();
        assertThat(total).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void trendCountsAlarmInTriggerMinuteBucket() {
        alarmStore.clear();
        long start = PortalTimeParser.alignToMinuteFloor(System.currentTimeMillis() - 10 * 60_000L);
        alarmStore.open("demo-order", EventRule.WAY_THRESHOLD, "critical", "error rate exceeded",
                Instant.ofEpochMilli(start));

        Map<String, Object> response = alarmService.trend(Map.of(
                "fromTime", start,
                "toTime", start + 5 * 60_000L,
                "interval", 60));

        Map<String, Object> trendData = trendData(response);
        assertThat(statusCount(trendData, start, "count")).isEqualTo(1L);
        for (int i = 1; i < 5; i++) {
            assertThat(statusCount(trendData, start + i * 60_000L, "count")).isZero();
        }
    }

    @Test
    void trendIgnoresAlarmTriggeredBeforeQueryRange() {
        alarmStore.clear();
        long from = PortalTimeParser.alignToMinuteFloor(System.currentTimeMillis() - 10 * 60_000L);
        alarmStore.open("demo-order", EventRule.WAY_THRESHOLD, "critical", "error rate exceeded",
                Instant.ofEpochMilli(from - 2 * 60_000L));

        Map<String, Object> response = alarmService.trend(Map.of(
                "fromTime", from,
                "toTime", from + 3 * 60_000L,
                "interval", 60));

        Map<String, Object> trendData = trendData(response);
        for (int i = 0; i < 3; i++) {
            assertThat(statusCount(trendData, from + i * 60_000L, "count")).isZero();
        }
    }

    @Test
    void trendCountsResolvedAlarmOnlyInTriggerBucket() {
        alarmStore.clear();
        long start = PortalTimeParser.alignToMinuteFloor(System.currentTimeMillis() - 10 * 60_000L);
        Alarm alarm = alarmStore.open("demo-order", EventRule.WAY_THRESHOLD, "critical", "error rate exceeded",
                Instant.ofEpochMilli(start));
        alarmStore.persistExisting(alarm.resolve(Instant.ofEpochMilli(start + 300_000L)));

        Map<String, Object> response = alarmService.trend(Map.of(
                "fromTime", start,
                "toTime", start + 6 * 60_000L,
                "interval", 60));

        Map<String, Object> trendData = trendData(response);
        assertThat(statusCount(trendData, start, "count")).isEqualTo(1L);
        for (int i = 1; i < 6; i++) {
            assertThat(statusCount(trendData, start + i * 60_000L, "count")).isZero();
        }
    }

    @Test
    void queryParamsReturnsServiceAndRuleNameOptions() {
        when(eventRuleService.listRules()).thenReturn(List.of(demoOrderRule()));

        long now = System.currentTimeMillis();
        Map<String, Object> response = alarmService.queryParams(Map.of(
                "fromTime", now - 86_400_000L,
                "toTime", now + 60_000L));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> services = (List<Map<String, String>>) data.get("service");
        @SuppressWarnings("unchecked")
        List<String> ruleNames = (List<String>) data.get("ruleName");
        assertThat(services).isNotEmpty();
        assertThat(services.get(0).get("serviceName")).isEqualTo("demo-order");
        assertThat(services.get(0).get("serviceId")).isNotBlank();
        assertThat(ruleNames).contains("checkout errors");
    }

    @Test
    void listIncludesOpenAlarmWhenTriggeredAtMatchesPortalEventBucket() {
        long queryNow = LocalDateTime.of(2026, 6, 9, 15, 0, 10)
                .atZone(ApmTimeZones.SHANGHAI)
                .toInstant()
                .toEpochMilli();
        long portalTo = PortalTimeParser.metricQueryEndMillis(queryNow);
        Instant triggeredAt = PortalTimeParser.eventBucketInstant(portalTo);

        alarmStore.persistExisting(new Alarm(
                alarmStore.findOpenByService("demo-order").orElseThrow().id(),
                2L,
                "demo-order",
                EventRule.WAY_THRESHOLD,
                "critical",
                "error rate exceeded",
                Alarm.STATUS_OPEN,
                triggeredAt,
                null));

        Map<String, Object> response = alarmService.list(Map.of(
                "fromTime", queryNow - 3_600_000L,
                "toTime", portalTo));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) ((Map<?, ?>) response.get("data")).get("list");
        assertThat(list).hasSize(1);
        assertThat(((Number) list.get(0).get("startTriggerTime")).longValue()).isEqualTo(triggeredAt.toEpochMilli());
        assertThat(((Number) list.get(0).get("timestamp")).longValue()).isEqualTo(triggeredAt.toEpochMilli());
    }

    @Test
    void listFiltersByServiceIdInTrigger() {
        alarmStore.open("billing", EventRule.WAY_THRESHOLD, "warning", "billing alert");
        long now = System.currentTimeMillis();
        Map<String, Object> body = Map.of(
                "fromTime", now - 86_400_000L,
                "toTime", now + 60_000L,
                "trigger", Map.of("serviceId", List.of("demo-order")));
        Map<String, Object> response = alarmService.list(body);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("serviceName")).isEqualTo("demo-order");
    }

    @Test
    void listFiltersByRuleNameInTrigger() {
        when(eventRuleService.listRules()).thenReturn(List.of(
                demoOrderRule(),
                billingRule()));
        alarmStore.open("billing", EventRule.WAY_THRESHOLD, "warning", "billing alert");

        long now = System.currentTimeMillis();
        Map<String, Object> response = alarmService.list(Map.of(
                "fromTime", now - 86_400_000L,
                "toTime", now + 60_000L,
                "trigger", Map.of("ruleName", List.of("checkout errors"))));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("serviceName")).isEqualTo("demo-order");
    }

    @Test
    void listPaginatesByOffsetAndSize() {
        long now = System.currentTimeMillis();
        long base = now - 3_600_000L;
        alarmStore.open("billing", EventRule.WAY_THRESHOLD, "warning", "billing alert",
                Instant.ofEpochMilli(base + 60_000L));
        alarmStore.open("inventory", EventRule.WAY_THRESHOLD, "warning", "inventory alert",
                Instant.ofEpochMilli(base + 120_000L));

        Map<String, Object> response = alarmService.list(Map.of(
                "fromTime", now - 86_400_000L,
                "toTime", now + 60_000L,
                "offset", 1,
                "size", 1,
                "sortField", "timestamp",
                "sortOrder", "desc"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) data.get("list");
        assertThat(((Number) data.get("total")).longValue()).isEqualTo(3L);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("serviceName")).isEqualTo("inventory");
    }

    @Test
    void listSortsByTimestampAscending() {
        long now = System.currentTimeMillis();
        long base = now - 3_600_000L;
        alarmStore.open("billing", EventRule.WAY_THRESHOLD, "warning", "billing alert",
                Instant.ofEpochMilli(base + 120_000L));
        alarmStore.open("inventory", EventRule.WAY_THRESHOLD, "warning", "inventory alert",
                Instant.ofEpochMilli(base + 60_000L));

        Map<String, Object> response = alarmService.list(Map.of(
                "fromTime", now - 86_400_000L,
                "toTime", now + 60_000L,
                "offset", 0,
                "size", 1,
                "sortField", "timestamp",
                "sortOrder", "asc"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) ((Map<?, ?>) response.get("data")).get("list");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("serviceName")).isEqualTo("inventory");
    }

    @Test
    void detailReturnsAbnormalMetricsFromLinkedEventsWhenRuleServiceIsWildcard() {
        EventPersistence eventPersistence = mock(EventPersistence.class);
        when(eventPersistence.isPersistenceEnabled()).thenReturn(true);
        Alarm alarm = alarmStore.findOpenByService("demo-order").orElseThrow();
        when(eventPersistence.listForAlarm(alarm)).thenReturn(List.of(
                new ApmConfigRepository.EventRow(
                        "E1",
                        2L,
                        "服务入口错误率过高",
                        "demo-order",
                        EventRule.WAY_THRESHOLD,
                        "critical",
                        "trigger",
                        "error rate exceeded",
                        "demo-order",
                        false,
                        Instant.now())));
        when(eventRuleService.findRule(2L)).thenReturn(Optional.of(wildcardErrorRateRule()));
        when(eventRuleService.listRules()).thenReturn(List.of(wildcardErrorRateRule()));

        AlarmService service = new AlarmService(alarmStore, eventRuleService, eventPersistence);

        Map<String, Object> response = service.detail(alarm.id());
        @SuppressWarnings("unchecked")
        Map<String, Object> detail = (Map<String, Object>) response.get("data");
        assertThat(detail.get("abnormalMetrics")).isEqualTo(List.of("service.error.pct"));
        assertThat(detail.get("ruleName")).isEqualTo("服务入口错误率过高");
    }

    private static EventRule demoOrderRule() {
        return new EventRule(
                2L,
                "checkout errors",
                EventRule.CLASSIFY_SINGLE,
                EventRule.WAY_THRESHOLD,
                "demo-order",
                "service.http.cnt",
                0.05,
                EventRule.COMPARATOR_GT,
                true,
                null,
                null);
    }

    private static EventRule wildcardErrorRateRule() {
        return new EventRule(
                2L,
                "服务入口错误率过高",
                EventRule.CLASSIFY_SINGLE,
                EventRule.WAY_THRESHOLD,
                "*",
                "service.error.pct",
                10.0,
                EventRule.COMPARATOR_GT,
                true,
                null,
                null);
    }

    private static EventRule billingRule() {
        return new EventRule(
                3L,
                "billing latency",
                EventRule.CLASSIFY_SINGLE,
                EventRule.WAY_THRESHOLD,
                "billing",
                "service.http.latency",
                0.1,
                EventRule.COMPARATOR_GT,
                true,
                null,
                null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> trendData(Map<String, Object> response) {
        return (Map<String, Object>) ((Map<?, ?>) response.get("data")).get("data");
    }

    private static long statusCount(Map<String, Object> trendData, long bucket, String status) {
        @SuppressWarnings("unchecked")
        Map<String, Object> counts = (Map<String, Object>) trendData.get(String.valueOf(bucket));
        assertThat(counts).as("trend bucket %s", bucket).isNotNull();
        return ((Number) counts.get(status)).longValue();
    }
}
