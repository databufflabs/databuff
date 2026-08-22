package com.databuff.apm.web.monitor.webhook;

import com.databuff.apm.web.monitor.Alarm;
import com.databuff.apm.web.monitor.EventRule;
import com.databuff.apm.web.monitor.pipeline.EventRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookAlertAssemblerTest {

    private static Alarm alarm(String message) {
        return new Alarm(
                "A1", 7L, "order-service", EventRule.WAY_THRESHOLD, "critical", message,
                Alarm.STATUS_RESOLVED, Instant.parse("2026-08-22T10:38:00Z"),
                Instant.parse("2026-08-22T10:38:00Z"));
    }

    private static EventRecord event() {
        return new EventRecord(
                "E1", 12L, "订单服务错误率", "order-service", "threshold", "critical",
                EventRecord.STATUS_TRIGGER, "错误率（TimeoutException）的8.7%值超过阈值8%",
                "TimeoutException", false, Instant.parse("2026-08-22T10:38:00Z"),
                "service.error.pct", "错误率", "%", 8.7, 8.0, "gt");
    }

    @Test
    void triggerEventOverridesOneShotResolvedAlarmState() {
        Map<String, Object> payload = new WebhookAlertAssembler().assemble(alarm("msg"), event(), "databuff-apm");
        assertThat(payload).containsKeys(
                "alarmId", "eventId", "title", "description", "severity", "status", "source",
                "service", "groupKey", "detectionWay", "rule", "metric", "fingerprint",
                "triggeredAt", "silenced", "tags");
        // legacy duplicate of description is gone
        assertThat(payload).doesNotContainKey("message");
        assertThat(payload.get("alarmId")).isEqualTo("A1");
        assertThat(payload.get("eventId")).isEqualTo("E1");
        assertThat(payload.get("severity")).isEqualTo("critical");
        assertThat(payload.get("status")).isEqualTo("firing");
        assertThat(payload.get("source")).isEqualTo("databuff-apm");
        assertThat(payload.get("service")).isEqualTo("order-service");
        assertThat(payload.get("groupKey")).isEqualTo("TimeoutException");
        assertThat(payload.get("detectionWay")).isEqualTo("threshold");
        assertThat(payload.get("fingerprint")).isEqualTo("rule:12:service:TimeoutException");
        assertThat(payload.get("rule")).isEqualTo(Map.of("id", 12L, "name", "订单服务错误率"));
        assertThat(payload.get("tags")).isEqualTo(Map.of(
                "service", "order-service", "ruleName", "订单服务错误率"));
        assertThat(payload.get("triggeredAt")).isEqualTo("2026-08-22T10:38:00Z");
        assertThat(payload).doesNotContainKey("resolvedAt");
        assertThat(payload.get("silenced")).isEqualTo(false);
    }

    @Test
    void recoveryEventProducesResolvedStateAndTimestamp() {
        EventRecord recovery = new EventRecord(
                "E2", 12L, "订单服务错误率", "order-service", "threshold", "critical",
                EventRecord.STATUS_RECOVER, "recovered", "TimeoutException", false,
                Instant.parse("2026-08-22T10:40:00Z"));

        Map<String, Object> payload = new WebhookAlertAssembler().assemble(
                new Alarm("A1", 7L, "order-service", "threshold", "critical", "recovered",
                        Alarm.STATUS_OPEN, Instant.parse("2026-08-22T10:38:00Z"), null),
                recovery,
                "databuff-apm");

        assertThat(payload).containsEntry("status", "resolved")
                .containsEntry("resolvedAt", "2026-08-22T10:40:00Z");
    }

    @Test
    void fingerprintUsesRuleServiceGroupKey() {
        assertThat(WebhookAlertAssembler.fingerprint(alarm("m"), event()))
                .isEqualTo("rule:12:service:TimeoutException");
        // no event context -> policyId fallback, groupKey falls back to service
        assertThat(WebhookAlertAssembler.fingerprint(alarm("m"), null))
                .isEqualTo("rule:7:service:order-service");
        // blank groupKey falls back to service
        EventRecord noGroup = new EventRecord(
                "E2", 12L, "r", "order-service", "threshold", "critical",
                EventRecord.STATUS_TRIGGER, "m", " ", false, Instant.now());
        assertThat(WebhookAlertAssembler.fingerprint(alarm("m"), noGroup))
                .isEqualTo("rule:12:service:order-service");
    }

    @Test
    void metricBlockCarriesStructuredNumbers() {
        Map<String, Object> metric = (Map<String, Object>) new WebhookAlertAssembler()
                .assemble(alarm("m"), event(), null).get("metric");
        assertThat(metric).containsEntry("id", "service.error.pct")
                .containsEntry("label", "错误率")
                .containsEntry("unit", "%")
                .containsEntry("value", 8.7)
                .containsEntry("threshold", 8.0)
                .containsEntry("comparator", "gt");
    }

    @Test
    void emptyFieldsAreOmittedInsteadOfNull() {
        EventRecord plain = new EventRecord(
                "E3", 1L, "r", "s", "threshold", "warning",
                EventRecord.STATUS_TRIGGER, "m", "s", false, Instant.now());
        Map<String, Object> payload = new WebhookAlertAssembler().assemble(alarm("m"), plain, null);
        assertThat(payload.get("metric")).isNull();
        // no event at all: event-only keys are absent, not null
        Map<String, Object> bare = new WebhookAlertAssembler().assemble(
                new Alarm("A9", 1L, "s", "threshold", "critical", "m",
                        Alarm.STATUS_OPEN, Instant.now(), null),
                null, null);
        assertThat(bare).doesNotContainKeys("eventId", "groupKey", "rule", "metric", "resolvedAt");
        assertThat(bare).doesNotContainValue(null);
        assertThat(bare.get("status")).isEqualTo("firing");
    }

    @Test
    void severityNormalizesToFourGrades() {
        assertThat(WebhookAlertAssembler.severity("critical")).isEqualTo("critical");
        assertThat(WebhookAlertAssembler.severity("warning")).isEqualTo("medium");
        assertThat(WebhookAlertAssembler.severity(null)).isEqualTo("medium");
        assertThat(WebhookAlertAssembler.severity("error")).isEqualTo("high");
        assertThat(WebhookAlertAssembler.severity("info")).isEqualTo("low");
        assertThat(WebhookAlertAssembler.severity("unknown-level")).isEqualTo("medium");
        // emitted values are fixed points
        for (String level : java.util.List.of("critical", "high", "medium", "low")) {
            assertThat(WebhookAlertAssembler.severity(level)).isEqualTo(level);
        }
        // assembled payloads carry the normalized grade, not the raw level
        Map<String, Object> payload = new WebhookAlertAssembler().assemble(
                new Alarm("A5", 1L, "s", "threshold", "warning", "m",
                        Alarm.STATUS_OPEN, Instant.now(), null), null, null);
        assertThat(payload.get("severity")).isEqualTo("medium");
    }

    @Test
    void statusMapsOnlyToFiringOrResolved() {
        assertThat(WebhookAlertAssembler.status("open")).isEqualTo("firing");
        assertThat(WebhookAlertAssembler.status("triggered")).isEqualTo("firing");
        assertThat(WebhookAlertAssembler.status("firing")).isEqualTo("firing");
        assertThat(WebhookAlertAssembler.status(null)).isEqualTo("firing");
        assertThat(WebhookAlertAssembler.status("resolved")).isEqualTo("resolved");
        assertThat(WebhookAlertAssembler.status("ok")).isEqualTo("resolved");
        assertThat(WebhookAlertAssembler.status("closed")).isEqualTo("resolved");
    }

    @Test
    void titleIsFirstLineTruncatedAndMultiLineMessageSurvivesJson() throws Exception {
        String multiLine = "第一行标题超长超长超长超长超长超长超长超长超长超长超长超长超长\n第二行带 \"引号\" 和 \\ 反斜杠";
        Map<String, Object> payload = new WebhookAlertAssembler().assemble(alarm(multiLine), event(), null);
        assertThat(payload.get("title")).isEqualTo("第一行标题超长超长超长超长超长超长超长超长超长超长超长超长超长");
        // Jackson serialization keeps the payload valid JSON with control characters escaped
        String json = new ObjectMapper().writeValueAsString(payload);
        Map<String, Object> parsed = new ObjectMapper().readValue(json, Map.class);
        assertThat(parsed.get("description")).isEqualTo(multiLine);
        assertThat(parsed.get("title")).isEqualTo(payload.get("title"));
    }
}
