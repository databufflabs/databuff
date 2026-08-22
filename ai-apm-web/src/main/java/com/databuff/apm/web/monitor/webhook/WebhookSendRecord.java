package com.databuff.apm.web.monitor.webhook;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

/** One delivered webhook POST (final outcome after retries), newest first in the store. */
public record WebhookSendRecord(
        String batchId,
        String url,
        String templateId,
        int alertCount,
        boolean success,
        int attempts,
        int statusCode,
        String error,
        String sentAt,
        long durationMillis) {

    public static WebhookSendRecord of(
            String batchId,
            String url,
            String templateId,
            int alertCount,
            boolean success,
            int attempts,
            int statusCode,
            String error,
            long durationMillis) {
        return new WebhookSendRecord(batchId, url, templateId, alertCount, success, attempts,
                statusCode, error, DateTimeFormatter.ISO_INSTANT.format(Instant.now()), durationMillis);
    }
}
