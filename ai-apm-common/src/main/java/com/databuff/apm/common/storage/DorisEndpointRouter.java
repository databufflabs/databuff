package com.databuff.apm.common.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Round-robin Doris HTTP endpoints with health-set failover (aligned with ultra
 * {@code OlapWriterHandler}: pick from healthy list, drop on failure, probe {@code /api/health} to restore).
 */
final class DorisEndpointRouter {

    private static final Logger log = LoggerFactory.getLogger(DorisEndpointRouter.class);
    private static final long HEALTH_CHECK_INTERVAL_MS = 60_000L;
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(3);

    private final List<DorisHttpEndpoint> all;
    private final List<DorisHttpEndpoint> healthy = new ArrayList<>();
    private final Object lock = new Object();
    private final AtomicInteger pickCursor = new AtomicInteger();
    private final AtomicInteger probeCursor = new AtomicInteger();
    private final ConcurrentHashMap<String, Long> lastProbeMs = new ConcurrentHashMap<>();

    DorisEndpointRouter(List<DorisHttpEndpoint> endpoints) {
        this.all = List.copyOf(Objects.requireNonNull(endpoints));
        if (this.all.isEmpty()) {
            throw new IllegalArgumentException("endpoint list must not be empty");
        }
        synchronized (lock) {
            this.healthy.addAll(this.all);
        }
    }

    List<DorisHttpEndpoint> all() {
        return all;
    }

    /** Snapshot of currently healthy endpoints (for tests). */
    List<DorisHttpEndpoint> healthySnapshot() {
        synchronized (lock) {
            return List.copyOf(healthy);
        }
    }

    DorisHttpEndpoint pick() {
        synchronized (lock) {
            if (!healthy.isEmpty()) {
                int slot = Math.floorMod(pickCursor.getAndIncrement(), healthy.size());
                return healthy.get(slot);
            }
            int slot = Math.floorMod(pickCursor.getAndIncrement(), all.size());
            return all.get(slot);
        }
    }

    void recordSuccess(DorisHttpEndpoint endpoint) {
        Objects.requireNonNull(endpoint);
        synchronized (lock) {
            if (!healthy.contains(endpoint)) {
                healthy.add(endpoint);
                log.info("Doris endpoint restored to healthy set: {}", endpoint.authority());
            }
        }
    }

    void recordFailure(DorisHttpEndpoint endpoint) {
        Objects.requireNonNull(endpoint);
        synchronized (lock) {
            healthy.remove(endpoint);
        }
        log.warn("Doris endpoint removed from healthy set after failure: {}", endpoint.authority());
    }

    /**
     * Opportunistically probe one configured-but-unhealthy endpoint via {@code GET /api/health}.
     * No-op when every configured endpoint is already healthy.
     */
    void maybeProbeUnhealthy(HttpClient httpClient, String authorizationHeader) {
        Objects.requireNonNull(httpClient);
        DorisHttpEndpoint toProbe;
        boolean skipThrottle;
        synchronized (lock) {
            skipThrottle = healthy.isEmpty();
            List<DorisHttpEndpoint> surplus = new ArrayList<>(all);
            surplus.removeAll(healthy);
            if (surplus.isEmpty()) {
                return;
            }
            int idx = Math.floorMod(probeCursor.getAndIncrement(), surplus.size());
            toProbe = surplus.get(idx);
        }
        long now = System.currentTimeMillis();
        if (!skipThrottle) {
            Long last = lastProbeMs.get(toProbe.authority());
            if (last != null && now - last < HEALTH_CHECK_INTERVAL_MS) {
                return;
            }
        }
        lastProbeMs.put(toProbe.authority(), now);
        if (isAlive(httpClient, toProbe, authorizationHeader)) {
            recordSuccess(toProbe);
        }
    }

    static boolean isAlive(HttpClient httpClient, DorisHttpEndpoint endpoint, String authorizationHeader) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint.httpBaseUrl() + "/api/health"))
                .timeout(HEALTH_TIMEOUT)
                .GET();
        if (authorizationHeader != null && !authorizationHeader.isBlank()) {
            builder.header("Authorization", authorizationHeader);
        }
        try {
            HttpResponse<Void> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            int code = response.statusCode();
            return code >= 200 && code < 300;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
