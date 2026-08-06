package com.databuff.apm.common.platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformMetricsTest {

    @AfterEach
    void tearDown() {
        PlatformMetrics.resetForTests();
        PlatformMetrics.stop();
    }

    @Test
    void flushPrefixesRelativeNamesWithComponent() {
        AtomicReference<List<Map<String, Object>>> exported = new AtomicReference<>();
        PlatformMetrics.start("ingest", rows -> exported.set(new ArrayList<>(rows)));

        String req = PlatformMetricNames.inbound(
                PlatformMetricNames.PROTO_OTEL, PlatformMetricNames.SIGNAL_TRACE, PlatformMetricNames.KIND_REQ);
        String cost = PlatformMetricNames.inbound(
                PlatformMetricNames.PROTO_OTEL, PlatformMetricNames.SIGNAL_TRACE, PlatformMetricNames.KIND_COST_MS);
        String queue = PlatformMetricNames.write(PlatformMetricNames.KIND_QUEUE);

        assertThat(req).isEqualTo("otel.trace.req");
        assertThat(queue).isEqualTo("write.queue");
        assertThat(PlatformMetricNames.writeDim("trace_dc_span")).isEqualTo("trace_dc_span");

        PlatformMetrics.counter(req).add(10);
        PlatformMetrics.timer(cost).add(5);
        PlatformMetrics.timer(cost).add(15);
        PlatformMetrics.gauge(queue, PlatformMetricNames.writeDim("trace_dc_span")).set(3);

        PlatformMetrics.flushNow();

        List<Map<String, Object>> rows = exported.get();
        assertThat(rows).isNotNull();

        Map<String, Object> inReq = rows.stream()
                .filter(r -> "ingest.otel.trace.req".equals(r.get("metric")))
                .findFirst()
                .orElseThrow();
        assertThat(inReq.get("component")).isEqualTo("ingest");
        assertThat(inReq.get("dim")).isEqualTo("");
        assertThat(inReq.get("cnt")).isEqualTo(10L);

        Map<String, Object> costRow = rows.stream()
                .filter(r -> "ingest.otel.trace.cost_ms".equals(r.get("metric")))
                .findFirst()
                .orElseThrow();
        assertThat(costRow.get("cnt")).isEqualTo(2L);
        assertThat(costRow.get("val_sum")).isEqualTo(20.0);

        Map<String, Object> queueRow = rows.stream()
                .filter(r -> "ingest.write.queue".equals(r.get("metric")))
                .findFirst()
                .orElseThrow();
        assertThat(queueRow.get("dim")).isEqualTo("trace_dc_span");
        assertThat(queueRow.get("val_gauge")).isEqualTo(3.0);

        assertThat(rows.stream().anyMatch(r -> {
            Object m = r.get("metric");
            return m != null && m.toString().startsWith("ingest.system.");
        })).isTrue();

        assertThat(PlatformMetricNames.query("trace", "req")).isEqualTo("query.trace.req");
        assertThat(PlatformMetricNames.pipeline("aggregate", "drop")).isEqualTo("pipeline.aggregate.drop");
        assertThat(PlatformMetricNames.qualify("web", "query.trace.req")).isEqualTo("web.query.trace.req");
        assertThat(PlatformMetricNames.clusterForward("ingest.trace.span", "req"))
                .isEqualTo("cluster.forward.ingest.trace.span.req");
        assertThat(PlatformMetricNames.clusterForwardIn("ingest.metric.optimized", "fail"))
                .isEqualTo("cluster.forward.in.ingest.metric.optimized.fail");
    }

    @Test
    void disableReturnsSharedNoopAndSkipsFlush() {
        AtomicReference<List<Map<String, Object>>> exported = new AtomicReference<>();
        PlatformMetrics.disable();

        assertThat(PlatformMetrics.isActive()).isFalse();
        PlatformMetrics.Counter c1 = PlatformMetrics.counter("otel.trace.req");
        PlatformMetrics.Counter c2 = PlatformMetrics.counter("otel.trace.fail");
        assertThat(c1).isSameAs(c2);
        c1.inc();
        c1.add(100);
        PlatformMetrics.timer("otel.trace.cost_ms").add(5);
        PlatformMetrics.gauge("write.queue", "trace_dc_span").set(9);

        PlatformMetrics.flushNow();
        assertThat(exported.get()).isNull();

        PlatformMetrics.start("ingest", rows -> exported.set(new ArrayList<>(rows)));
        assertThat(PlatformMetrics.isActive()).isTrue();
        PlatformMetrics.counter("otel.trace.req").add(3);
        PlatformMetrics.flushNow();
        assertThat(exported.get()).isNotNull();
        assertThat(exported.get().stream().anyMatch(r -> "ingest.otel.trace.req".equals(r.get("metric"))))
                .isTrue();
    }

    @Test
    void flushSkipsGaugeWhenValueNegative() {
        AtomicReference<List<Map<String, Object>>> exported = new AtomicReference<>();
        PlatformMetrics.start("web", rows -> exported.set(new ArrayList<>(rows)));

        PlatformMetrics.gauge("doris.up").set(1);
        PlatformMetrics.gauge("bad.sentinel").set(-1);
        PlatformMetrics.gauge(PlatformMetricNames.write(PlatformMetricNames.KIND_QUEUE), "trace_dc_span").set(0);
        PlatformMetrics.flushNow();

        List<Map<String, Object>> rows = exported.get();
        assertThat(rows).isNotNull();
        assertThat(rows.stream().anyMatch(r -> "web.doris.up".equals(r.get("metric")))).isTrue();
        assertThat(rows.stream().anyMatch(r ->
                "web.write.queue".equals(r.get("metric")) && "trace_dc_span".equals(r.get("dim")))).isTrue();
        assertThat(rows.stream().noneMatch(r -> "web.bad.sentinel".equals(r.get("metric")))).isTrue();
        assertThat(rows.stream().noneMatch(r -> {
            Object g = r.get("val_gauge");
            return g instanceof Number n && (!Double.isFinite(n.doubleValue()) || n.doubleValue() < 0);
        })).isTrue();
    }

    @Test
    void flushUsesExplicitInstanceAndCpuTypeDim() {
        AtomicReference<List<Map<String, Object>>> exported = new AtomicReference<>();
        PlatformMetrics.start("web", rows -> exported.set(new ArrayList<>(rows)));

        PlatformMetrics.gauge(PlatformMetricNames.DORIS_UP, "", "10.0.0.1").set(1);
        PlatformMetrics.gauge(PlatformMetricNames.DORIS_BE_ALIVE, "", "10.0.0.2").set(1);
        PlatformMetrics.gauge(PlatformMetricNames.doris("be.cpu"), "softirq", "10.0.0.2").set(3);
        PlatformMetrics.gauge(PlatformMetricNames.doris("be.cpu"), "user", "10.0.0.2").set(7);
        PlatformMetrics.flushNow();

        List<Map<String, Object>> rows = exported.get();
        assertThat(rows).isNotNull();

        Map<String, Object> up = rows.stream()
                .filter(r -> "web.doris.up".equals(r.get("metric")))
                .findFirst()
                .orElseThrow();
        assertThat(up.get("dim")).isEqualTo("");
        assertThat(up.get("instance")).isEqualTo("10.0.0.1");

        Map<String, Object> alive = rows.stream()
                .filter(r -> "web.doris.be.alive".equals(r.get("metric")))
                .findFirst()
                .orElseThrow();
        assertThat(alive.get("instance")).isEqualTo("10.0.0.2");

        Map<String, Object> softirq = rows.stream()
                .filter(r -> "web.doris.be.cpu".equals(r.get("metric")) && "softirq".equals(r.get("dim")))
                .findFirst()
                .orElseThrow();
        assertThat(softirq.get("instance")).isEqualTo("10.0.0.2");
        assertThat(softirq.get("val_gauge")).isEqualTo(3.0);

        Map<String, Object> user = rows.stream()
                .filter(r -> "web.doris.be.cpu".equals(r.get("metric")) && "user".equals(r.get("dim")))
                .findFirst()
                .orElseThrow();
        assertThat(user.get("instance")).isEqualTo("10.0.0.2");
        assertThat(user.get("val_gauge")).isEqualTo(7.0);
    }

    @Test
    void flushStampsPreviousClosedMinute() {
        AtomicReference<List<Map<String, Object>>> exported = new AtomicReference<>();
        PlatformMetrics.start("ingest", rows -> exported.set(new ArrayList<>(rows)));

        PlatformMetrics.counter("otel.trace.req").add(1);
        long beforeFlush = System.currentTimeMillis();
        PlatformMetrics.flushNow();
        long afterFlush = System.currentTimeMillis();

        List<Map<String, Object>> rows = exported.get();
        assertThat(rows).isNotNull();
        Map<String, Object> row = rows.stream()
                .filter(r -> "ingest.otel.trace.req".equals(r.get("metric")))
                .findFirst()
                .orElseThrow();
        assertThat(row.get("ts")).isInstanceOf(Long.class);
        long ts = (Long) row.get("ts");
        assertThat(ts % PlatformMetrics.BUCKET_MS).isZero();
        // Must match closed-minute of the flush instant (tolerate rare minute boundary cross).
        assertThat(ts).isIn(
                PlatformMetrics.closedMinuteBucketMs(beforeFlush),
                PlatformMetrics.closedMinuteBucketMs(afterFlush));
        long openMinute = (afterFlush / PlatformMetrics.BUCKET_MS) * PlatformMetrics.BUCKET_MS;
        assertThat(ts).isLessThan(openMinute);
    }

    @Test
    void closedMinuteBucketAndFlushDelayAlignToClock() {
        // 10:01:00.000 → closed bucket 10:00; delay 2s to flush at 10:01:02
        long atBoundary = 1_704_067_260_000L; // aligned minute
        assertThat(PlatformMetrics.closedMinuteBucketMs(atBoundary + 2_000L))
                .isEqualTo(atBoundary - PlatformMetrics.BUCKET_MS);
        assertThat(PlatformMetrics.initialDelayToNextFlushMs(atBoundary))
                .isEqualTo(PlatformMetrics.FLUSH_AFTER_BOUNDARY_MS);
        // Already past :02 → wait until next minute's :02
        assertThat(PlatformMetrics.initialDelayToNextFlushMs(atBoundary + 2_000L))
                .isEqualTo(PlatformMetrics.BUCKET_MS);
        assertThat(PlatformMetrics.initialDelayToNextFlushMs(atBoundary + 30_000L))
                .isEqualTo(PlatformMetrics.BUCKET_MS - 30_000L + PlatformMetrics.FLUSH_AFTER_BOUNDARY_MS);
    }

    @Test
    void exporterSkipsNegativeGaugeRows() {
        assertThat(DorisPlatformMetricExporter.hasWritableValue(Map.of("val_gauge", -1.0))).isFalse();
        assertThat(DorisPlatformMetricExporter.hasWritableValue(Map.of("val_gauge", Double.NaN))).isFalse();
        assertThat(DorisPlatformMetricExporter.hasWritableValue(Map.of("val_gauge", 0.0))).isTrue();
        assertThat(DorisPlatformMetricExporter.hasWritableValue(Map.of("cnt", 3L))).isTrue();
        assertThat(DorisPlatformMetricExporter.hasWritableValue(Map.of("metric", "x"))).isFalse();
    }
}
