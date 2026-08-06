package com.databuff.apm.common.platform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Maps Doris FE/BE Prometheus samples to Ultra 自监控看板 logical names
 * ({@code webapp.starrocks.*} → OSS {@code doris.*}).
 * <p>
 * Most sparse labels are folded into the metric name. {@code doris_be_cpu} mode goes to
 * {@code dim} (metric stays {@code doris.cpu}). Node Host is set as {@code instance} by the sampler.
 * Counter samples keep a cumulative flag so the sampler can store per-interval deltas;
 * CPU deltas are further normalized to mode percentages before write.
 */
public final class DorisSelfMonitorMapper {

    public static final Set<String> ULTRA_LOGICAL_NAMES = Set.of(
            "async_delta_writer_execute_time_ns_total",
            "async_delta_writer_queue_count",
            "async_delta_writer_task_pending_duration_us",
            "compaction_bytes_total",
            "compaction_mem_bytes",
            "cpu",
            "disks_data_used_capacity",
            "disks_total_capacity",
            "edit_log_write",
            "engine_requests_total",
            "image_write",
            "jvm_heap_size_bytes",
            "jvm_old_gc",
            "jvm_young_gc",
            "max_network_receive_bytes_rate",
            "max_network_send_bytes_rate",
            "memtable_flush_disk_bytes_total",
            "memtable_flush_duration_us",
            "memtable_flush_io_time_us",
            "memtable_flush_total",
            "process_fd_num_limit_hard",
            "process_fd_num_used",
            "process_mem_bytes",
            "query_scan_bytes",
            "query_scan_rows",
            "query_success",
            "segment_read",
            "stream_load",
            "tablet_base_max_compaction_score",
            "tablet_cumulative_max_compaction_score",
            "tablet_update_max_compaction_score",
            "thread_pool",
            "txn_request");

    private DorisSelfMonitorMapper() {
    }

    public record MappedMetric(String platformMetric, String dim, double value, boolean cumulative) {
        public MappedMetric(String platformMetric, double value, boolean cumulative) {
            this(platformMetric, "", value, cumulative);
        }

        public MappedMetric(String platformMetric, double value) {
            this(platformMetric, "", value, false);
        }
    }

    /** Aggregated absolute value + whether scrape should emit a delta. */
    public record AggregatedMetric(String metric, String dim, double value, boolean cumulative) {
    }

