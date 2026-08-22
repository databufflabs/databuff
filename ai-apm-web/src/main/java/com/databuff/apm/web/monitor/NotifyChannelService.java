package com.databuff.apm.web.monitor;

import com.databuff.apm.web.config.AlarmWebhookProperties;
import com.databuff.apm.web.monitor.pipeline.EventRecord;
import com.databuff.apm.web.monitor.webhook.DefaultWebhookPayloadTemplate;
import com.databuff.apm.web.monitor.webhook.WebhookAlertAssembler;
import com.databuff.apm.web.monitor.webhook.WebhookAlertQueue;
import com.databuff.apm.web.monitor.webhook.WebhookDeliveryClient;
import com.databuff.apm.web.monitor.webhook.WebhookSendRecord;
import com.databuff.apm.web.monitor.webhook.WebhookSendRecordStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Application-file-only alarm webhook channel. Alarm threads only enqueue; a dedicated
 * bounded-queue consumer performs batching, HTTP I/O and retries.
 */
@Service
public class NotifyChannelService {

    private static final Logger log = LoggerFactory.getLogger(NotifyChannelService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int SEND_RECORD_CAPACITY = 200;
    private static final String SOURCE = "databuff-apm";

    private final AlarmWebhookProperties properties;
    private final MonitorRecordIdGenerator idGenerator;
    private final WebhookAlertAssembler assembler;
    private final DefaultWebhookPayloadTemplate template = new DefaultWebhookPayloadTemplate();
    private final WebhookDeliveryClient deliveryClient = new WebhookDeliveryClient();
    private final WebhookSendRecordStore sendRecords = new WebhookSendRecordStore(SEND_RECORD_CAPACITY);
    private final WebhookAlertQueue queue;

    public NotifyChannelService(
            AlarmWebhookProperties properties,
            MonitorRecordIdGenerator idGenerator,
            WebhookAlertAssembler assembler) {
        this.properties = properties;
        this.idGenerator = idGenerator;
        this.assembler = assembler;
        this.queue = properties.enabled()
                ? new WebhookAlertQueue(
                        properties.queueCapacity(), properties.batchSize(), this::deliverBatch)
                : null;
        if (queue != null) {
            log.info("Alarm webhook enabled: queueCapacity={}, batchSize={}, retryTimes={}",
                    properties.queueCapacity(), properties.batchSize(), properties.retryTimes());
        }
    }

    /** Non-blocking alarm-pipeline entry point. A full queue drops the new alert. */
    public void notifyAlert(Alarm alarm, EventRecord event) {
        if (alarm == null || queue == null) {
            return;
        }
        Map<String, Object> payload = assembler.assemble(alarm, event, SOURCE);
        if (!queue.offer(payload)) {
            long dropped = queue.droppedCount();
            if (dropped > 0 && (dropped == 1 || dropped % 100 == 0)) {
                log.warn("Alarm webhook queue is full; dropped {} alert(s)", dropped);
            }
        }
    }

    public void notifyAlert(Alarm alarm) {
        notifyAlert(alarm, null);
    }

    public List<WebhookSendRecord> recentSendRecords(int limit) {
        return sendRecords.list(Math.max(1, Math.min(limit, SEND_RECORD_CAPACITY)));
    }

    public int sendRecordCount() {
        return sendRecords.size();
    }

    public long droppedCount() {
        return queue == null ? 0L : queue.droppedCount();
    }

    public int queuedCount() {
        return queue == null ? 0 : queue.queuedCount();
    }

    public boolean awaitBatchIdle() throws InterruptedException {
        return queue == null || queue.awaitIdle(10_000L);
    }

    private void deliverBatch(List<Map<String, Object>> alerts) {
        if (alerts == null || alerts.isEmpty()) {
            return;
        }
        String batchId = idGenerator.nextBatchId();
        String json;
        try {
            json = MAPPER.writeValueAsString(template.render(batchId, alerts));
        } catch (Exception e) {
            log.warn("Alarm webhook payload serialization failed: {}", e.toString());
            sendRecords.append(WebhookSendRecord.of(batchId, properties.url(), template.id(),
                    alerts.size(), false, 0, -1, "payload serialization failed: " + e, 0));
            return;
        }

        Map<String, String> headers = new LinkedHashMap<>(properties.headers());

        int maxAttempts = 1 + properties.retryTimes();
        int attempts = 0;
        long startedAt = System.currentTimeMillis();
        WebhookDeliveryClient.DeliveryResult result = null;
        while (attempts < maxAttempts) {
            attempts++;
            result = deliveryClient.post(properties.url(), headers, json, batchId);
            if (result.success() || attempts >= maxAttempts) {
                break;
            }
            if (!sleepBeforeRetry(properties.retryBackoffMillis(), attempts)) {
                break;
            }
        }

        if (result == null) {
            result = new WebhookDeliveryClient.DeliveryResult(false, -1, "delivery interrupted", 0);
        }
        sendRecords.append(WebhookSendRecord.of(batchId, properties.url(), template.id(),
                alerts.size(), result.success(), attempts, result.statusCode(), result.error(),
                System.currentTimeMillis() - startedAt));
        if (!result.success()) {
            log.warn("Alarm webhook delivery failed after {} attempt(s): {}", attempts,
                    result.error() == null ? "HTTP " + result.statusCode() : result.error());
        }
    }

    private static boolean sleepBeforeRetry(long initialBackoffMillis, int attemptsMade) {
        if (initialBackoffMillis <= 0) {
            return true;
        }
        long multiplier = 1L << Math.min(Math.max(0, attemptsMade - 1), 10);
        try {
            Thread.sleep(Math.min(initialBackoffMillis * multiplier, 60_000L));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @PreDestroy
    public void close() {
        if (queue != null) {
            queue.close();
        }
    }
}
