package com.databuff.apm.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/** Application-file-only configuration for outbound alarm webhooks. */
@ConfigurationProperties(prefix = "apm.alarm.webhook")
public class AlarmWebhookProperties {

    private static final int DEFAULT_QUEUE_CAPACITY = 1000;
    private static final int DEFAULT_BATCH_SIZE = 50;
    private static final int MAX_QUEUE_CAPACITY = 100_000;
    private static final int MAX_BATCH_SIZE = 1000;
    private static final int DEFAULT_RETRY_TIMES = 2;
    private static final long DEFAULT_RETRY_BACKOFF_MILLIS = 1000L;

    private String url = "";
    private int queueCapacity = DEFAULT_QUEUE_CAPACITY;
    private int batchSize = DEFAULT_BATCH_SIZE;
    private int retryTimes = DEFAULT_RETRY_TIMES;
    private long retryBackoffMillis = DEFAULT_RETRY_BACKOFF_MILLIS;
    private Map<String, String> headers = new LinkedHashMap<>();

    public AlarmWebhookProperties() {
    }

    public AlarmWebhookProperties(
            String url,
            Map<String, String> headers,
            int queueCapacity,
            int batchSize,
            int retryTimes,
            long retryBackoffMillis) {
        this.url = url;
        this.headers = headers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);
        this.queueCapacity = queueCapacity;
        this.batchSize = batchSize;
        this.retryTimes = retryTimes;
        this.retryBackoffMillis = retryBackoffMillis;
    }

    public boolean enabled() {
        return url() != null && !url().isBlank();
    }

    public String url() {
        return url == null ? "" : url.trim();
    }

    public Map<String, String> headers() {
        Map<String, String> normalized = new LinkedHashMap<>();
        if (headers != null) {
            headers.forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
                    normalized.put(key.trim(), value.trim());
                }
            });
        }
        return Map.copyOf(normalized);
    }

    public int queueCapacity() {
        int normalized = queueCapacity > 0 ? queueCapacity : DEFAULT_QUEUE_CAPACITY;
        return Math.min(normalized, MAX_QUEUE_CAPACITY);
    }

    public int batchSize() {
        int normalized = batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
        return Math.min(Math.min(normalized, MAX_BATCH_SIZE), queueCapacity());
    }

    public int retryTimes() {
        return Math.max(0, Math.min(retryTimes, 10));
    }

    public long retryBackoffMillis() {
        long normalized = retryBackoffMillis >= 0
                ? retryBackoffMillis
                : DEFAULT_RETRY_BACKOFF_MILLIS;
        return Math.min(normalized, 60_000L);
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getRetryTimes() {
        return retryTimes;
    }

    public void setRetryTimes(int retryTimes) {
        this.retryTimes = retryTimes;
    }

    public long getRetryBackoffMillis() {
        return retryBackoffMillis;
    }

    public void setRetryBackoffMillis(long retryBackoffMillis) {
        this.retryBackoffMillis = retryBackoffMillis;
    }
}
