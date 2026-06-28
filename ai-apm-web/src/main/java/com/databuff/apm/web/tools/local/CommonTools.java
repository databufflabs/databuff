package com.databuff.apm.web.tools.local;

import com.databuff.apm.common.time.ApmTimeZones;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Lazy
public class CommonTools {

    private static final long ONE_MINUTE_MS = 60_000L;
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final ObjectMapper objectMapper;

    public CommonTools(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Tool(description = "Get current query time range from now backwards. Returns fromTime/toTime in yyyy-MM-dd HH:mm:ss.")
    public Map<String, String> getCurrentTimeRange(
            @ToolParam(name = "rangeMinutes", required = false, description = "Lookback minutes, default 10, minimum 1")
            Integer rangeMinutes) {
        int minutes = rangeMinutes == null || rangeMinutes < 1 ? 10 : rangeMinutes;
        long endTime = System.currentTimeMillis() / ONE_MINUTE_MS * ONE_MINUTE_MS;
        return timeRange(endTime - minutes * ONE_MINUTE_MS, endTime);
    }

    @Tool(description = "Get query time range around a HH:mm target time. from = target - 9 minutes, to = target + 1 minute. Returns yyyy-MM-dd HH:mm:ss.")
    public Map<String, String> getTimeRangeAroundTime(
            @ToolParam(name = "targetTime", description = "Target time in HH:mm, for example 11:34")
            String targetTime) {
        if (targetTime == null || targetTime.isBlank()) {
            throw new IllegalArgumentException("targetTime is required");
        }
        LocalTime time;
        try {
            time = LocalTime.parse(targetTime.trim(), HH_MM);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("targetTime format must be HH:mm, for example 11:34");
        }
        long targetMillis = LocalDate.now(ApmTimeZones.SHANGHAI)
                .atTime(time)
                .atZone(ApmTimeZones.SHANGHAI)
                .toInstant()
                .toEpochMilli();
        return timeRange(targetMillis - 9 * ONE_MINUTE_MS, targetMillis + ONE_MINUTE_MS);
    }

    @Tool(converter = PlainTextToolResultConverter.class, description = "Draw multiple trend line charts from already queried metric data. Pass all chart specs at once; each chart contains title, labels, values, seriesName, and unit.")
    public String drawTrendCharts(
            @ToolParam(name = "charts", description = "Trend chart specs. Each item contains title, labels, values, seriesName, and unit. labels are x-axis values and values are y-axis numbers.")
            List<TrendChartSpec> charts) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> chartResults = new ArrayList<>();
        result.put("chartType", "trendBatch");
        result.put("charts", chartResults);
        if (charts == null || charts.isEmpty()) {
            result.put("success", false);
            result.put("count", 0);
            result.put("error", "charts is empty");
            return json(result);
        }

        boolean allSuccess = true;
        for (int i = 0; i < charts.size(); i++) {
            TrendChartSpec chart = charts.get(i);
            Map<String, Object> chartResult = chart == null
                    ? failedTrendChart("chart item is null")
                    : drawTrendChartBody(chart);
            chartResult.put("index", i);
            chartResults.add(chartResult);
            if (!Boolean.TRUE.equals(chartResult.get("success"))) {
                allSuccess = false;
            }
        }
        result.put("success", allSuccess);
        result.put("count", chartResults.size());
        return json(result);
    }

    private static Map<String, String> timeRange(long fromMillis, long toMillis) {
        return Map.of(
                "fromTime", ApmTimeZones.WALL_CLOCK.format(Instant.ofEpochMilli(fromMillis)),
                "toTime", ApmTimeZones.WALL_CLOCK.format(Instant.ofEpochMilli(toMillis)));
    }

    private static Map<String, Object> drawTrendChartBody(TrendChartSpec chart) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("chartType", "trend");
        result.put("title", firstNonBlank(chart.getTitle(), "趋势图"));
        result.put("labels", chart.getLabels() == null ? List.of() : chart.getLabels());
        result.put("values", chart.getValues() == null ? List.of() : chart.getValues());
        result.put("seriesName", firstNonBlank(chart.getSeriesName(), "Series"));
        result.put("unit", chart.getUnit() == null ? "" : chart.getUnit().trim());
        return result;
    }

    private static Map<String, Object> failedTrendChart(String error) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("chartType", "trend");
        result.put("error", error);
        return result;
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first.trim();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }
}
