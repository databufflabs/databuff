package com.databuff.apm.common.model;

import java.util.Arrays;
import java.util.Objects;

/**
 * Trace-derived or normalized metric before Doris aggregation.
 */
public final class OptimizedMetric {

    private int tsId;
    private long timestamp;
    private String measurement;
    private String[] tagValues = new String[0];
    private long[] fieldValues = new long[0];

    public int tsId() {
        return tsId;
    }

    public long timestamp() {
        return timestamp;
    }

    public String measurement() {
        return measurement;
    }

    public String[] tagValues() {
        return tagValues.clone();
    }

    /** Uncloned view for encode/merge hot paths; caller must not mutate. */
    public String[] tagValuesRef() {
        return tagValues;
    }

    public long[] fieldValues() {
        return fieldValues.clone();
    }

    /** Uncloned view for encode/merge hot paths; caller must not mutate. */
    public long[] fieldValuesRef() {
        return fieldValues;
    }

    public OptimizedMetric withTsId(int tsId) {
        OptimizedMetric copy = copy();
        copy.tsId = tsId;
        return copy;
    }

    public OptimizedMetric withTimestamp(long timestamp) {
        OptimizedMetric copy = copy();
        copy.timestamp = timestamp;
        return copy;
    }

    public OptimizedMetric withMeasurement(String measurement) {
        OptimizedMetric copy = copy();
        copy.measurement = measurement;
        return copy;
    }

    public OptimizedMetric withTagValues(String... tagValues) {
        OptimizedMetric copy = copy();
        copy.tagValues = tagValues == null ? new String[0] : tagValues.clone();
        return copy;
    }

    public OptimizedMetric withFieldValues(long... fieldValues) {
        OptimizedMetric copy = copy();
        copy.fieldValues = fieldValues == null ? new long[0] : fieldValues.clone();
        return copy;
    }

    public OptimizedMetric initTsId() {
        return withTsId(Objects.hash(measurement, Arrays.hashCode(tagValues)));
    }

    public OptimizedMetric merge(OptimizedMetric other) {
        if (isLegacyTraceFieldShape(fieldValues) && isLegacyTraceFieldShape(other.fieldValues)) {
            return withFieldValues(mergeLegacyTraceFields(
                    normalizeLegacyTraceFields(fieldValues),
                    normalizeLegacyTraceFields(other.fieldValues)));
        }
        if (fieldValues.length != other.fieldValues.length) {
            throw new IllegalArgumentException("field cardinality mismatch");
        }
        long[] merged = fieldValues.clone();
        long[] otherFields = other.fieldValues;
        for (int i = 0; i < merged.length; i++) {
            merged[i] += otherFields[i];
        }
        OptimizedMetric copy = copy();
        copy.fieldValues = merged;
        return copy;
    }

    /** Legacy trace metrics use (cnt, error, sumDuration[, maxDuration]). */
    private static boolean isLegacyTraceFieldShape(long[] fieldValues) {
        return fieldValues.length == 3 || fieldValues.length == 4;
    }

    private static long[] normalizeLegacyTraceFields(long[] fieldValues) {
        if (fieldValues.length >= 4) {
            return fieldValues.clone();
        }
        if (fieldValues.length == 3) {
            long maxDuration = fieldValues[0] == 1 ? fieldValues[2] : 0L;
            return new long[] {fieldValues[0], fieldValues[1], fieldValues[2], maxDuration};
        }
        return fieldValues.clone();
    }

    private static long[] mergeLegacyTraceFields(long[] left, long[] right) {
        return new long[] {
            left[0] + right[0],
            left[1] + right[1],
            left[2] + right[2],
            Math.max(left[3], right[3])
        };
    }

    private OptimizedMetric copy() {
        OptimizedMetric copy = new OptimizedMetric();
        copy.tsId = tsId;
        copy.timestamp = timestamp;
        copy.measurement = measurement;
        copy.tagValues = tagValues.clone();
        copy.fieldValues = fieldValues.clone();
        return copy;
    }
}
