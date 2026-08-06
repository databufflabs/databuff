package com.databuff.apm.common.platform;

import com.databuff.apm.common.storage.MetricRowTimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/**
 * In-process self-metric registry inspired by Ultra {@code ScheduledMetricUtil} +
 * {@code TSDBMetricExporter} resource sampling.
 * <p>
 * Call sites pass <em>relative</em> metric names to {@link #counter}/{@link #timer}/{@link #gauge}.
 * {@link #start(String, PlatformMetricExporter)} binds the process prefix and starts a
 * <strong>clock-aligned</strong> 1-minute flush (fires ~{@link #FLUSH_AFTER_BOUNDARY_MS} after
 * each minute boundary). Rows are stamped with the <em>previous closed</em> minute bucket
 * ({@code ts} / {@code metric_time}), matching business {@code metric_*} minute semantics.
 * {@link #disable()} installs shared noop instruments and does not start any scheduler.
 * <p>
 * Prefer encoding sparse dimensions in the metric name. Shared tag: process {@code instance}.
 * Optional {@code dim}: type labels (e.g. CPU mode on {@code doris.*.cpu}), Doris table on
 * {@code write.*}, or empty. Callers may pass an explicit {@code instance} (Doris Host on
 * {@code doris.*}) via {@link #gauge(String, String, String)}.
 */
public final class PlatformMetrics {

    private static final Logger log = LoggerFactory.getLogger(PlatformMetrics.class);
    /** Minute bucket width (same as {@link com.databuff.apm.common.metric.TraceMetricMinuteBucket#BUCKET_MS}). */
    static final long BUCKET_MS = 60_000L;
    /** Flush this many ms after the wall-clock minute boundary so the previous minute is closed. */
    static final long FLUSH_AFTER_BOUNDARY_MS = 2_000L;
    private static final long FLUSH_PERIOD_MS = BUCKET_MS;

    private static final Counter NOOP_COUNTER = Counter.noop();
    private static final Timer NOOP_TIMER = Timer.noop();
    private static final Gauge NOOP_GAUGE = Gauge.noop();

    private static final ConcurrentHashMap<Key, Counter> COUNTERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Key, Timer> TIMERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Key, Gauge> GAUGES = new ConcurrentHashMap<>();

    private static final AtomicReference<String> COMPONENT = new AtomicReference<>("");
    private static final AtomicReference<String> INSTANCE = new AtomicReference<>(resolveInstance());
    private static final AtomicReference<PlatformMetricExporter> EXPORTER = new AtomicReference<>();
    /** False until {@link #start}; also false after {@link #disable}. */
    private static final AtomicBoolean ACTIVE = new AtomicBoolean();
    private static final AtomicBoolean FLUSH_SCHEDULED = new AtomicBoolean();

