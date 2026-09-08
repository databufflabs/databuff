package com.databuff.apm.web.portal;

import com.databuff.apm.web.config.AlarmWebhookProperties;
import com.databuff.apm.web.monitor.Alarm;
import com.databuff.apm.web.monitor.EventRule;
import com.databuff.apm.web.monitor.NotifyChannelService;
import com.databuff.apm.web.monitor.TestMonitorRecordIds;
import com.databuff.apm.web.monitor.pipeline.EventRecord;
import com.databuff.apm.web.monitor.webhook.WebhookAlertAssembler;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NotifyPortalControllerTest {

    @Test
    void recordsExposesFiringAndResolvedWebhookDeliveries() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new InetSocketAddress(0), 0);
        server.createContext("/hook", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        String hookUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";

        NotifyChannelService service = new NotifyChannelService(
                new AlarmWebhookProperties(hookUrl, Map.of("Authorization", "Bearer test"), 100, 1, 0, 0),
                TestMonitorRecordIds.create(),
                new WebhookAlertAssembler());
        NotifyPortalController controller = new NotifyPortalController(service);
        try {
            service.notifyAlert(
                    new Alarm("A1", 12L, "checkout", EventRule.WAY_THRESHOLD, "critical", "firing alert",
                            Alarm.STATUS_OPEN, Instant.now(), null),
                    triggerEvent("E1", EventRecord.STATUS_TRIGGER));
            service.notifyAlert(
                    new Alarm("A2", 12L, "checkout", EventRule.WAY_THRESHOLD, "critical", "resolved alert",
                            Alarm.STATUS_RESOLVED, Instant.now(), Instant.now()),
                    triggerEvent("E2", EventRecord.STATUS_RECOVER));
            assertThat(service.awaitBatchIdle()).isTrue();

            Map<String, Object> response = controller.records(Map.of("size", 10));
            Map<String, Object> data = (Map<String, Object>) response.get("data");
            List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("list");

            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(row -> row.get("primaryStatus"))
                    .containsExactly("resolved", "firing");
            assertThat(rows).extracting(row -> row.get("primaryFingerprint"))
                    .containsOnly("rule:12:service:checkout");
            assertThat(rows).extracting(row -> row.get("result")).containsOnly("success");
        } finally {
            service.close();
            server.stop(0);
        }
    }

    private static EventRecord triggerEvent(String id, String status) {
        return new EventRecord(id, 12L, "checkout error rate", "checkout", EventRule.WAY_THRESHOLD,
                "critical", status, status + " message", "checkout", false, Instant.now());
    }
}