    public static List<MappedMetric> map(DorisPrometheusTextParser.Sample sample) {
        if (sample == null || sample.name() == null) {
            return List.of();
        }
        String name = sample.name();
        Map<String, String> labels = sample.labels();
        DorisPrometheusTextParser.MetricType type = sample.type();
        List<MappedMetric> out = new ArrayList<>(2);

        switch (name) {
            case "doris_be_cpu" -> {
                if (!"cpu".equals(labels.getOrDefault("device", ""))) {
                    break;
                }
                String mode = labelSuffix(labels, "mode");
                if (mode.isBlank()) {
                    break;
                }
                // metric=doris.cpu，mode 进 dim（如 softirq / user），便于单图 groupBy type
                addWithDim(out, "cpu", mode, sample.value(), type);
            }
            case "doris_be_memory_allocated_bytes" ->
                    add(out, "process_mem_bytes", "", sample.value(), type);
            case "doris_be_disks_local_used_capacity" ->
                    add(out, "disks_data_used_capacity", pathSuffix(labels), sample.value(), type);
            case "doris_be_disks_total_capacity" ->
                    add(out, "disks_total_capacity", pathSuffix(labels), sample.value(), type);
            case "doris_be_jvm_heap_size_bytes", "jvm_heap_size_bytes" ->
                    add(out, "jvm_heap_size_bytes", labelSuffix(labels, "type"), sample.value(), type);
            case "doris_be_jvm_gc", "jvm_gc" -> mapJvmGc(out, labels, sample.value(), type);
            case "doris_be_max_network_receive_bytes_rate" ->
                    add(out, "max_network_receive_bytes_rate", "", sample.value(), type);
            case "doris_be_max_network_send_bytes_rate" ->
                    add(out, "max_network_send_bytes_rate", "", sample.value(), type);
            case "doris_be_compaction_bytes_total" ->
                    add(out, "compaction_bytes_total", labelSuffix(labels, "type"), sample.value(), type);
            case "doris_be_compaction_mem_bytes" ->
                    add(out, "compaction_mem_bytes", "", sample.value(), type);
            case "doris_be_engine_requests_total" -> add(out, "engine_requests_total",
                    joinSuffix(labelSuffix(labels, "type"), labelSuffix(labels, "status")),
                    sample.value(),
                    type);
            case "doris_be_memtable_flush_total" ->
                    add(out, "memtable_flush_total", "", sample.value(), type);
            case "doris_be_memtable_flush_duration_us" ->
                    add(out, "memtable_flush_duration_us", "", sample.value(), type);
            case "doris_be_memtable_flush_disk_bytes_total" ->
                    add(out, "memtable_flush_disk_bytes_total", "", sample.value(), type);
            case "doris_be_memtable_flush_io_time_us" ->
                    add(out, "memtable_flush_io_time_us", "", sample.value(), type);
            case "doris_be_process_fd_num_limit_hard" ->
                    add(out, "process_fd_num_limit_hard", "", sample.value(), type);
            case "doris_be_process_fd_num_used" ->
                    add(out, "process_fd_num_used", "", sample.value(), type);
            case "doris_be_query_scan_bytes" ->
                    add(out, "query_scan_bytes", "", sample.value(), type);
            case "doris_be_query_scan_rows" ->
                    add(out, "query_scan_rows", "", sample.value(), type);
            case "doris_be_segment_read" ->
                    add(out, "segment_read", labelSuffix(labels, "type"), sample.value(), type);
            case "doris_be_stream_load" ->
                    add(out, "stream_load", labelSuffix(labels, "type"), sample.value(), type);
            case "doris_be_tablet_base_max_compaction_score" ->
                    add(out, "tablet_base_max_compaction_score", "", sample.value(), type);
            case "doris_be_tablet_cumulative_max_compaction_score" ->
                    add(out, "tablet_cumulative_max_compaction_score", "", sample.value(), type);
            case "doris_be_tablet_update_max_compaction_score" ->
                    add(out, "tablet_update_max_compaction_score", "", sample.value(), type);
            case "doris_be_thread_pool_active_threads" -> {
                String pool = labels.getOrDefault("thread_pool_name", "");
                if (pool.isBlank()) {
                    break;
                }
                add(out, "thread_pool", PlatformMetricNames.token(pool, "pool"), sample.value(), type);
            }
            case "doris_be_stream_load_txn_request" ->
                    add(out, "txn_request", labelSuffix(labels, "type"), sample.value(), type);
            case "doris_be_async_delta_writer_execute_time_ns_total" ->
                    add(out, "async_delta_writer_execute_time_ns_total", "", sample.value(), type);
            case "doris_be_async_delta_writer_queue_count" ->
                    add(out, "async_delta_writer_queue_count", "", sample.value(), type);
            case "doris_be_async_delta_writer_task_pending_duration_us" ->
                    add(out, "async_delta_writer_task_pending_duration_us", "", sample.value(), type);
            case "doris_fe_query_total" -> {
                if (labels.containsKey("user")) {
                    break;
                }
                add(out, "query_success", "", sample.value(), type);
            }
            case "doris_fe_edit_log" -> {
                if ("write".equals(labels.getOrDefault("type", ""))) {
                    add(out, "edit_log_write", "", sample.value(), type);
                }
            }
            case "doris_fe_image_write" ->
                    add(out, "image_write", labelSuffix(labels, "type"), sample.value(), type);
            case "doris_fe_txn_counter" ->
                    add(out, "txn_request", labelSuffix(labels, "type"), sample.value(), type);
            case "doris_fe_thread_pool" -> {
                if (!"active_thread_num".equals(labels.getOrDefault("type", ""))) {
                    break;
                }
                String pool = labels.getOrDefault("name", "");
                if (pool.isBlank() || pool.startsWith("PUBLISH_VERSION_EXEC-")) {
                    break;
                }
                add(out, "thread_pool", PlatformMetricNames.token(pool, "pool"), sample.value(), type);
            }
            default -> {
            }
        }
        return out;
    }

