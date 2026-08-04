package com.databuff.apm.web.monitor;

public final class ThresholdAlarmCheck {

    private ThresholdAlarmCheck() {
    }

    /**
     * Returns true when {@code actual} breaches {@code threshold} under the given comparator.
     * Supported values: {@code gt}, {@code gte}, {@code lt}, {@code lte}, plus the UI symbols
     * {@code >}, {@code >=}, {@code <}, {@code <=}. Unknown / blank comparators default to
     * greater-than (legacy behavior for built-in "过高" rules).
     */
    public static boolean breached(double actual, double threshold, String comparator) {
        return switch (EventRule.normalizeComparator(comparator)) {
            case EventRule.COMPARATOR_GTE -> actual >= threshold;
            case EventRule.COMPARATOR_LT -> actual < threshold;
            case EventRule.COMPARATOR_LTE -> actual <= threshold;
            default -> actual > threshold;
        };
    }

    /**
     * Picks the most severe breached band. Critical wins when both fire. Returns {@code null}
     * when neither band is configured/breached. {@link Double#NaN} means "band not set".
     */
    public static String resolveLevel(
            double actual, String comparator, double criticalThreshold, double warningThreshold) {
        boolean criticalBreached = !Double.isNaN(criticalThreshold)
                && breached(actual, criticalThreshold, comparator);
        if (criticalBreached) {
            return EventRule.LEVEL_CRITICAL;
        }
        boolean warningBreached = !Double.isNaN(warningThreshold)
                && breached(actual, warningThreshold, comparator);
        if (warningBreached) {
            return EventRule.LEVEL_WARNING;
        }
        return null;
    }

    /** Threshold value that corresponds to {@link #resolveLevel} for message formatting. */
    public static double resolveBreachedThreshold(
            String level, double criticalThreshold, double warningThreshold) {
        if (EventRule.LEVEL_WARNING.equals(level) && !Double.isNaN(warningThreshold)) {
            return warningThreshold;
        }
        return criticalThreshold;
    }
}
