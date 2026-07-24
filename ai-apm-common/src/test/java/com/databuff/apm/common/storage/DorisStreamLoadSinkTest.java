package com.databuff.apm.common.storage;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DorisStreamLoadSinkTest {

    /** Disable async ready-listener flushes so tests drive Stream Load explicitly. */
    private static final java.util.concurrent.Executor NOOP_EXECUTOR = command -> {
    };

    @Test
    void joinsJsonLinesWithNewline() throws Exception {
        byte[] joined = DorisStreamLoadSink.joinJsonLines(List.of(
                DorisJsonRow.ofBytes("{\"a\":1}".getBytes()),
                DorisJsonRow.ofBytes("{\"b\":2}".getBytes())));
        assertThat(new String(joined)).isEqualTo("{\"a\":1}\n{\"b\":2}");
    }

    @Test
    void flushAllReturnsZeroWhenEmpty() throws Exception {
        DorisStreamLoadSink sink = new DorisStreamLoadSink(
                new DorisBatchWriter(),
                new DorisStreamLoader(new DorisConnectionConfig("localhost", 9030, 8030), "root", ""),
                "databuff",
                "metric_service",
                DorisStreamLoadSink.DEFAULT_MAX_CONSECUTIVE_FAILURES,
                NOOP_EXECUTOR);
        assertThat(sink.flushAll()).isZero();
    }

    @Test
    void flushReadyLoadsWhenBatchBytesFull() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/api/databuff/metric_service/_stream_load", exchange -> {
            calls.incrementAndGet();
            byte[] ok = "{\"Status\": \"Success\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            try (java.io.OutputStream os = exchange.getResponseBody()) {
                os.write(ok);
            }
        });
        server.start();
        try {
            DorisStreamLoader loader = new DorisStreamLoader(
                    new DorisConnectionConfig("127.0.0.1", 9030, port), "root", "");
            // "{\"a\":1}" is 7 bytes + newline => 8; threshold 10 needs two rows
            DorisBatchWriter writer = new DorisBatchWriter(10);
            DorisStreamLoadSink sink = new DorisStreamLoadSink(
                    writer,
                    loader,
                    "databuff",
                    "metric_service",
                    DorisStreamLoadSink.DEFAULT_MAX_CONSECUTIVE_FAILURES,
                    NOOP_EXECUTOR);
            writer.offer("{\"a\":1}".getBytes(StandardCharsets.UTF_8));
            assertThat(sink.flushReady()).isZero();
            writer.offer("{\"a\":2}".getBytes(StandardCharsets.UTF_8));
            assertThat(sink.flushReady()).isEqualTo(2);
            assertThat(calls.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void exposesDatabaseAndTable() {
        DorisStreamLoadSink sink = new DorisStreamLoadSink(
                new DorisBatchWriter(),
                new DorisStreamLoader(new DorisConnectionConfig("h", 9030, 8030), "root", ""),
                "databuff",
                "trace_dc_span",
                DorisStreamLoadSink.DEFAULT_MAX_CONSECUTIVE_FAILURES,
                NOOP_EXECUTOR);
        assertThat(sink.database()).isEqualTo("databuff");
        assertThat(sink.table()).isEqualTo("trace_dc_span");
    }

    @Test
    void requeuesOnFailureUntilBudgetThenDrops() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/api/databuff/log_dc_record/_stream_load", exchange -> {
            calls.incrementAndGet();
            byte[] fail = "{\"Status\": \"Fail\", \"Message\": \"too long\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, fail.length);
            try (java.io.OutputStream os = exchange.getResponseBody()) {
                os.write(fail);
            }
        });
        server.start();
        try {
            DorisStreamLoader loader = new DorisStreamLoader(
                    new DorisConnectionConfig("127.0.0.1", 9030, port), "root", "");
            DorisBatchWriter writer = new DorisBatchWriter();
            DorisStreamLoadSink sink = new DorisStreamLoadSink(
                    writer, loader, "databuff", "log_dc_record", 3, NOOP_EXECUTOR);
            writer.offer("{\"body\":\"x\"}".getBytes(StandardCharsets.UTF_8));

            assertThatThrownBy(sink::flushAll).isInstanceOf(IOException.class);
            assertThat(writer.pendingCount()).isEqualTo(1);
            assertThat(sink.consecutiveFailures()).isEqualTo(1);

            assertThatThrownBy(sink::flushAll).isInstanceOf(IOException.class);
            assertThat(writer.pendingCount()).isEqualTo(1);
            assertThat(sink.consecutiveFailures()).isEqualTo(2);

            assertThat(sink.flushAll()).isZero();
            assertThat(writer.pendingCount()).isZero();
            assertThat(sink.consecutiveFailures()).isZero();
            assertThat(calls.get()).isEqualTo(3);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void asyncFlushRunsUpToConfiguredConcurrency() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int port = server.getAddress().getPort();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/api/databuff/trace_dc_span/_stream_load", exchange -> {
            int n = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(n, Math::max);
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("release timeout");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            } finally {
                inFlight.decrementAndGet();
            }
            completed.incrementAndGet();
            byte[] ok = "{\"Status\": \"Success\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, ok.length);
            try (java.io.OutputStream os = exchange.getResponseBody()) {
                os.write(ok);
            }
        });
        ExecutorService httpExecutor = Executors.newFixedThreadPool(8);
        server.setExecutor(httpExecutor);
        server.start();
        ExecutorService flushExecutor = Executors.newFixedThreadPool(8);
        try {
            DorisStreamLoader loader = new DorisStreamLoader(
                    new DorisConnectionConfig("127.0.0.1", 9030, port), "root", "");
            // Each offer exceeds threshold → immediate hand-off (4 ready batches).
            DorisBatchWriter writer = new DorisBatchWriter(1);
            DorisStreamLoadSink sink = new DorisStreamLoadSink(
                    writer,
                    loader,
                    "databuff",
                    "trace_dc_span",
                    DorisStreamLoadSink.DEFAULT_MAX_CONSECUTIVE_FAILURES,
                    flushExecutor,
                    4);
            assertThat(sink.flushConcurrency()).isEqualTo(4);

            for (int i = 0; i < 4; i++) {
                writer.offer(("{\"i\":" + i + "}").getBytes(StandardCharsets.UTF_8));
            }
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(maxInFlight.get()).isEqualTo(4);
            release.countDown();
            // Wait for in-flight loads to finish before shutting the pool down.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (completed.get() < 4 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(completed.get()).isEqualTo(4);
            assertThat(writer.pendingCount()).isZero();
            flushExecutor.shutdown();
            assertThat(flushExecutor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            flushExecutor.shutdownNow();
            server.stop(0);
            httpExecutor.shutdownNow();
        }
    }
}
