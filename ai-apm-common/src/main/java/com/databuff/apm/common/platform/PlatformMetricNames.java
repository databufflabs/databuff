package com.databuff.apm.common.platform;

/**
 * Canonical self-monitoring metric <em>relative</em> names for {@code metric_platform}.
 * <p>
 * Call sites pass relative names to {@link PlatformMetrics}; flush prefixes
 * {@code ingest}/{@code web}. Prefer encoding sparse dimensions in the metric name
 * (API domain, pipeline, GC name, cluster stream). Shared process tag: {@code instance}
 * (hostname/pod). For {@code doris.*}, callers pass Doris Host as {@code instance}.
 * Optional {@code dim}: CPU mode on {@code doris.*.cpu}, Doris <strong>table</strong>
 * on {@code write.*} (tables are too many to encode in the metric name).
 */
public final class PlatformMetricNames {

    private PlatformMetricNames() {
    }

    public static final String COMPONENT_INGEST = "ingest";
    public static final String COMPONENT_WEB = "web";

    public static final String PROTO_OTEL = "otel";
    public static final String PROTO_SW = "sw";

    public static final String SIGNAL_TRACE = "trace";
    public static final String SIGNAL_METRIC = "metric";
    public static final String SIGNAL_LOG = "log";

    /** Item throughput: span / metric-line / log counts for inbound (not HTTP/gRPC request count). */
    public static final String KIND_REQ = "req";
    public static final String KIND_FAIL = "fail";
    public static final String KIND_COST_MS = "cost_ms";
    public static final String KIND_BYTES = "bytes";
    public static final String KIND_QUEUE = "queue";
    public static final String KIND_QUEUE_CAP = "queue.cap";
    public static final String KIND_DROP = "drop";

    public static final String PIPELINE_TRACE = "trace";
    public static final String PIPELINE_METRIC = "metric";
    public static final String PIPELINE_AGGREGATE = "aggregate";

    public static final String TRACE_ASSEMBLY_PENDING = "trace.assembly.pending";
    public static final String TRACE_DROP_IGNORE = "trace.drop.ignore";

    public static final String DORIS_UP = "doris.up";
    public static final String DORIS_BE_ALIVE = "doris.be.alive";
    public static final String DORIS_FE_ALIVE = "doris.fe.alive";

    public static final String CLUSTER_MEMBER_COUNT = "cluster.member.count";
    public static final String CLUSTER_LEADER = "cluster.leader";
    public static final String CLUSTER_EFFECTIVE = "cluster.effective";

    public static final String SYSTEM_CPU_USAGE = "system.cpu.usage";
    public static final String SYSTEM_HEAP_USED = "system.memory.heap.used";
    public static final String SYSTEM_HEAP_MAX = "system.memory.heap.max";
    public static final String SYSTEM_THREAD_COUNT = "system.thread.count";

    /** Relative inbound: {@code otel.trace.req}. */
    public static String inbound(String protocol, String signal, String kind) {
        return protocol + "." + signal + "." + kind;
    }

    /**
     * Relative write kind only: {@code write.req} / {@code write.queue}.
     * Pass table name as {@code dim} via {@link #writeDim(String)}.
     */
    public static String write(String kind) {
        return "write." + token(kind, "unknown");
    }

    /** {@code dim} value for write.* series — Doris target table. */
    public static String writeDim(String table) {
        return token(table, "unknown");
    }

    /** Relative web query: {@code query.trace.req} — API domain encoded in the name. */
    public static String query(String domain, String kind) {
        return "query." + token(domain, "other") + "." + kind;
    }

    /** Relative pipeline: {@code pipeline.trace.req} / {@code pipeline.aggregate.cost_ms} / {@code pipeline.metric.drop}. */
    public static String pipeline(String pipeline, String kind) {
        return "pipeline." + token(pipeline, "unknown") + "." + kind;
    }

    /** Relative cluster drop: {@code cluster.drop.ingest.trace.span}. */
    public static String clusterDrop(String stream) {
        return "cluster.drop." + token(stream, "unknown");
    }

    /** Outbound forward: {@code cluster.forward.ingest.trace.span.req}. */
    public static String clusterForward(String stream, String kind) {
        return "cluster.forward." + token(stream, "unknown") + "." + token(kind, "unknown");
    }

    /** Inbound forward: {@code cluster.forward.in.ingest.trace.span.req}. */
    public static String clusterForwardIn(String stream, String kind) {
        return "cluster.forward.in." + token(stream, "unknown") + "." + token(kind, "unknown");
    }

    /** Relative GC: {@code system.gc.count.G1YoungGeneration}. */
    public static String systemGc(String kind, String gcName) {
        return "system.gc." + kind + "." + token(gcName, "unknown");
    }

    /** Doris self-metric → {@code doris.query_success}; optional label suffix ({@code type}/{@code mode}/…). */
    public static String doris(String metricName) {
        return doris(metricName, "");
    }

    public static String doris(String metricName, String tagSuffix) {
        String n = token(metricName, "unknown");
        String tag = tagSuffix == null ? "" : tagSuffix.trim();
        if (tag.isEmpty()) {
            return "doris." + n;
        }
        return "doris." + n + "." + token(tag, "tag");
    }

    /**
     * Prefix relative metric with component. Idempotent if already prefixed.
     */
    public static String qualify(String component, String relativeMetric) {
        String c = component == null ? "" : component.trim();
        String m = relativeMetric == null ? "" : relativeMetric.trim();
        if (c.isEmpty()) {
            return m;
        }
        if (m.isEmpty()) {
            return c;
        }
        if (m.startsWith(c + ".")) {
            return m;
        }
        return c + "." + m;
    }

    /** Sanitize a name fragment for embedding in metric (no spaces / separators). */
    public static String token(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String s = raw.trim()
                .replace(' ', '_')
                .replace('|', '_')
                .replace('/', '.')
                .replace('\\', '.');
        while (s.contains("..")) {
            s = s.replace("..", ".");
        }
        if (s.startsWith(".")) {
            s = s.substring(1);
        }
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.isEmpty() ? fallback : s;
    }
}
