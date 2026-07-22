package com.databuff.apm.web.portal;

import com.databuff.apm.web.log.LogQueryService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LogPortalService {

    private final LogQueryService logQueryService;

    public LogPortalService(LogQueryService logQueryService) {
        this.logQueryService = logQueryService;
    }

    public Map<String, Object> search(Map<String, Object> body) {
        int offset = ServicePortalService.intValue(body.get("offset"), 0);
        int size = Math.min(Math.max(ServicePortalService.intValue(body.get("size"), 50), 1), 1000);
        LogQueryService.LogSearchCriteria criteria = toCriteria(body, offset, size);

        try {
            List<Map<String, Object>> data = logQueryService.search(criteria);
            long total = logQueryService.count(criteria);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", 200);
            response.put("message", "success");
            response.put("data", data);
            response.put("total", total);
            response.put("offset", offset);
            return response;
        } catch (Exception ignored) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", 200);
            response.put("message", "success");
            response.put("data", List.of());
            response.put("total", 0L);
            response.put("offset", offset);
            return response;
        }
    }

    public Map<String, Object> trend(Map<String, Object> body) {
        int interval = ServicePortalService.intValue(body.get("interval"), 60);
        LogQueryService.LogSearchCriteria criteria = toCriteria(body, 0, 1);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", 200);
        response.put("message", "success");
        try {
            response.put("data", logQueryService.trend(criteria, interval));
        } catch (Exception ignored) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("logCnts", Map.of());
            data.put("severityCnts", Map.of());
            response.put("data", data);
        }
        return response;
    }

    public Map<String, Object> conditions(Map<String, Object> body) {
        LogQueryService.LogSearchCriteria criteria = toCriteria(body, 0, 1);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", 200);
        response.put("message", "success");
        try {
            response.put("data", logQueryService.listConditions(criteria));
        } catch (Exception ignored) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("hosts", List.of());
            data.put("services", List.of());
            data.put("serviceInstances", List.of());
            data.put("severities", List.of());
            response.put("data", data);
        }
        return response;
    }

    public Map<String, Object> detail(Map<String, Object> body) {
        Map<String, Object> request = body == null ? Map.of() : body;
        long timeNs = parseTimeNs(request.get("timeNs"));
        String serviceId = ServicePortalService.stringValue(request.get("serviceId"), null);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", 200);
        response.put("message", "success");
        try {
            response.put("data", logQueryService.detail(timeNs, serviceId));
        } catch (Exception ignored) {
            response.put("data", Map.of());
        }
        return response;
    }

    private static long parseTimeNs(Object value) {
        if (value == null) {
            return 0L;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || "NaN".equalsIgnoreCase(text) || "null".equalsIgnoreCase(text)) {
            return 0L;
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static LogQueryService.LogSearchCriteria toCriteria(Map<String, Object> body, int offset, int size) {
        long now = System.currentTimeMillis();
        Map<String, Object> request = body == null ? Map.of() : body;
        long from = parseTimeMillis(request.get("fromTimeNs"), PortalTimeParser.rangeFrom(request, now - 3_600_000L));
        long to = parseTimeMillis(request.get("toTimeNs"), PortalTimeParser.rangeTo(request, now));
        if (from > to) {
            long swap = from;
            from = to;
            to = swap;
        }
        return new LogQueryService.LogSearchCriteria(
                ServicePortalService.stringValue(request.get("traceId"), null),
                ServicePortalService.stringValue(request.get("spanId"), null),
                ServicePortalService.stringValue(request.get("serviceId"), null),
                stringList(request.get("serviceIds")),
                stringList(request.get("services")),
                stringList(request.get("serviceInstances")),
                stringList(request.get("hosts")),
                stringList(request.get("severities")),
                ServicePortalService.stringValue(request.get("query"), null),
                from,
                to,
                Math.max(0, offset),
                size);
    }

    private static long parseTimeMillis(Object value, long fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || "NaN".equalsIgnoreCase(text) || "null".equalsIgnoreCase(text)) {
            return fallback;
        }
        if (text.chars().allMatch(Character::isDigit) && text.length() > 13) {
            try {
                return Long.parseLong(text) / 1_000_000L;
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        try {
            return PortalTimeParser.parseMillis(value, fallback);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    String text = String.valueOf(item).trim();
                    if (!text.isEmpty()) {
                        out.add(text);
                    }
                }
            }
            return out;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? List.of() : List.of(text);
    }
}
