package com.databuff.apm.common.query;

import com.databuff.apm.common.time.ApmTimeZones;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TimeSeriesFillUtilTest {

    @Test
    void fillsMissingMetricBucketsWithNull() {
        long from = 1_710_000_000_000L;
        long to = from + 180_000L;

        List<ApmQueryModels.MetricSeriesPoint> filled = TimeSeriesFillUtil.fillMetricSeries(
                List.of(new ApmQueryModels.MetricSeriesPoint(1_710_000_000L, 12.0)),
                from,
                to,
                60);

        assertThat(filled).hasSize(3);
        assertThat(filled.get(0)).isEqualTo(new ApmQueryModels.MetricSeriesPoint(1_710_000_000L, 12.0));
        assertThat(filled.get(1).value()).isNull();
        assertThat(filled.get(2).value()).isNull();
    }

    @Test
    void exclusiveEndTimeExcludesBoundaryBucket() {
        long from = java.time.LocalDateTime.of(2026, 6, 5, 20, 0, 0)
                .atZone(ApmTimeZones.SHANGHAI)
                .toInstant()
                .toEpochMilli();
        long to = java.time.LocalDateTime.of(2026, 6, 5, 20, 35, 0)
                .atZone(ApmTimeZones.SHANGHAI)
                .toInstant()
                .toEpochMilli();
        long lastBucket = java.time.LocalDateTime.of(2026, 6, 5, 20, 34, 0)
                .atZone(ApmTimeZones.SHANGHAI)
                .toInstant()
                .getEpochSecond();

        List<ApmQueryModels.MetricSeriesPoint> filled = TimeSeriesFillUtil.fillMetricSeries(List.of(), from, to, 60);

        assertThat(filled.get(filled.size() - 1).epochSeconds()).isEqualTo(lastBucket);
        assertThat(filled).noneMatch(point -> point.epochSeconds()
                == java.time.LocalDateTime.of(2026, 6, 5, 20, 35, 0)
                .atZone(ApmTimeZones.SHANGHAI)
                .toInstant()
                .getEpochSecond());
    }

    @Test
    void fillsStringKeyMapAcrossRangeWithNullGaps() {
        Map<String, Number> filled = TimeSeriesFillUtil.fillStringKeyMap(
                Map.of("1710000000000", 5),
                1_710_000_000_000L,
                1_710_000_120_000L,
                60);

        assertThat(filled).containsEntry("1710000000000", 5);
        assertThat(filled.get("1710000060000")).isNull();
        assertThat(filled).hasSize(2);
    }

    @Test
    void fillsGroupedTimeSeriesRowsWithNullGaps() {
        long from = 1_710_000_000_000L;
        long to = from + 180_000L;

        List<Map<String, Object>> filled = TimeSeriesFillUtil.fillGroupedTimeSeriesRows(
                List.of(Map.of("epoch_sec", 1_710_000_000L, "service", "order", "reqCount", 12)),
                from,
                to,
                60,
                "epoch_sec",
                List.of("service"),
                List.of("reqCount"));

        assertThat(filled).hasSize(3);
        assertThat(filled.get(0)).containsEntry("epoch_sec", 1_710_000_000L)
                .containsEntry("service", "order")
                .containsEntry("reqCount", 12);
        assertThat(filled.get(1)).containsEntry("epoch_sec", 1_710_000_060L)
                .containsEntry("service", "order")
                .containsEntry("reqCount", null);
        assertThat(filled.get(2)).containsEntry("reqCount", null);
    }

    @Test
    void fillsEmptyGroupedTimeSeriesAcrossRange() {
        long from = 1_710_000_000_000L;
        long to = from + 60_000L;

        List<Map<String, Object>> filled = TimeSeriesFillUtil.fillGroupedTimeSeriesRows(
                List.of(),
                from,
                to,
                60,
                "epoch_sec",
                List.of(),
                List.of("reqCount"));

        assertThat(filled).hasSize(1);
        assertThat(filled.get(0)).containsEntry("epoch_sec", 1_710_000_000L)
                .containsEntry("reqCount", null);
    }

    @Test
    void bucketAlignmentHelpersAndEpochMsFill() {
        assertThat(TimeSeriesFillUtil.bucketSec(45)).isEqualTo(60);
        assertThat(TimeSeriesFillUtil.alignBucketEpochSec(1_710_000_015_000L, 60))
                .isEqualTo(1_710_000_000L);
        assertThat(TimeSeriesFillUtil.alignBucketEpochSecWithStep(1_710_000_015_000L, 60L))
                .isEqualTo(1_710_000_000L);
        List<List<Object>> filled = TimeSeriesFillUtil.fillEpochMsValues(
                java.util.Map.of(1_710_000_000L, 5),
                1_710_000_000_000L,
                1_710_000_120_000L,
                60,
                v -> v);
        assertThat(filled).hasSizeGreaterThanOrEqualTo(2);
    }
}
