package com.databuff.apm.common.storage;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class DorisStreamLoadSinkCoverageTest {

    private static final Executor NOOP_EXECUTOR = command -> {
    };

    private static HttpServer server(String table, String body, AtomicInteger calls) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/databuff/" + table + "/_stream_load", exchange -> {
            if (calls != null) {
                calls.incrementAndGet();
            }
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });
        server.start();
        return server;
    }

    private static DorisStreamLoader loaderOn(int port) {
        return new DorisStreamLoader(new DorisConnectionConfig("127.0.0.1", 9030, port), "root", "");
    }

    @Test
    void publicConstructorsExposeGetters() {
        DorisStreamLoadSink s4 = new DorisStreamLoadSink(
                new DorisBatchWriter(), loaderOn(1), "databuff", "trace_dc_span");
        DorisStreamLoadSink s5 = new DorisStreamLoadSink(
                new DorisBatchWriter(), loaderOn(1), "databuff", "log_dc_record",
                DorisStreamLoadSink.DEFAULT_MAX_CONSECUTIVE_FAILURES);
        DorisStreamLoadSink s6 = new DorisStreamLoadSink(
                new DorisBatchWriter(), loaderOn(1), "databuff", "metric_service",
                DorisStreamLoadSink.DEFAULT_MAX_CONSECUTIVE_FAILURES, 2);

        assertThat(s4.table()).isEqualTo("trace_dc_span");
        assertThat(s4.database()).isEqualTo("databuff");
        assertThat(s4.flushConcurrency()).isEqualTo(1);
        assertThat(s5.flushConcurrency()).isEqualTo(1);
        assertThat(s6.flushConcurrency()).isEqualTo(2);
        assertThat(s4.inFlight()).isZero();
        assertThat(s4.hasReady()).isFalse();
        assertThat(s4.pendingCount()).isZero();
        assertThat(s4.readyBatchCount()).isZero();
        assertThat(s4.maxReadyBatches()).isEqualTo(DorisBatchWriter.DEFAULT_MAX_READY_BATCHES);
    }

    @Test
    void joinJsonLines_emptyYieldsEmptyBytes() throws Exception {
        assertThat(DorisStreamLoadSink.joinJsonLines(List.of())).isEmpty();
    }

    @Test
    void scheduleFlushReady_stopsAtConcurrencyLimit() {
        DorisBatchWriter writer = new DorisBatchWriter(1);
        DorisStreamLoadSink sink = new DorisStreamLoadSink(
                writer, loaderOn(1), "databuff", "log_dc_record", 3, NOOP_EXECUTOR, 1);
        writer.offer("{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        writer.offer("{\"a\":2}".getBytes(StandardCharsets.UTF_8));
        writer.offer("{\"a\":3}".getBytes(StandardCharsets.UTF_8));
        assertThat(sink.readyBatchCount()).isEqualTo(3);
        assertThat(sink.inFlight()).isEqualTo(1);
    }

    @Test
    void scheduleFlushReady_swallowsRejectedExecution() {
        Executor rejecting = command -> {
            throw new RejectedExecutionException("shutting down");
        };
        DorisBatchWriter writer = new DorisBatchWriter(1);
        DorisStreamLoadSink sink = new DorisStreamLoadSink(
                writer, loaderOn(1), "databuff", "log_dc_record", 3, rejecting, 1);
        writer.offer("{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        assertThat(sink.readyBatchCount()).isEqualTo(1);
        assertThat(sink.inFlight()).isZero();
    }

    @Test
    void asyncFlushSharedExecutor_drainsMultipleReadyBatches() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server("trace_dc_span", "{\"Status\": \"Success\"}", calls);
        try {
            DorisBatchWriter writer = new DorisBatchWriter(1);
            DorisStreamLoadSink sink = new DorisStreamLoadSink(
                    writer, loaderOn(server.getAddress().getPort()), "databuff", "trace_dc_span",
                    DorisStreamLoadSink.DEFAULT_MAX_CONSECUTIVE_FAILURES,
                    DorisStreamLoadSink.DEFAULT_FLUSH_CONCURRENCY);
            for (int i = 0; i < 3; i++) {
                writer.offer(("{\"i\":" + i + "}").getBytes(StandardCharsets.UTF_8));
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (calls.get() < 3 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(calls.get()).isEqualTo(3);
            assertThat(sink.pendingCount()).isZero();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void asyncFlushConcurrencyOne_customExecutor() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server("trace_dc_span", "{\"Status\": \"Success\"}", calls);
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            DorisBatchWriter writer = new DorisBatchWriter(1);
            DorisStreamLoadSink sink = new DorisStreamLoadSink(
                    writer, loaderOn(server.getAddress().getPort()), "databuff", "trace_dc_span", 3, exec, 1);
            for (int i = 0; i < 3; i++) {
                writer.offer(("{\"i\":" + i + "}").getBytes(StandardCharsets.UTF_8));
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (calls.get() < 3 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(calls.get()).isEqualTo(3);
            assertThat(sink.inFlight()).isZero();
            assertThat(sink.pendingCount()).isZero();
        } finally {
            exec.shutdownNow();
            server.stop(0);
        }
    }

    @Test
    void metricPlatformSelfMetricSkipsCounterInstrumentation() throws Exception {
        HttpServer server = server("metric_platform", "{\"Status\": \"Success\"}", null);
        try {
            DorisBatchWriter writer = new DorisBatchWriter(1);
            DorisStreamLoadSink sink = new DorisStreamLoadSink(
                    writer, loaderOn(server.getAddress().getPort()), "databuff", "metric_platform", 3, NOOP_EXECUTOR);
            writer.offer("{\"metric\":\"doris.up\"}".getBytes(StandardCharsets.UTF_8));
            assertThat(sink.flushAll()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void metaServiceFailureAddsSchemaHint() throws Exception {
        HttpServer server = server("meta_service", "{\"Status\": \"Fail\", \"Message\": \"schema\"}", null);
        try {
            DorisBatchWriter writer = new DorisBatchWriter(1);
            DorisStreamLoadSink sink = new DorisStreamLoadSink(
                    writer, loaderOn(server.getAddress().getPort()), "databuff", "meta_service", 3, NOOP_EXECUTOR);
            writer.offer("{\"k\":\"v\"}".getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(sink::flushAll)
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("meta_service schema is outdated");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sampleRow_truncatesOverlongRow() throws Exception {
        HttpServer server = server("log_dc_record", "{\"Status\": \"Fail\"}", null);
        try {
            DorisBatchWriter writer = new DorisBatchWriter(1);
            DorisStreamLoadSink sink = new DorisStreamLoadSink(
                    writer, loaderOn(server.getAddress().getPort()), "databuff", "log_dc_record", 3, NOOP_EXECUTOR);
            String big = "{\"body\":\"" + "x".repeat(600) + "\"}";
            writer.offer(big.getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(sink::flushAll)
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("sampleRow=")
                    .hasMessageContaining("...");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sampleRow_fallsBackWhenRowEncodingFails() throws Exception {
        DorisJsonRow broken = mock(DorisJsonRow.class);
        doThrow(new IOException("encode boom"))
                .when(broken).writeTo(org.mockito.ArgumentMatchers.any(OutputStream.class));
        DorisBatchWriter writer = new DorisBatchWriter(1);
        DorisStreamLoadSink sink = new DorisStreamLoadSink(
                writer, loaderOn(1), "databuff", "log_dc_record", 3, NOOP_EXECUTOR);
        writer.offer(broken);
        assertThatThrownBy(sink::flushAll)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("encode boom");
        assertThatThrownBy(sink::flushAll).isInstanceOf(IOException.class);
        assertThat(sink.flushAll()).isZero();
        assertThat(sink.consecutiveFailures()).isZero();
    }

    @Test
    void metricJvmSuccessLogsPipelineStreamLoad() throws Exception {
        HttpServer server = server("metric_jvm", "{\"Status\": \"Success\"}", null);
        try {
            DorisBatchWriter writer = new DorisBatchWriter(1);
            DorisStreamLoadSink sink = new DorisStreamLoadSink(
                    writer, loaderOn(server.getAddress().getPort()), "databuff", "metric_jvm", 3, NOOP_EXECUTOR);
            writer.offer("{\"a\":1}".getBytes(StandardCharsets.UTF_8));
            assertThat(sink.flushAll()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void metricJvmFailureLogsPipelineStreamLoad() throws Exception {
        HttpServer server = server("metric_jvm", "x".repeat(400), null);
        try {
            DorisBatchWriter writer = new DorisBatchWriter(1);
            DorisStreamLoadSink sink = new DorisStreamLoadSink(
                    writer, loaderOn(server.getAddress().getPort()), "databuff", "metric_jvm", 3, NOOP_EXECUTOR);
            writer.offer("{\"a\":1}".getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(sink::flushAll).isInstanceOf(IOException.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void onQueueDropCountsForNonPlatformTable() {
        DorisBatchWriter writer = new DorisBatchWriter(1, 60_000, 1);
        DorisStreamLoadSink sink = new DorisStreamLoadSink(
                writer, loaderOn(1), "databuff", "log_dc_record", 3, NOOP_EXECUTOR, 1);
        for (int i = 0; i < 3; i++) {
            writer.offer(("{\"i\":" + i + "}").getBytes(StandardCharsets.UTF_8));
        }
        assertThat(writer.readyBatchCount()).isEqualTo(1);
        assertThat(sink.inFlight()).isEqualTo(1);
    }

    @Test
    void onQueueDropSkipsCounterForPlatformTable() {
        DorisBatchWriter writer = new DorisBatchWriter(1, 60_000, 1);
        DorisStreamLoadSink sink = new DorisStreamLoadSink(
                writer, loaderOn(1), "databuff", "metric_platform", 3, NOOP_EXECUTOR, 1);
        for (int i = 0; i < 3; i++) {
            writer.offer(("{\"i\":" + i + "}").getBytes(StandardCharsets.UTF_8));
        }
        assertThat(writer.readyBatchCount()).isEqualTo(1);
        assertThat(sink.inFlight()).isEqualTo(1);
    }
}
