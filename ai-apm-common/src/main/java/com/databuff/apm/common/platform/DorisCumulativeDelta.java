package com.databuff.apm.common.platform;

import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Converts Prometheus-style cumulative counters into per-scrape deltas.
 * First sample for a series is skipped (no baseline). Counter resets yield {@code 0}.
 */
public final class DorisCumulativeDelta {

    private final ConcurrentHashMap<String, Double> lastAbsolute = new ConcurrentHashMap<>();

    /**
     * @param seriesKey stable key such as {@code host + '\\0' + relativeMetric}
     * @return empty on first observation; otherwise {@code max(0, absolute - previous)}
     */
    public OptionalDouble delta(String seriesKey, double absolute) {
        if (seriesKey == null || seriesKey.isBlank() || !Double.isFinite(absolute)) {
            return OptionalDouble.empty();
        }
        Double prev = lastAbsolute.put(seriesKey, absolute);
        if (prev == null) {
            return OptionalDouble.empty();
        }
        double d = absolute - prev;
        if (d < 0) {
            return OptionalDouble.of(0.0);
        }
        return OptionalDouble.of(d);
    }

    /** Visible for tests. */
    void clear() {
        lastAbsolute.clear();
    }
}