    /** @deprecated prefer {@link #mapAndAggregateTyped(List)} */
    public static Map<String, Double> mapAndAggregate(List<DorisPrometheusTextParser.Sample> samples) {
        Map<String, AggregatedMetric> typed = mapAndAggregateTyped(samples);
        Map<String, Double> agg = new LinkedHashMap<>();
        for (AggregatedMetric e : typed.values()) {
            // 兼容旧 key：有 dim 时拼回 metric.dim（如 doris.cpu.idle）
            String key = e.dim() == null || e.dim().isBlank()
                    ? e.metric()
                    : e.metric() + "." + e.dim();
            agg.merge(key, e.value(), Double::sum);
        }
        return agg;
    }

    /**
     * Aggregate by {@code metric + dim}. Map key is {@code metric + '\\0' + dim}.
     */
    public static Map<String, AggregatedMetric> mapAndAggregateTyped(
            List<DorisPrometheusTextParser.Sample> samples) {
        Map<String, AggregatedMetric> agg = new LinkedHashMap<>();
        if (samples == null) {
            return agg;
        }
        for (DorisPrometheusTextParser.Sample sample : samples) {
            for (MappedMetric m : map(sample)) {
                String dim = m.dim() == null ? "" : m.dim();
                String key = m.platformMetric() + '\0' + dim;
                agg.merge(
                        key,
                        new AggregatedMetric(m.platformMetric(), dim, m.value(), m.cumulative()),
                        (a, b) -> new AggregatedMetric(
                                a.metric(),
                                a.dim(),
                                a.value() + b.value(),
                                a.cumulative() || b.cumulative()));
            }
        }
        return agg;
    }

    /**
     * Insert FE/BE role into a relative Doris metric: {@code doris.cpu.user} → {@code doris.be.cpu.user}.
     * Idempotent if already role-prefixed. Leaves non-{@code doris.*} names unchanged.
     */
    public static String withRole(String relativeDorisMetric, String role) {
        if (relativeDorisMetric == null || relativeDorisMetric.isBlank()) {
            return relativeDorisMetric;
        }
        String r = role == null ? "" : role.trim().toLowerCase(Locale.ROOT);
        if (!"be".equals(r) && !"fe".equals(r)) {
            return relativeDorisMetric.trim();
        }
        String m = relativeDorisMetric.trim();
        String prefix;
        String rest;
        if (m.startsWith("web.doris.")) {
            prefix = "web.doris.";
            rest = m.substring("web.doris.".length());
        } else if (m.startsWith("doris.")) {
            prefix = "doris.";
            rest = m.substring("doris.".length());
        } else {
            return m;
        }
        if (rest.startsWith("be.") || rest.startsWith("fe.")) {
            return m;
        }
        return prefix + r + "." + rest;
    }

    /**
     * Whether this relative Doris metric is the host CPU family ({@code doris[.be|.fe].cpu}).
     * Mode labels live in {@code dim}; values are stored as percent of scrape-interval CPU time.
     */
    public static boolean isCpuMetric(String relativeDorisMetric) {
        String m = stripDorisPrefix(relativeDorisMetric);
        return "cpu".equals(m);
    }

