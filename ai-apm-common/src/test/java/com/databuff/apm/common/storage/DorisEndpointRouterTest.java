package com.databuff.apm.common.storage;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DorisEndpointRouterTest {

    @Test
    void roundRobinsHealthyEndpoints() {
        DorisEndpointRouter router = new DorisEndpointRouter(List.of(
                new DorisHttpEndpoint("a", 8040),
                new DorisHttpEndpoint("b", 8040)));
        assertThat(router.pick().host()).isEqualTo("a");
        assertThat(router.pick().host()).isEqualTo("b");
        assertThat(router.pick().host()).isEqualTo("a");
    }

    @Test
    void failureRemovesFromHealthyUntilSuccess() {
        DorisHttpEndpoint a = new DorisHttpEndpoint("a", 8040);
        DorisHttpEndpoint b = new DorisHttpEndpoint("b", 8040);
        DorisEndpointRouter router = new DorisEndpointRouter(List.of(a, b));
        router.recordFailure(a);
        assertThat(router.healthySnapshot()).containsExactly(b);
        assertThat(router.pick()).isEqualTo(b);
        assertThat(router.pick()).isEqualTo(b);
        router.recordSuccess(a);
        assertThat(router.healthySnapshot()).containsExactlyInAnyOrder(a, b);
    }

    @Test
    void emptyHealthyFallsBackToAll() {
        DorisHttpEndpoint a = new DorisHttpEndpoint("a", 8040);
        DorisHttpEndpoint b = new DorisHttpEndpoint("b", 8040);
        DorisEndpointRouter router = new DorisEndpointRouter(List.of(a, b));
        router.recordFailure(a);
        router.recordFailure(b);
        assertThat(router.healthySnapshot()).isEmpty();
        assertThat(router.pick().host()).isIn("a", "b");
    }

    @Test
    void probeSkippedWhenAllHealthy() {
        DorisEndpointRouter router = new DorisEndpointRouter(List.of(
                new DorisHttpEndpoint("127.0.0.1", 1)));
        HttpClient client = HttpClient.newHttpClient();
        router.maybeProbeUnhealthy(client, null);
        assertThat(router.healthySnapshot()).hasSize(1);
    }
}
