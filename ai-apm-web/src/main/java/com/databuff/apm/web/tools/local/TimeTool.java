package com.databuff.apm.web.tools.local;

import com.databuff.apm.common.time.ApmTimeZones;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

@Component
@Lazy
public class TimeTool {

    private static final long ONE_MINUTE_MS = 60_000L;
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

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

    private static Map<String, String> timeRange(long fromMillis, long toMillis) {
        return Map.of(
                "fromTime", ApmTimeZones.WALL_CLOCK.format(Instant.ofEpochMilli(fromMillis)),
                "toTime", ApmTimeZones.WALL_CLOCK.format(Instant.ofEpochMilli(toMillis)));
    }
}
