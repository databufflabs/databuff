package com.databuff.apm.common.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Doris FE/BE HTTP endpoint ({@code host} or {@code host:port}).
 * Comma-separated lists are parsed by {@link #parseList(String, int)}.
 */
public record DorisHttpEndpoint(String host, int port) {

    public DorisHttpEndpoint {
        Objects.requireNonNull(host, "host");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("invalid port: " + port);
        }
    }

    public String authority() {
        return host + ":" + port;
    }

    public String httpBaseUrl() {
        return "http://" + authority();
    }

    /**
     * Parse {@code host1,host2:8041,host3} into endpoints.
     * Entries without an explicit port use {@code defaultPort}.
     */
    public static List<DorisHttpEndpoint> parseList(String raw, int defaultPort) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String[] parts = raw.split(",");
        List<DorisHttpEndpoint> out = new ArrayList<>(parts.length);
        for (String part : parts) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            out.add(parseOne(token, defaultPort));
        }
        return List.copyOf(out);
    }

    static DorisHttpEndpoint parseOne(String token, int defaultPort) {
        int colon = token.lastIndexOf(':');
        if (colon <= 0 || colon == token.length() - 1) {
            return new DorisHttpEndpoint(token, defaultPort);
        }
        String host = token.substring(0, colon).trim();
        String portPart = token.substring(colon + 1).trim();
        // IPv6 without brackets is not supported; host:port only.
        if (host.isEmpty() || !portPart.chars().allMatch(Character::isDigit)) {
            return new DorisHttpEndpoint(token, defaultPort);
        }
        return new DorisHttpEndpoint(host, Integer.parseInt(portPart));
    }
}
