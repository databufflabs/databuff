package com.databuff.apm.web.monitor.webhook;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Shared HTTP client for webhook delivery (one client, reused across sends). */
public class WebhookDeliveryClient {

    public static final String DELIVERY_ID_HEADER = "X-BuffOps-Delivery-Id";

    public record DeliveryResult(boolean success, int statusCode, String error, long durationMillis) {
    }

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public DeliveryResult post(
            String url,
            Map<String, String> headers,
            String jsonBody,
            String deliveryId) {
        long start = System.currentTimeMillis();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    if (header.getKey() != null && !header.getKey().isBlank()
                            && header.getValue() != null && !header.getValue().isBlank()
                            && !DELIVERY_ID_HEADER.equalsIgnoreCase(header.getKey().trim())) {
                        builder.setHeader(header.getKey().trim(), header.getValue().trim());
                    }
                }
            }
            if (deliveryId != null && !deliveryId.isBlank()) {
                builder.header(DELIVERY_ID_HEADER, deliveryId.trim());
            }
            HttpResponse<Void> response =
                    client.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            return new DeliveryResult(status >= 200 && status < 300, status, null,
                    System.currentTimeMillis() - start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new DeliveryResult(false, -1, "delivery interrupted", System.currentTimeMillis() - start);
        } catch (Exception e) {
            return new DeliveryResult(false, -1, e.toString(), System.currentTimeMillis() - start);
        }
    }
}
