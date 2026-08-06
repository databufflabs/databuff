package com.databuff.apm.common.platform;

import java.util.List;
import java.util.Map;

/** Sink for one-minute platform self-metric rows (fail-soft; closed-minute ts). */
@FunctionalInterface
public interface PlatformMetricExporter {

    /**
     * Persist flushed rows. Implementations must not throw to callers of the scheduler;
     * log and swallow internally when possible.
     *
     * @param rows NDJSON-ready maps matching {@code metric_platform} columns
     */
    void export(List<Map<String, Object>> rows);
}
