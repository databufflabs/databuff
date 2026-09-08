package com.databuff.apm.web.monitor.webhook;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

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
        long durationMillis,
        String primaryStatus,
        String primaryFingerprint,
        String primaryDescription) {

    public static WebhookSendRecord of(
            String batchId,
            String url,
            String templateId,
            int alertCount,
            boolean success,
            int attempts,
            int statusCode,
            String error,
            long durationMillis,
            List<Map<String, Object>> alerts) {
        Map<String, Object> first = alerts == null || alerts.isEmpty() ? Map.of() : alerts.get(0);
        return new WebhookSendRecord(
                batchId,
                url,
                templateId,
                alertCount,
                success,
                attempts,
                statusCode,
                error,
                DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                durationMillis,
                stringOrNull(first.get("status")),
                stringOrNull(first.get("fingerprint")),
                stringOrNull(first.get("description")));
    }

    private static String stringOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
