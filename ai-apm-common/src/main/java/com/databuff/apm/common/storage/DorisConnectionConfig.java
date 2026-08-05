package com.databuff.apm.common.storage;

import java.util.ArrayList;
import java.util.List;

/**
 * Doris connection settings.
 * <p>
 * Host fields ({@code feHost} / {@code beHttpHost}) accept:
 * <ul>
 *   <li>{@code h1} — single host; port from the matching {@code *Port} field</li>
 *   <li>{@code h1,h2} — multi-host; shared port from the matching {@code *Port} field</li>
 *   <li>{@code h1:p1,h2:p2} — per-entry ports (omit port on an entry to fall back to {@code *Port})</li>
 * </ul>
 * Port field defaults when unset by env/YAML: FE query {@code 9030}, FE HTTP {@code 8030}, BE HTTP {@code 8040}.
 */
public record DorisConnectionConfig(
        String feHost,
        int queryPort,
        int httpPort,
        String beHttpHost,
        int beHttpPort) {

    public DorisConnectionConfig(String feHost, int queryPort, int httpPort) {
        this(feHost, queryPort, httpPort, null, defaultBeHttpPort());
    }

    public static DorisConnectionConfig fromEnv() {
        String host = System.getenv().getOrDefault("DORIS_FE_HOST", "127.0.0.1");
        int query = Integer.parseInt(System.getenv().getOrDefault("DORIS_FE_QUERY_PORT", "9030"));
        int http = Integer.parseInt(System.getenv().getOrDefault("DORIS_FE_HTTP_PORT", "8030"));
        String beHost = System.getenv("DORIS_BE_HTTP_HOST");
        if (beHost != null && beHost.isBlank()) {
            beHost = null;
        }
        return new DorisConnectionConfig(host, query, http, beHost, defaultBeHttpPort());
    }

    /** FE hostnames only (port suffixes stripped). */
    public List<String> feHosts() {
        List<DorisHttpEndpoint> eps = feJdbcEndpoints();
        List<String> names = new ArrayList<>(eps.size());
        for (DorisHttpEndpoint ep : eps) {
            names.add(ep.host());
        }
        return List.copyOf(names);
    }

    /**
     * FE JDBC endpoints. Default port = {@link #queryPort()} ({@code DORIS_FE_QUERY_PORT}, default 9030).
     * Supports {@code fe1} / {@code fe1,fe2} / {@code fe1:p1,fe2:p2}.
     */
    public List<DorisHttpEndpoint> feJdbcEndpoints() {
        String raw = (feHost == null || feHost.isBlank()) ? "127.0.0.1" : feHost;
        return DorisHttpEndpoint.parseList(raw, queryPort);
    }

    /**
     * FE HTTP endpoints (Stream Load via FE). Default port = {@link #httpPort()}
     * ({@code DORIS_FE_HTTP_PORT}, default 8030).
     * Supports {@code fe1} / {@code fe1,fe2} / {@code fe1:p1,fe2:p2}.
     */
    public List<DorisHttpEndpoint> feHttpEndpoints() {
        String raw = (feHost == null || feHost.isBlank()) ? "127.0.0.1" : feHost;
        return DorisHttpEndpoint.parseList(raw, httpPort);
    }

    /**
     * BE HTTP endpoints for direct Stream Load; empty when BE host is unset.
     * Default port = {@link #beHttpPort()} ({@code DORIS_BE_HTTP_PORT}, default 8040).
     * Supports {@code be1} / {@code be1,be2} / {@code be1:p1,be2:p2}.
     */
    public List<DorisHttpEndpoint> beHttpEndpoints() {
        if (beHttpHost == null || beHttpHost.isBlank()) {
            return List.of();
        }
        return DorisHttpEndpoint.parseList(beHttpHost, beHttpPort);
    }

    /** Host used when rewriting FE→BE stream-load redirects (first BE host, else first FE host). */
    public String effectiveBeHttpHost() {
        List<DorisHttpEndpoint> be = beHttpEndpoints();
        if (!be.isEmpty()) {
            return be.get(0).host();
        }
        return feJdbcEndpoints().get(0).host();
    }

    /**
     * Port used when rewriting FE→BE redirects.
     * First BE endpoint port when BE configured; otherwise {@link #beHttpPort()}.
     */
    public int effectiveBeHttpPort() {
        List<DorisHttpEndpoint> be = beHttpEndpoints();
        if (!be.isEmpty()) {
            return be.get(0).port();
        }
        return beHttpPort;
    }

    /** Stream load from outside the cluster must rewrite internal BE addresses. */
    public boolean rewritesBeRedirect() {
        return true;
    }

    /** When BE HTTP host(s) are configured, upload directly to BE (avoids FE 307 + HttpClient hang). */
    public boolean preferDirectBeStreamLoad() {
        return !beHttpEndpoints().isEmpty();
    }

    public String feJdbcUrl() {
        return jdbcUrl("") + "&connectTimeout=5000&socketTimeout=30000";
    }

    public String jdbcUrl(String database) {
        String dbPath = database == null ? "" : database;
        String params = "?useSSL=false&allowPublicKeyRetrieval=true";
        List<DorisHttpEndpoint> endpoints = feJdbcEndpoints();
        if (endpoints.size() == 1) {
            DorisHttpEndpoint ep = endpoints.get(0);
            return "jdbc:mysql://" + ep.host() + ":" + ep.port() + "/" + dbPath + params;
        }
        StringBuilder sb = new StringBuilder("jdbc:mysql:loadbalance://");
        for (int i = 0; i < endpoints.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            DorisHttpEndpoint ep = endpoints.get(i);
            sb.append(ep.host()).append(':').append(ep.port());
        }
        sb.append('/').append(dbPath).append(params);
        return sb.toString();
    }

    private static int defaultBeHttpPort() {
        return Integer.parseInt(System.getenv().getOrDefault("DORIS_BE_HTTP_PORT", "8040"));
    }
}
