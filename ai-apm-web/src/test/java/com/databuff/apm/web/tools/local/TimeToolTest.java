package com.databuff.apm.web.tools.local;

import com.databuff.apm.common.time.ApmTimeZones;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeToolTest {

    private static final long ONE_MINUTE_MS = 60_000L;

    private final TimeTool tool = new TimeTool();

    @Test
    void currentTimeRangeDefaultsToTenMinutes() {
        Map<String, String> range = tool.getCurrentTimeRange(null);

        long from = ApmTimeZones.wallClockToEpochMilli(range.get("fromTime"));
        long to = ApmTimeZones.wallClockToEpochMilli(range.get("toTime"));
        assertThat(to - from).isEqualTo(10 * ONE_MINUTE_MS);
        assertThat(to).isEqualTo(floorToMinute(System.currentTimeMillis()) - ONE_MINUTE_MS);
    }

    @Test
    void currentTimeRangeUsesProvidedMinutes() {
        Map<String, String> range = tool.getCurrentTimeRange(5);

        long from = ApmTimeZones.wallClockToEpochMilli(range.get("fromTime"));
        long to = ApmTimeZones.wallClockToEpochMilli(range.get("toTime"));
        assertThat(to - from).isEqualTo(5 * ONE_MINUTE_MS);
    }

    @Test
    void currentTimeRangeClampsTooSmallRange() {
        Map<String, String> range = tool.getCurrentTimeRange(0);

        long from = ApmTimeZones.wallClockToEpochMilli(range.get("fromTime"));
        long to = ApmTimeZones.wallClockToEpochMilli(range.get("toTime"));
        assertThat(to - from).isEqualTo(10 * ONE_MINUTE_MS);
    }

    @Test
    void timeRangeAroundTargetTime() {
        Map<String, String> range = tool.getTimeRangeAroundTime("11:34");

        long expectedTarget = LocalDate.now(ApmTimeZones.SHANGHAI)
                .atTime(LocalTime.parse("11:34"))
                .atZone(ApmTimeZones.SHANGHAI)
                .toInstant()
                .toEpochMilli();
        long from = ApmTimeZones.wallClockToEpochMilli(range.get("fromTime"));
        long to = ApmTimeZones.wallClockToEpochMilli(range.get("toTime"));
        assertThat(from).isEqualTo(expectedTarget - 9 * ONE_MINUTE_MS);
        assertThat(to).isEqualTo(expectedTarget + ONE_MINUTE_MS);
    }

    @Test
    void rejectsNullTargetTime() {
        assertThatThrownBy(() -> tool.getTimeRangeAroundTime(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetTime is required");
    }

    @Test
    void rejectsBlankTargetTime() {
        assertThatThrownBy(() -> tool.getTimeRangeAroundTime("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetTime is required");
    }

    @Test
    void rejectsMalformedTargetTime() {
        assertThatThrownBy(() -> tool.getTimeRangeAroundTime("1134"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("format must be HH:mm");
    }

    private static long floorToMinute(long millis) {
        return (millis / ONE_MINUTE_MS) * ONE_MINUTE_MS;
    }
}
