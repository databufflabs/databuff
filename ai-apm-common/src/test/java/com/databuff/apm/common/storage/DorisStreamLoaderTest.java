package com.databuff.apm.common.storage;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class DorisStreamLoaderTest {

    private HttpServer server;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/api/databuff/metric_service/_stream_load", exchange -> {
            byte[] ok = "{\"Status\": \"Success\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(ok);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void loadsJsonLinesViaHttp() throws IOException {
        DorisStreamLoader loader = new DorisStreamLoader(
                new DorisConnectionConfig("127.0.0.1", 9030, port), "root", "");
        DorisStreamLoader.StreamLoadResult result = loader.loadJsonLines(
                "databuff", "metric_service", "{\"cnt\":1}".getBytes(StandardCharsets.UTF_8));
        assertThat(result.success()).isTrue();
        assertThat(result.httpStatus()).isEqualTo(200);
    }

    @Test
    void reportsFailureWhenHttpNotSuccess() throws Exception {
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/api/databuff/metric_service/_stream_load", exchange -> {
            byte[] body = "{\"Status\": \"Fail\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        DorisStreamLoader loader = new DorisStreamLoader(
                new DorisConnectionConfig("127.0.0.1", 9030, port), "root", "");
        DorisStreamLoader.StreamLoadResult result = loader.loadJsonLines(
                "databuff", "metric_service", "{}".getBytes());
        assertThat(result.success()).isFalse();
    }

    @Test
    void loadsViaDirectBeWhenBeHostConfigured() throws Exception {
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/api/databuff/metric_service/_stream_load", exchange -> {
            byte[] ok = "{\"Status\": \"Success\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(ok);
            }
        });
        server.start();
        DorisStreamLoader loader = new DorisStreamLoader(
                new DorisConnectionConfig("127.0.0.1", 9030, 8030, "127.0.0.1", port), "root", "");
        DorisStreamLoader.StreamLoadResult result = loader.loadJsonLines(
                "databuff", "metric_service", "{\"cnt\":1}".getBytes(StandardCharsets.UTF_8));
        assertThat(result.success()).isTrue();
    }

    @Test
    void rewritesInternalBeRedirectToPublicHost() throws Exception {
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/api/databuff/metric_service/_stream_load", exchange -> {
            exchange.getResponseHeaders().add("Location",
                    "http://172.20.80.3:" + port + "/api/databuff/metric_service/_stream_load_be");
            exchange.sendResponseHeaders(307, -1);
        });
        server.createContext("/api/databuff/metric_service/_stream_load_be", exchange -> {
            byte[] ok = "{\"Status\": \"Success\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(ok);
            }
        });
        server.start();
        DorisStreamLoader loader = new DorisStreamLoader(
                new DorisConnectionConfig("127.0.0.1", 9030, port, null, port), "root", "");
        DorisStreamLoader.StreamLoadResult result = loader.loadJsonLines(
                "databuff", "metric_service", "{\"cnt\":1}".getBytes(StandardCharsets.UTF_8));
        assertThat(result.success()).isTrue();
    }

    @Test
    void rewritesInternalBeRedirectUsingFeHostWhenBeHostUnset() throws Exception {
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/api/databuff/metric_service/_stream_load", exchange -> {
            exchange.getResponseHeaders().add("Location",
                    "http://172.20.80.3:" + port + "/api/databuff/metric_service/_stream_load_be");
            exchange.sendResponseHeaders(307, -1);
        });
        server.createContext("/api/databuff/metric_service/_stream_load_be", exchange -> {
            byte[] ok = "{\"Status\": \"Success\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(ok);
            }
        });
        server.start();
        DorisStreamLoader loader = new DorisStreamLoader(
                new DorisConnectionConfig("127.0.0.1", 9030, port, null, port), "root", "");
        DorisStreamLoader.StreamLoadResult result = loader.loadJsonLines(
                "databuff", "metric_service", "{\"cnt\":1}".getBytes(StandardCharsets.UTF_8));
        assertThat(result.success()).isTrue();
    }

    @Test
    void followsRedirectAndPreservesAuthorization() throws Exception {
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        AtomicBoolean authSeen = new AtomicBoolean(false);
        server.createContext("/api/databuff/metric_service/_stream_load", exchange -> {
            exchange.getResponseHeaders().add("Location",
                    "http://127.0.0.1:" + port + "/api/databuff/metric_service/_stream_load_be");
            exchange.sendResponseHeaders(307, -1);
        });
        server.createContext("/api/databuff/metric_service/_stream_load_be", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth != null && auth.startsWith("Basic ")) {
                authSeen.set(true);
            }
            byte[] ok = "{\"Status\": \"Success\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(ok);
            }
        });
        server.start();
        DorisStreamLoader loader = new DorisStreamLoader(
                new DorisConnectionConfig("127.0.0.1", 9030, port, null, port), "root", "secret");
        DorisStreamLoader.StreamLoadResult result = loader.loadJsonLines(
                "databuff", "metric_service", "{\"cnt\":1}".getBytes(StandardCharsets.UTF_8));
        assertThat(result.success()).isTrue();
        assertThat(authSeen).isTrue();
    }

    @Test
    void directBeFailsOverToSecondEndpoint() throws Exception {
        server.stop(0);
        HttpServer bad = HttpServer.create(new InetSocketAddress(0), 0);
        int badPort = bad.getAddress().getPort();
        bad.createContext("/api/databuff/metric_service/_stream_load", exchange -> {
            byte[] body = "{\"Status\": \"Fail\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        bad.createContext("/api/health", exchange -> {
            exchange.sendResponseHeaders(500, -1);
        });
        bad.start();

        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/api/databuff/metric_service/_stream_load", exchange -> {
            byte[] ok = "{\"Status\": \"Success\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(ok);
            }
        });
        server.createContext("/api/health", exchange -> {
            byte[] ok = "{\"status\":\"OK\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(ok);
            }
        });
        server.start();

        try {
            String beList = "127.0.0.1:" + badPort + ",127.0.0.1:" + port;
            DorisStreamLoader loader = new DorisStreamLoader(
                    new DorisConnectionConfig("127.0.0.1", 9030, 8030, beList, 8040), "root", "");
            DorisStreamLoader.StreamLoadResult result = loader.loadJsonLines(
                    "databuff", "metric_service", "{\"cnt\":1}".getBytes(StandardCharsets.UTF_8));
            assertThat(result.success()).isTrue();
            assertThat(loader.beRouterForTest().healthySnapshot())
                    .extracting(DorisHttpEndpoint::port)
                    .contains(port)
                    .doesNotContain(badPort);
        } finally {
            bad.stop(0);
        }
    }
}
