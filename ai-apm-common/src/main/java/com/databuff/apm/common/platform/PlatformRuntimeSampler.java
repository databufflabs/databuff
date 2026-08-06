package com.databuff.apm.common.platform;

import com.databuff.apm.common.storage.MetricRowTimeUtil;
import com.sun.management.OperatingSystemMXBean;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Samples JVM CPU / heap / GC / threads each flush (Ultra {@code TSDBMetricExporter} style).
 * GC name is encoded in the metric ({@code system.gc.count.G1YoungGeneration}); dim stays empty.
 */
final class PlatformRuntimeSampler {

    private static final Map<String, Long> LAST_GC_COUNT = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_GC_TIME = new ConcurrentHashMap<>();

    private PlatformRuntimeSampler() {
    }

    static void appendRuntimeRows(List<Map<String, Object>> rows, String component, String instance, long bucketMs) {
        try {
            appendCpu(rows, component, instance, bucketMs);
            appendHeap(rows, component, instance, bucketMs);
            appendGc(rows, component, instance, bucketMs);
            appendThreads(rows, component, instance, bucketMs);
        } catch (Throwable ignored) {
            // never break flush
        }
    }

    private static void appendCpu(List<Map<String, Object>> rows, String component, String instance, long bucketMs) {
        double usage = processCpuPercent();
        if (usage < 0 || usage > 1000) {
            return;
        }
        rows.add(gauge(component, instance, PlatformMetricNames.SYSTEM_CPU_USAGE, bucketMs, usage));
    }

    private static void appendHeap(List<Map<String, Object>> rows, String component, String instance, long bucketMs) {
        MemoryMXBean bean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = bean.getHeapMemoryUsage();
        long used = heap.getUsed();
        if (used >= 0L) {
            rows.add(gauge(component, instance, PlatformMetricNames.SYSTEM_HEAP_USED, bucketMs, used));
        }
        long max = heap.getMax();
        // JVM returns -1 when max is undefined
        if (max >= 0L) {
            rows.add(gauge(component, instance, PlatformMetricNames.SYSTEM_HEAP_MAX, bucketMs, max));
        }
    }

    private static void appendGc(List<Map<String, Object>> rows, String component, String instance, long bucketMs) {
        for (GarbageCollectorMXBean mx : ManagementFactory.getGarbageCollectorMXBeans()) {
            String name = mx.getName() == null ? "unknown" : mx.getName().replace(" ", "");
            long count = mx.getCollectionCount();
            long time = mx.getCollectionTime();
            if (count < 0) {
                continue;
            }
            long prevCount = LAST_GC_COUNT.getOrDefault(name, count);
            long prevTime = LAST_GC_TIME.getOrDefault(name, time);
            LAST_GC_COUNT.put(name, count);
            LAST_GC_TIME.put(name, time);
            long dCount = Math.max(0L, count - prevCount);
            long dTime = Math.max(0L, time - prevTime);
            if (dCount > 0L) {
                rows.add(counter(
                        component,
                        instance,
                        PlatformMetricNames.systemGc("count", name),
                        bucketMs,
                        dCount));
            }
            if (dTime > 0L) {
                rows.add(counter(
                        component,
                        instance,
                        PlatformMetricNames.systemGc("time", name),
                        bucketMs,
                        dTime));
            }
        }
    }

    private static void appendThreads(
            List<Map<String, Object>> rows, String component, String instance, long bucketMs) {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        rows.add(gauge(
                component,
                instance,
                PlatformMetricNames.SYSTEM_THREAD_COUNT,
                bucketMs,
                threads.getThreadCount()));
    }

    private static double processCpuPercent() {
        try {
            OperatingSystemMXBean os = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
            double load = os.getProcessCpuLoad();
            if (load >= 0) {
                return load * 100.0;
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return -1;
    }

    private static Map<String, Object> gauge(
            String component, String instance, String relativeMetric, long bucketMs, double value) {
        Map<String, Object> row = base(component, instance, relativeMetric, bucketMs);
        row.put("val_gauge", value);
        return row;
    }

    private static Map<String, Object> counter(
            String component, String instance, String relativeMetric, long bucketMs, long cnt) {
        Map<String, Object> row = base(component, instance, relativeMetric, bucketMs);
        row.put("cnt", cnt);
        return row;
    }

    private static Map<String, Object> base(
            String component, String instance, String relativeMetric, long bucketMs) {
        Map<String, Object> row = new HashMap<>(12);
        MetricRowTimeUtil.putTsAndMetricTime(row, bucketMs);
        row.put("component", component);
        row.put("instance", instance);
        row.put("metric", PlatformMetricNames.qualify(component, relativeMetric));
        row.put("dim", "");
        return row;
    }
}
