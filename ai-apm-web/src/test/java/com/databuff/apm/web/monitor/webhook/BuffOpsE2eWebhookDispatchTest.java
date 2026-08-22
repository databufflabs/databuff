package com.databuff.apm.web.monitor.webhook;

import com.databuff.apm.web.config.AlarmWebhookProperties;
import com.databuff.apm.web.monitor.Alarm;
import com.databuff.apm.web.monitor.EventRule;
import com.databuff.apm.web.monitor.NotifyChannelService;
import com.databuff.apm.web.monitor.TestMonitorRecordIds;
import com.databuff.apm.web.monitor.pipeline.AlarmResponseExecutor;
import com.databuff.apm.web.monitor.pipeline.EventRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Local end-to-end integration against a REALLY RUNNING BuffOps instance
 * ({@code POST /api/open/v1/ingest/{receiverKey}} with {@code X-BuffOps-Token}).
 * Real JVM, real JDK HttpClient, real batcher/template/retry — only the alert
 * source is constructed (no Doris needed locally).
 * <p>
 * Uses the single built-in {@code default} template: the unified payload already speaks
 * the standard-event vocabulary (title/severity/firing/fingerprint; batch envelope
 * {@code {"batchId","count","events":[...]}} uses buffops's native batch key), so no
 * dedicated buffops template is needed on the DataBuff side.
 * <p>
 * Gated: run only when {@code BUFFOPS_E2E=true}; endpoint/token via env:
 * <pre>
 * BUFFOPS_E2E=true \
 * BUFFOPS_E2E_BASE=http://127.0.0.1:19100 \
 * BUFFOPS_E2E_RECEIVER=conn-databuff-e2e \
 * BUFFOPS_E2E_TOKEN=bo_... \
 * mvn -pl ai-apm-web test -Dtest=BuffOpsE2eWebhookDispatchTest
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "BUFFOPS_E2E", matches = "true")
class BuffOpsE2eWebhookDispatchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private static final String BASE = env("BUFFOPS_E2E_BASE", "http://127.0.0.1:19100");
    private static final String RECEIVER = env("BUFFOPS_E2E_RECEIVER", "conn-databuff-e2e");
    private static final String INGEST_TOKEN = System.getenv("BUFFOPS_E2E_TOKEN");
    private static final String ADMIN_PASSWORD = env("BUFFOPS_E2E_ADMIN_PASSWORD", "Databuff@123");
    private static final String INGEST_URL = BASE + "/api/open/v1/ingest/" + RECEIVER;

    private static NotifyChannelService service;
    private static AlarmResponseExecutor executor;
    private static String adminJwt;
    /** Per-run suffix so repeated runs never match stale events from earlier runs. */
    private static final String RUN = env("BUFFOPS_E2E_RUN",
            "R" + Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toEpochMilli());

    @BeforeAll
    static void bootRealNotifyChain() throws Exception {
        assertThat(INGEST_TOKEN).as("BUFFOPS_E2E_TOKEN (system token with api-ingest-event)").isNotBlank();
        service = new NotifyChannelService(
                new AlarmWebhookProperties(
                        INGEST_URL, Map.of("X-BuffOps-Token", INGEST_TOKEN), 100, 50, 2, 200),
                TestMonitorRecordIds.create(),
                new WebhookAlertAssembler());
        executor = new AlarmResponseExecutor(service);
        adminJwt = login();
    }

    @AfterAll
    static void closeNotifyChain() {
        if (service != null) {
            service.close();
        }
    }

    /** Single delivery: window 0 -> immediate POST, same batch envelope with one event. */
    @Test
    void singleAlertIsDeliveredToBuffOps() throws Exception {
        executor.dispatch(
                alarm("E2E-A-SINGLE-1-" + RUN, "warning"),
                event("E2E-E-SINGLE-1-" + RUN, 9901L, "E2E-批量单发-服务A", "E2E-SINGLE-Svc-" + RUN));
        assertThat(service.awaitBatchIdle()).isTrue();

        WebhookSendRecord record = service.recentSendRecords(10).get(0);
        assertThat(record.success()).as("single delivery should succeed: %s", record).isTrue();
        assertThat(record.statusCode()).isEqualTo(202);
        assertThat(record.batchId()).as("even a single event POSTs the batch envelope").isNotNull();
        assertThat(record.alertCount()).isEqualTo(1);
        assertThat(record.templateId()).isEqualTo("default");

        JsonNode event = awaitBuffOpsEvent("rule:9901:service:E2E-SINGLE-Svc");
        assertThat(event.path("status").asText()).isEqualTo("firing");
        assertThat(event.path("severity").asText()).as("warning -> medium").isEqualTo("medium");
        assertThat(event.path("source").asText()).isEqualTo("Standard Event");
        assertThat(event.path("title").asText()).contains("E2E");
    }

    /** Batch delivery: 2 alerts inside one window -> ONE POST with {"events":[...]}. */
    @Test
    void twoAlertsInWindowArriveAsOneBatchPost() throws Exception {
        executor.dispatch(alarm("E2E-A-BATCH-1-" + RUN, "critical"), event("E2E-E-BATCH-1-" + RUN, 9902L, "E2E-批量-服务A", "E2E-BATCH-SvcA-" + RUN));
        executor.dispatch(alarm("E2E-A-BATCH-2-" + RUN, "warning"), event("E2E-E-BATCH-2-" + RUN, 9902L, "E2E-批量-服务B", "E2E-BATCH-SvcB-" + RUN));
        assertThat(service.awaitBatchIdle()).isTrue();

        WebhookSendRecord record = service.recentSendRecords(10).get(0);
        assertThat(record.success()).as("batch delivery should succeed: %s", record).isTrue();
        assertThat(record.statusCode()).isEqualTo(202);
        assertThat(record.alertCount()).isEqualTo(2);
        assertThat(record.batchId()).as("batch envelope carries a batchId").isNotNull();

        assertThat(awaitBuffOpsEvent("rule:9902:service:E2E-BATCH-SvcA").path("severity").asText())
                .as("critical stays critical").isEqualTo("critical");
        assertThat(awaitBuffOpsEvent("rule:9902:service:E2E-BATCH-SvcB").path("severity").asText())
                .as("warning maps to medium").isEqualTo("medium");
    }

    /** Convergence dedup input: 2 events with the SAME fingerprint arrive in one batch. */
    @Test
    void twoEventsWithSameFingerprintLandForConvergenceDedup() throws Exception {
        executor.dispatch(alarm("E2E-A-DEDUP-1-" + RUN, "high"), event("E2E-E-DEDUP-1-" + RUN, 9903L, "E2E-去重-同一指纹", "E2E-DEDUP-Fp-" + RUN));
        executor.dispatch(alarm("E2E-A-DEDUP-2-" + RUN, "high"), event("E2E-E-DEDUP-2-" + RUN, 9903L, "E2E-去重-同一指纹", "E2E-DEDUP-Fp-" + RUN));
        assertThat(service.awaitBatchIdle()).isTrue();

        WebhookSendRecord record = service.recentSendRecords(10).get(0);
        assertThat(record.success()).isTrue();
        assertThat(record.statusCode()).isEqualTo(202);
        assertThat(record.alertCount()).isEqualTo(2);

        Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500))
                .until(() -> buffOpsEventsByFingerprint("rule:9903:service:E2E-DEDUP-Fp"),
                        list -> list.size() >= 2);
        List<JsonNode> events = buffOpsEventsByFingerprint("rule:9903:service:E2E-DEDUP-Fp");
        assertThat(events).as("same-fingerprint pair should land as 2 events (dedup happens at convergence)")
                .hasSizeGreaterThanOrEqualTo(2);
    }

    // ---------------------------------------------------------------- helpers

    private static Alarm alarm(String id, String level) {
        return new Alarm(id, 1L, "databuff-e2e-service", EventRule.WAY_THRESHOLD, level,
                "E2E 联调告警 " + id + "：模拟错误率 8.7% 超过阈值 8%",
                Alarm.STATUS_OPEN, Instant.now(), null);
    }

    private static EventRecord event(String id, long ruleId, String ruleName, String groupKey) {
        return new EventRecord(id, ruleId, ruleName, "databuff-e2e-service", "threshold", "warning",
                EventRecord.STATUS_TRIGGER,
                "E2E 联调事件 " + id + "：模拟错误率 8.7% 超过阈值 8%",
                groupKey, false, Instant.now(),
                "service.error.pct", "错误率", "%", 8.7, 8.0, "gt");
    }

    private static String login() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + "/api/v1/auth/login"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"admin\",\"password\":\"" + ADMIN_PASSWORD + "\"}"))
                .build();
        return MAPPER.readTree(HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body())
                .path("token").asText();
    }

    /** Polls BuffOps /api/v1/events until one event with the fingerprint is READY-visible. */
    private static JsonNode awaitBuffOpsEvent(String fingerprint) {
        return Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500))
                .until(() -> buffOpsEventsByFingerprint(fingerprint),
                        list -> !list.isEmpty())
                .get(0);
    }

    private static List<JsonNode> buffOpsEventsByFingerprint(String fingerprint) {
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(BASE + "/api/v1/events?pageNum=1&pageSize=200"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + adminJwt)
                    .GET()
                    .build();
            JsonNode rows = MAPPER.readTree(HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body());
            List<JsonNode> hits = new java.util.ArrayList<>();
            for (JsonNode row : rows) {
                if (fingerprint.equals(row.path("fingerprint").asText())) {
                    hits.add(row);
                }
            }
            return hits;
        } catch (Exception e) {
            throw new IllegalStateException("buffops events query failed: " + e, e);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