    /**
     * Convert per-mode jiffy deltas from {@code /proc/stat} into percentages that sum to 100.
     * Keys are mode names (user / idle / …). Non-finite or negative deltas are treated as 0.
     * When total ≤ 0, every mode is 0.
     */
    public static Map<String, Double> cpuModeDeltasToPercents(Map<String, Double> modeDeltas) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (modeDeltas == null || modeDeltas.isEmpty()) {
            return out;
        }
        double total = 0.0;
        for (Map.Entry<String, Double> e : modeDeltas.entrySet()) {
            String mode = e.getKey() == null ? "" : e.getKey();
            double v = e.getValue() == null || !Double.isFinite(e.getValue()) ? 0.0 : Math.max(0.0, e.getValue());
            out.put(mode, v);
            total += v;
        }
        if (total <= 0.0) {
            out.replaceAll((k, v) -> 0.0);
            return out;
        }
        for (Map.Entry<String, Double> e : out.entrySet()) {
            e.setValue(e.getValue() * 100.0 / total);
        }
        return out;
    }

    /**
     * Fallback when Prometheus TYPE is missing: treat known monotonic series as cumulative.
     */
    public static boolean isLikelyCumulativeLogical(String relativeDorisMetric) {
        String m = stripDorisPrefix(relativeDorisMetric);
        if (m.isEmpty()) {
            return false;
        }
        if ("cpu".equals(m) || m.startsWith("cpu.")) {
            return true;
        }
        if (m.startsWith("memtable_flush_")) {
            return true;
        }
        if (m.startsWith("compaction_bytes_total")) {
            return true;
        }
        if (m.startsWith("engine_requests_total")) {
            return true;
        }
        if (m.startsWith("segment_read.")) {
            return true;
        }
        if (m.startsWith("stream_load.")) {
            return true;
        }
        if (m.startsWith("jvm_young_gc.") || m.startsWith("jvm_old_gc.")) {
            return true;
        }
        if (m.startsWith("image_write.")) {
            return true;
        }
        if (m.startsWith("txn_request.")) {
            return true;
        }
        if (m.startsWith("async_delta_writer_execute_time")) {
            return true;
        }
        return switch (m) {
            case "query_success",
                    "query_scan_bytes",
                    "query_scan_rows",
                    "edit_log_write",
                    "memtable_flush_total",
                    "memtable_flush_duration_us",
                    "memtable_flush_io_time_us",
                    "memtable_flush_disk_bytes_total" -> true;
            default -> false;
        };
    }

    private static void mapJvmGc(
            List<MappedMetric> out,
            Map<String, String> labels,
            double value,
            DorisPrometheusTextParser.MetricType type) {
        String gcName = labels.getOrDefault("name", "").toLowerCase(Locale.ROOT);
        String typeSuffix = labelSuffix(labels, "type");
        if (gcName.contains("young")) {
            add(out, "jvm_young_gc", typeSuffix, value, type);
        } else if (gcName.contains("old")) {
            add(out, "jvm_old_gc", typeSuffix, value, type);
        }
    }

    private static void add(
            List<MappedMetric> out,
            String logical,
            String suffix,
            double value,
            DorisPrometheusTextParser.MetricType type) {
        addWithDim(out, logical, suffix, "", value, type);
    }

    private static void addWithDim(
            List<MappedMetric> out,
            String logical,
            String dim,
            double value,
            DorisPrometheusTextParser.MetricType type) {
        addWithDim(out, logical, "", dim, value, type);
    }

    private static void addWithDim(
            List<MappedMetric> out,
            String logical,
            String nameSuffix,
            String dim,
            double value,
            DorisPrometheusTextParser.MetricType type) {
        String metric = PlatformMetricNames.doris(logical, nameSuffix);
        boolean cumulative = switch (type == null ? DorisPrometheusTextParser.MetricType.UNKNOWN : type) {
            case COUNTER -> true;
            case GAUGE -> false;
            case UNKNOWN -> isLikelyCumulativeLogical(metric);
        };
        out.add(new MappedMetric(metric, dim == null ? "" : dim, value, cumulative));
    }

    private static String stripDorisPrefix(String metric) {
        if (metric == null) {
            return "";
        }
        String m = metric.trim();
        if (m.startsWith("web.doris.")) {
            m = m.substring("web.doris.".length());
        } else if (m.startsWith("doris.")) {
            m = m.substring("doris.".length());
        }
        // role-qualified scrape names: be.cpu.user / fe.query_success
        if (m.startsWith("be.") || m.startsWith("fe.")) {
            m = m.substring(3);
        }
        return m;
    }

    private static String labelSuffix(Map<String, String> labels, String key) {
        String v = labels.get(key);
        if (v == null || v.isBlank()) {
            return "";
        }
        return PlatformMetricNames.token(v, key);
    }

    private static String pathSuffix(Map<String, String> labels) {
        String path = labels.get("path");
        if (path == null || path.isBlank()) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        String leaf = slash >= 0 && slash < path.length() - 1 ? path.substring(slash + 1) : path;
        return PlatformMetricNames.token(leaf, "path");
    }

    private static String joinSuffix(String a, String b) {
        if (a == null || a.isBlank()) {
            return b == null ? "" : b;
        }
        if (b == null || b.isBlank()) {
            return a;
        }
        return a + "." + b;
    }
}
