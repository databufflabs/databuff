package com.databuff.apm.web.monitor;

import com.databuff.apm.web.config.AlarmWebhookProperties;
import com.databuff.apm.web.monitor.pipeline.EventRecord;
import com.databuff.apm.web.monitor.webhook.WebhookAlertAssembler;
import com.databuff.apm.web.monitor.webhook.WebhookSendRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class NotifyChannelServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void blankUrlDisablesWebhookWithoutStartingDelivery() throws Exception {
        NotifyChannelService service = service(new AlarmWebhookProperties());
        try {
            service.notifyAlert(alarm("A0"), event("E0"));
            assertThat(service.awaitBatchIdle()).isTrue();
            assertThat(service.sendRecordCount()).isZero();
            assertThat(service.queuedCount()).isZero();
        } finally {
            service.close();
        }
    }

    @Test
    void configuredUrlSendsBatchEnvelopeAndCustomHeaders() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> token = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> deliveryId = new AtomicReference<>();
        com.sun.net.httpserver.HttpServer server = server(exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes()));
            token.set(exchange.getRequestHeaders().getFirst("X-BuffOps-Token"));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            deliveryId.set(exchange.getRequestHeaders().getFirst("X-BuffOps-Delivery-Id"));
            exchange.sendResponseHeaders(204, -1);
        });
        AlarmWebhookProperties properties = properties(url(server), 2);
        properties.setHeaders(Map.of(
                "X-BuffOps-Token", "bo-test",
                "Authorization", "Bearer generic-test"));
        NotifyChannelService service = service(properties);
        try {
            service.notifyAlert(alarm("A1"), event("E1"));
            assertThat(service.awaitBatchIdle()).isTrue();

            Map<String, Object> payload = MAPPER.readValue(body.get(), Map.class);
            assertThat(payload).containsEntry("count", 1);
            assertThat(String.valueOf(payload.get("batchId"))).startsWith("B");
            List<Map<String, Object>> events = (List<Map<String, Object>>) payload.get("events");
            assertThat(events.get(0)).containsEntry("alarmId", "A1")
                    .containsEntry("eventId", "E1")
                    .containsEntry("status", "firing");
            assertThat(token.get()).isEqualTo("bo-test");
            assertThat(authorization.get()).isEqualTo("Bearer generic-test");
            assertThat(deliveryId.get()).isEqualTo(payload.get("batchId"));
            assertThat(service.recentSendRecords(1).get(0).success()).isTrue();
        } finally {
            service.close();
            server.stop(0);
        }
    }

    @Test
    void defaultRetriesTwiceAndKeepsDeliveryIdentity() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        List<String> bodies = new CopyOnWriteArrayList<>();
        List<String> deliveryIds = new CopyOnWriteArrayList<>();
        com.sun.net.httpserver.HttpServer server = server(exchange -> {
            hits.incrementAndGet();
            bodies.add(new String(exchange.getRequestBody().readAllBytes()));
            deliveryIds.add(exchange.getRequestHeaders().getFirst("X-BuffOps-Delivery-Id"));
            exchange.sendResponseHeaders(500, -1);
        });
        AlarmWebhookProperties properties = new AlarmWebhookProperties();
        properties.setUrl(url(server));
        properties.setRetryBackoffMillis(0);
        NotifyChannelService service = service(properties);
        try {
            service.notifyAlert(alarm("A2"), event("E2"));
            assertThat(service.awaitBatchIdle()).isTrue();

            assertThat(hits.get()).isEqualTo(3);
            assertThat(bodies).hasSize(3).allMatch(bodies.get(0)::equals);
            assertThat(deliveryIds).hasSize(3).containsOnly(deliveryIds.get(0));
            WebhookSendRecord record = service.recentSendRecords(1).get(0);
            assertThat(record.success()).isFalse();
            assertThat(record.attempts()).isEqualTo(3);
        } finally {
            service.close();
            server.stop(0);
        }
    }

    @Test
    void retryCountIsConfigurable() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        com.sun.net.httpserver.HttpServer server = server(exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
        });
        NotifyChannelService service = service(properties(url(server), 0));
        try {
            service.notifyAlert(alarm("A3"), event("E3"));
            assertThat(service.awaitBatchIdle()).isTrue();
            assertThat(hits.get()).isEqualTo(1);
            assertThat(service.recentSendRecords(1).get(0).attempts()).isEqualTo(1);
        } finally {
            service.close();
            server.stop(0);
        }
    }

    private static NotifyChannelService service(AlarmWebhookProperties properties) {
        return new NotifyChannelService(
                properties, TestMonitorRecordIds.create(), new WebhookAlertAssembler());
    }

    private static AlarmWebhookProperties properties(String url, int retries) {
        return new AlarmWebhookProperties(url, Map.of(), 100, 50, retries, 0);
    }

    private static Alarm alarm(String id) {
        return new Alarm(id, 1L, "demo", EventRule.WAY_THRESHOLD, "warning", "demo alarm",
                Alarm.STATUS_OPEN, Instant.now(), null);
    }

    private static EventRecord event(String id) {
        return new EventRecord(id, 12L, "demo rule", "demo", EventRule.WAY_THRESHOLD,
                "warning", EventRecord.STATUS_TRIGGER, "demo alarm", "demo", false,
                Instant.now(), "service.error.pct", "错误率", "%", 5.2, 5.0, "gt");
    }

    private static com.sun.net.httpserver.HttpServer server(
            com.sun.net.httpserver.HttpHandler handler) throws Exception {
        com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/hook", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private static String url(com.sun.net.httpserver.HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }
}