    private static final ScheduledExecutorService SCH = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "platform-self-metric");
        t.setDaemon(true);
        return t;
    });

    private PlatformMetrics() {
    }

    /** Bind component + exporter and start the clock-aligned 1-minute flush (idempotent). */
    public static void start(String component, PlatformMetricExporter exporter) {
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(exporter, "exporter");
        COMPONENT.set(component);
        EXPORTER.set(exporter);
        ACTIVE.set(true);
        if (FLUSH_SCHEDULED.compareAndSet(false, true)) {
            long now = System.currentTimeMillis();
            long delayMs = initialDelayToNextFlushMs(now);
            SCH.scheduleAtFixedRate(
                    PlatformMetrics::flushSafe, delayMs, FLUSH_PERIOD_MS, TimeUnit.MILLISECONDS);
            log.info(
                    "PlatformMetrics started component={} flushPeriodMs={} firstDelayMs={} flushAfterBoundaryMs={}",
                    component,
                    FLUSH_PERIOD_MS,
                    delayMs,
                    FLUSH_AFTER_BOUNDARY_MS);
        } else {
            log.info("PlatformMetrics started component={} (flush already scheduled)", component);
        }
    }

    /**
     * Delay until the next {@code :00 + FLUSH_AFTER_BOUNDARY_MS} wall-clock instant.
     * Visible for tests.
     */
    static long initialDelayToNextFlushMs(long nowMs) {
        long msIntoMinute = Math.floorMod(nowMs, BUCKET_MS);
        if (msIntoMinute < FLUSH_AFTER_BOUNDARY_MS) {
            return FLUSH_AFTER_BOUNDARY_MS - msIntoMinute;
        }
        return BUCKET_MS - msIntoMinute + FLUSH_AFTER_BOUNDARY_MS;
    }

    /**
     * Previous closed minute start (epoch ms). Flush at 10:01:02 stamps {@code 10:00:00}.
     * Visible for tests.
     */
    static long closedMinuteBucketMs(long nowMs) {
        long currentMinute = (nowMs / BUCKET_MS) * BUCKET_MS;
        return Math.max(0L, currentMinute - BUCKET_MS);
    }

    /**
     * Disable self-monitoring: {@link #counter}/{@link #timer}/{@link #gauge} return shared noops;
     * no flush scheduler is started (or further flushes no-op after stop).
     */
    public static void disable() {
        ACTIVE.set(false);
        EXPORTER.set(null);
        COMPONENT.set("");
        COUNTERS.clear();
        TIMERS.clear();
        GAUGES.clear();
        log.info("PlatformMetrics disabled (noop instruments; no flush scheduler)");
    }

    public static boolean isActive() {
        return ACTIVE.get();
    }

    public static void stop() {
        ACTIVE.set(false);
        EXPORTER.set(null);
    }

    public static Counter counter(String metric) {
        return counter(metric, "");
    }

    public static Counter counter(String metric, String dim) {
        if (!ACTIVE.get()) {
            return NOOP_COUNTER;
        }
        return COUNTERS.computeIfAbsent(Key.of(metric, dim), k -> new Counter());
    }

    public static Timer timer(String metric) {
        return timer(metric, "");
    }

    public static Timer timer(String metric, String dim) {
        if (!ACTIVE.get()) {
            return NOOP_TIMER;
        }
        return TIMERS.computeIfAbsent(Key.of(metric, dim), k -> new Timer());
    }

    public static Gauge gauge(String metric) {
        return gauge(metric, "");
    }

    public static Gauge gauge(String metric, String dim) {
        return gauge(metric, dim, "");
    }

    /**
     * @param instance when non-blank, flush writes this as {@code instance} (e.g. Doris Host);
     *                 otherwise falls back to the process hostname
     */
    public static Gauge gauge(String metric, String dim, String instance) {
        if (!ACTIVE.get()) {
            return NOOP_GAUGE;
        }
        return GAUGES.computeIfAbsent(Key.of(metric, dim, instance), k -> new Gauge());
    }

    /** Visible for tests. */
    static void flushNow() {
        flushSafe();
    }

    /** Visible for tests: clear in-memory instruments and reset active state. */
    static void resetForTests() {
        COUNTERS.clear();
        TIMERS.clear();
        GAUGES.clear();
        ACTIVE.set(false);
        EXPORTER.set(null);
        COMPONENT.set("");
        // FLUSH_SCHEDULED stays true once scheduled (daemon); flushSafe no-ops when inactive
    }

    private static void flushSafe() {
        try {
            flush();
        } catch (Throwable t) {
            log.warn("platform metric flush failed: {}", t.toString());
        }
    }

    private static void flush() {
        if (!ACTIVE.get()) {
            return;
        }
        PlatformMetricExporter exporter = EXPORTER.get();
        String component = COMPONENT.get();
        if (exporter == null || component == null || component.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        // Attribute the just-closed minute (accumulation since previous flush ≈ that window).
        long bucketMs = closedMinuteBucketMs(now);
        String processInstance = INSTANCE.get();
        List<Map<String, Object>> rows = new ArrayList<>();

        for (Map.Entry<Key, Counter> e : COUNTERS.entrySet()) {
            long n = e.getValue().adder.sumThenReset();
            if (n == 0L) {
                continue;
            }
            rows.add(counterRow(
                    component, rowInstance(e.getKey(), processInstance),
                    qualifyKey(component, e.getKey()), bucketMs, n));
        }
        for (Map.Entry<Key, Timer> e : TIMERS.entrySet()) {
            long count = e.getValue().countAdder.sumThenReset();
            long sum = e.getValue().sumAdder.sumThenReset();
            long max = e.getValue().drainMax();
            long min = e.getValue().drainMin();
            if (count == 0L) {
                continue;
            }
            rows.add(timerRow(
                    component, rowInstance(e.getKey(), processInstance),
                    qualifyKey(component, e.getKey()), bucketMs, count, sum, max, min));
        }
        for (Map.Entry<Key, Gauge> e : GAUGES.entrySet()) {
            if (!e.getValue().touched.getAndSet(false)) {
                continue;
            }
            double v = e.getValue().value.sum();
            // < 0 / NaN / Inf treat as "no value" (e.g. JVM heap max == -1) — do not write
            if (!Double.isFinite(v) || v < 0.0d) {
                continue;
            }
            rows.add(gaugeRow(
                    component, rowInstance(e.getKey(), processInstance),
                    qualifyKey(component, e.getKey()), bucketMs, v));
        }

        PlatformRuntimeSampler.appendRuntimeRows(rows, component, processInstance, bucketMs);

        if (!rows.isEmpty()) {
            exporter.export(rows);
        }
    }

    private static Key qualifyKey(String component, Key key) {
        return Key.of(PlatformMetricNames.qualify(component, key.metric), key.dim, key.instance);
    }

    /**
     * Prefer explicit instance override (Doris Host). Otherwise use the process hostname.
     * Do not treat {@code dim} as instance — dim may hold type labels (e.g. CPU mode).
     */
    private static String rowInstance(Key key, String processInstance) {
        if (key != null && key.instance != null && !key.instance.isBlank()) {
            return key.instance;
        }
        return processInstance;
    }

    private static Map<String, Object> baseRow(String component, String instance, Key key, long bucketMs) {
        Map<String, Object> row = new HashMap<>(12);
        MetricRowTimeUtil.putTsAndMetricTime(row, bucketMs);
        row.put("component", component);
        row.put("instance", instance);
        row.put("metric", key.metric);
        row.put("dim", key.dim);
        return row;
    }

    private static Map<String, Object> counterRow(
            String component, String instance, Key key, long bucketMs, long cnt) {
        Map<String, Object> row = baseRow(component, instance, key, bucketMs);
        row.put("cnt", cnt);
        return row;
    }

    private static Map<String, Object> timerRow(
            String component,
            String instance,
            Key key,
            long bucketMs,
            long count,
            long sum,
            long max,
            long min) {
        Map<String, Object> row = baseRow(component, instance, key, bucketMs);
        row.put("cnt", count);
        row.put("val_sum", (double) sum);
        if (max != Long.MIN_VALUE) {
            row.put("val_max", (double) max);
        }
        if (min != Long.MAX_VALUE) {
            row.put("val_min", (double) min);
        }
        return row;
    }

    private static Map<String, Object> gaugeRow(
            String component, String instance, Key key, long bucketMs, double value) {
        Map<String, Object> row = baseRow(component, instance, key, bucketMs);
        row.put("val_gauge", value);
        return row;
    }

    private static String resolveInstance() {
        try {
            String host = InetAddress.getLocalHost().getHostName();
            return host == null || host.isBlank() ? "unknown" : host;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private record Key(String metric, String dim, String instance) {
        static Key of(String metric, String dim) {
            return of(metric, dim, "");
        }

        static Key of(String metric, String dim, String instance) {
            String m = Objects.requireNonNull(metric, "metric");
            String d = dim == null ? "" : dim;
            String i = instance == null ? "" : instance;
            return new Key(m, d, i);
        }
    }

    public static final class Counter {
        private final LongAdder adder;
        private final boolean noop;

        Counter() {
            this.adder = new LongAdder();
            this.noop = false;
        }

        private Counter(boolean noop) {
            this.adder = null;
            this.noop = noop;
        }

        static Counter noop() {
            return new Counter(true);
        }

        public void add(long n) {
            if (noop || n == 0L) {
                return;
            }
            adder.add(n);
        }

        public void inc() {
            if (noop) {
                return;
            }
            adder.increment();
        }
    }

    public static final class Timer {
        private final LongAdder countAdder;
        private final LongAdder sumAdder;
        private final java.util.concurrent.atomic.AtomicLong maxHolder;
        private final java.util.concurrent.atomic.AtomicLong minHolder;
        private final boolean noop;

        Timer() {
            this.countAdder = new LongAdder();
            this.sumAdder = new LongAdder();
            this.maxHolder = new java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE);
            this.minHolder = new java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE);
            this.noop = false;
        }

        private Timer(boolean noop) {
            this.countAdder = null;
            this.sumAdder = null;
            this.maxHolder = null;
            this.minHolder = null;
            this.noop = noop;
        }

        static Timer noop() {
            return new Timer(true);
        }

        public void add(long durationMs) {
            if (noop || durationMs < 0L) {
                return;
            }
            countAdder.increment();
            sumAdder.add(durationMs);
            maxHolder.accumulateAndGet(durationMs, Math::max);
            minHolder.accumulateAndGet(durationMs, Math::min);
        }

        long drainMax() {
            return noop ? Long.MIN_VALUE : maxHolder.getAndSet(Long.MIN_VALUE);
        }

        long drainMin() {
            return noop ? Long.MAX_VALUE : minHolder.getAndSet(Long.MAX_VALUE);
        }
    }

    public static final class Gauge {
        private final DoubleAdder value;
        private final AtomicBoolean touched;
        private final boolean noop;

        Gauge() {
            this.value = new DoubleAdder();
            this.touched = new AtomicBoolean();
            this.noop = false;
        }

        private Gauge(boolean noop) {
            this.value = null;
            this.touched = null;
            this.noop = noop;
        }

        static Gauge noop() {
            return new Gauge(true);
        }

        public void set(double v) {
            if (noop) {
                return;
            }
            value.reset();
            value.add(v);
            touched.set(true);
        }
    }
}
