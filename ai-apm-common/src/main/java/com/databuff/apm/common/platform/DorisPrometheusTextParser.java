package com.databuff.apm.common.platform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal Prometheus text exposition parser for Doris FE/BE {@code /metrics}.
 * Captures {@code # TYPE name type} so callers can delta counters.
 */
public final class DorisPrometheusTextParser {

    private DorisPrometheusTextParser() {
    }

    public enum MetricType {
        COUNTER,
        GAUGE,
        UNKNOWN;

        static MetricType from(String raw) {
            if (raw == null || raw.isBlank()) {
                return UNKNOWN;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "counter" -> COUNTER;
                case "gauge" -> GAUGE;
                default -> UNKNOWN;
            };
        }
    }

    public record Sample(String name, Map<String, String> labels, double value, MetricType type) {
        public Sample {
            Objects.requireNonNull(name, "name");
            type = type == null ? MetricType.UNKNOWN : type;
            labels = labels == null || labels.isEmpty()
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(labels));
        }

        public boolean isCounter() {
            return type == MetricType.COUNTER;
        }

        public String label(String key) {
            return labels.getOrDefault(key, "");
        }
    }

    public static List<Sample> parse(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        List<Sample> out = new ArrayList<>();
        Map<String, MetricType> types = new LinkedHashMap<>();
        for (String raw : body.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                parseTypeDirective(line, types);
                continue;
            }
            Sample sample = parseLine(line, types);
            if (sample != null) {
                out.add(sample);
            }
        }
        return out;
    }

    static void parseTypeDirective(String line, Map<String, MetricType> types) {
        // # TYPE metric_name counter
        String rest = line.substring(1).trim();
        if (!rest.regionMatches(true, 0, "TYPE", 0, 4)) {
            return;
        }
        rest = rest.substring(4).trim();
        int sp = indexOfSampleSeparator(rest, 0);
        if (sp < 0) {
            return;
        }
        String name = rest.substring(0, sp).trim();
        String type = rest.substring(sp).trim();
        int sp2 = indexOfSampleSeparator(type, 0);
        if (sp2 >= 0) {
            type = type.substring(0, sp2).trim();
        }
        if (!name.isEmpty()) {
            types.put(name, MetricType.from(type));
        }
    }

    static Sample parseLine(String line, Map<String, MetricType> types) {
        int brace = line.indexOf('{');
        String name;
        Map<String, String> labels = Map.of();
        String rest;
        if (brace < 0) {
            int sp = indexOfSampleSeparator(line, 0);
            if (sp < 0) {
                return null;
            }
            name = line.substring(0, sp).trim();
            rest = line.substring(sp).trim();
        } else {
            name = line.substring(0, brace).trim();
            int close = findClosingBrace(line, brace);
            if (close < 0) {
                return null;
            }
            labels = parseLabels(line.substring(brace + 1, close));
            rest = line.substring(close + 1).trim();
        }
        if (name.isEmpty() || rest.isEmpty()) {
            return null;
        }
        int sp = indexOfSampleSeparator(rest, 0);
        String valuePart = sp < 0 ? rest : rest.substring(0, sp).trim();
        try {
            double value = Double.parseDouble(valuePart);
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return null;
            }
            MetricType type = types.getOrDefault(name, MetricType.UNKNOWN);
            return new Sample(name, labels, value, type);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int findClosingBrace(String line, int openIdx) {
        boolean inQuote = false;
        for (int i = openIdx + 1; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"' && line.charAt(i - 1) != '\\') {
                inQuote = !inQuote;
            } else if (c == '}' && !inQuote) {
                return i;
            }
        }
        return -1;
    }

    private static Map<String, String> parseLabels(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        Map<String, String> labels = new LinkedHashMap<>();
        int i = 0;
        int n = raw.length();
        while (i < n) {
            while (i < n && (raw.charAt(i) == ',' || Character.isWhitespace(raw.charAt(i)))) {
                i++;
            }
            if (i >= n) {
                break;
            }
            int eq = raw.indexOf('=', i);
            if (eq < 0) {
                break;
            }
            String key = raw.substring(i, eq).trim();
            i = eq + 1;
            while (i < n && Character.isWhitespace(raw.charAt(i))) {
                i++;
            }
            if (i >= n || raw.charAt(i) != '"') {
                break;
            }
            i++;
            StringBuilder value = new StringBuilder();
            while (i < n) {
                char c = raw.charAt(i);
                if (c == '\\' && i + 1 < n) {
                    value.append(raw.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    i++;
                    break;
                }
                value.append(c);
                i++;
            }
            if (!key.isEmpty()) {
                labels.put(key, value.toString());
            }
        }
        return labels;
    }

    private static int indexOfSampleSeparator(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
