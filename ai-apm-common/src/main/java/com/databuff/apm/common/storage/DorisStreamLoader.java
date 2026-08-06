package com.databuff.apm.common.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Doris FE/BE HTTP Stream Load ({@code PUT /api/{db}/{table}/_stream_load}).
 * Follows FE→BE redirects manually so {@code Authorization} is preserved (unlike
 * {@link HttpClient.Redirect#ALWAYS} which strips it on cross-host redirects).
 * <p>
 * When {@link DorisConnectionConfig#preferDirectBeStreamLoad()} is true, loads go
 * directly to configured BE HTTP endpoints (comma-separated multi-BE supported) with
 * round-robin + health failover. Otherwise loads hit FE HTTP (also multi-FE capable)
 * and follow 307 to BE.
 * <p>
 * Body is streamed as NDJSON via {@link NdjsonRowsInputStream} so rows can encode lazily
 * and are not joined into one contiguous {@code byte[]} before send.
 */
public final class DorisStreamLoader {

    private static final Logger log = LoggerFactory.getLogger(DorisStreamLoader.class);
    private static final Duration FE_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration BE_TIMEOUT = Duration.ofSeconds(30);

    private final DorisConnectionConfig config;
    private final String username;
    private final String password;
    private final HttpClient httpClient;
    private final DorisEndpointRouter beRouter;
    private final DorisEndpointRouter feRouter;

    public DorisStreamLoader(DorisConnectionConfig config, String username, String password) {
        this.config = Objects.requireNonNull(config);
        this.username = username == null ? "root" : username;
        this.password = password == null ? "" : password;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        List<DorisHttpEndpoint> beEndpoints = config.beHttpEndpoints();
        this.beRouter = beEndpoints.isEmpty() ? null : new DorisEndpointRouter(beEndpoints);
        this.feRouter = new DorisEndpointRouter(config.feHttpEndpoints());
    }

    public StreamLoadResult loadJsonLines(String database, String table, byte[] jsonLines) throws IOException {
        return loadJsonLines(database, table, jsonLines, DorisStreamLoadProfile.forTable(table));
    }

    public StreamLoadResult loadJsonLines(
            String database, String table, byte[] jsonLines, DorisStreamLoadProfile profile) throws IOException {
        Objects.requireNonNull(jsonLines);
        return loadNdjsonRows(database, table, List.of(DorisJsonRow.ofBytes(jsonLines)), profile);
    }

    public StreamLoadResult loadNdjsonRows(String database, String table, List<? extends DorisJsonRow> rows)
            throws IOException {
        return loadNdjsonRows(database, table, rows, DorisStreamLoadProfile.forTable(table));
    }

    public StreamLoadResult loadNdjsonRows(
            String database,
            String table,
            List<? extends DorisJsonRow> rows,
            DorisStreamLoadProfile profile) throws IOException {
        Objects.requireNonNull(database);
        Objects.requireNonNull(table);
        Objects.requireNonNull(rows);
        DorisStreamLoadProfile effective = profile == null ? DorisStreamLoadProfile.forTable(table) : profile;
        String label = "apm_" + UUID.randomUUID().toString().replace("-", "");
        String auth = basicAuth();
        if (config.preferDirectBeStreamLoad()) {
            return loadDirectBe(database, table, rows, effective, label, auth);
        }
        return loadViaFe(database, table, rows, effective, label, auth);
    }

    private StreamLoadResult loadDirectBe(
            String database,
            String table,
            List<? extends DorisJsonRow> rows,
            DorisStreamLoadProfile profile,
            String label,
            String auth) throws IOException {
        Objects.requireNonNull(beRouter);
        int attempts = beRouter.all().size();
        IOException lastIo = null;
        StreamLoadResult lastFail = null;
        for (int i = 0; i < attempts; i++) {
            beRouter.maybeProbeUnhealthy(httpClient, auth);
            DorisHttpEndpoint endpoint = beRouter.pick();
            String beUrl = endpoint.httpBaseUrl() + "/api/" + database + "/" + table + "/_stream_load";
            try {
                HttpResponse<String> beResponse = putNdjson(beUrl, label, auth, rows, profile, BE_TIMEOUT);
                StreamLoadResult result = toResult(beResponse);
                if (result.success()) {
                    beRouter.recordSuccess(endpoint);
                    return result;
                }
                beRouter.recordFailure(endpoint);
                lastFail = result;
            } catch (IOException e) {
                beRouter.recordFailure(endpoint);
                lastIo = e;
                log.warn("Stream load IO failure on BE {}: {}", endpoint.authority(), e.toString());
            }
        }
        if (lastIo != null && lastFail == null) {
            throw lastIo;
        }
        return lastFail != null ? lastFail : new StreamLoadResult(false, 0, "no BE endpoint available");
    }

    private StreamLoadResult loadViaFe(
            String database,
            String table,
            List<? extends DorisJsonRow> rows,
            DorisStreamLoadProfile profile,
            String label,
            String auth) throws IOException {
        int attempts = feRouter.all().size();
        IOException lastIo = null;
        StreamLoadResult lastFail = null;
        for (int i = 0; i < attempts; i++) {
            feRouter.maybeProbeUnhealthy(httpClient, auth);
            DorisHttpEndpoint fe = feRouter.pick();
            String feUrl = fe.httpBaseUrl() + "/api/" + database + "/" + table + "/_stream_load";
            try {
                HttpResponse<String> feResponse = putNdjson(feUrl, label, auth, rows, profile, FE_TIMEOUT);
                int status = feResponse.statusCode();
                if (status == 307 || status == 308) {
                    String location = feResponse.headers().firstValue("Location").orElse("");
                    if (location.isBlank()) {
                        feRouter.recordFailure(fe);
                        lastFail = new StreamLoadResult(false, status, "Stream load redirect missing Location header");
                        continue;
                    }
                    String beUrl = rewriteRedirectLocation(location);
                    HttpResponse<String> beResponse = putNdjson(beUrl, label, auth, rows, profile, BE_TIMEOUT);
                    StreamLoadResult result = toResult(beResponse);
                    if (result.success()) {
                        feRouter.recordSuccess(fe);
                        return result;
                    }
                    feRouter.recordFailure(fe);
                    lastFail = result;
                    continue;
                }
                StreamLoadResult result = toResult(feResponse);
                if (result.success()) {
                    feRouter.recordSuccess(fe);
                    return result;
                }
                feRouter.recordFailure(fe);
                lastFail = result;
            } catch (IOException e) {
                feRouter.recordFailure(fe);
                lastIo = e;
                log.warn("Stream load IO failure on FE {}: {}", fe.authority(), e.toString());
            }
        }
        if (lastIo != null && lastFail == null) {
            throw lastIo;
        }
        return lastFail != null ? lastFail : new StreamLoadResult(false, 0, "no FE endpoint available");
    }

    private StreamLoadResult toResult(HttpResponse<String> response) {
        boolean ok = response.statusCode() == 200 && isSuccessBody(response.body());
        if (!ok) {
            log.warn("Stream load failed status={} body={}", response.statusCode(), response.body());
        }
        return new StreamLoadResult(ok, response.statusCode(), response.body());
    }

    private HttpResponse<String> putNdjson(
            String url,
            String label,
            String auth,
            List<? extends DorisJsonRow> rows,
            DorisStreamLoadProfile profile,
            Duration timeout) throws IOException {
        // Materialize NDJSON so the request has Content-Length.
        // Chunked ofInputStream fails on Doris FE/BE ("There is no 100-continue header").
        // Do NOT set Expect:100-continue — java.net.http.HttpClient rejects it as a
        // restricted header (IllegalArgumentException). Content-Length alone is enough.
        byte[] payload = materializeNdjson(rows);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Authorization", auth)
                .header("label", label)
                .header("format", "json")
                .header("read_json_by_line", "true")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(payload));
        if (profile != null) {
            for (Map.Entry<String, String> header : profile.headers().entrySet()) {
                builder.header(header.getKey(), header.getValue());
            }
        }
        try {
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Stream load interrupted", e);
        }
    }

    private static byte[] materializeNdjson(List<? extends DorisJsonRow> rows) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.max(256, rows.size() * 64));
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                out.write('\n');
            }
            rows.get(i).writeTo(out);
        }
        return out.toByteArray();
    }

    String rewriteRedirectLocation(String location) {
        if (!config.rewritesBeRedirect()) {
            return location;
        }
        try {
            URI uri = URI.create(location);
            String targetHost = config.effectiveBeHttpHost();
            int targetPort = config.effectiveBeHttpPort();
            if (targetHost.equals(uri.getHost()) && targetPort == uri.getPort()) {
                return location;
            }
            URI rewritten = new URI(
                    uri.getScheme(),
                    null,
                    targetHost,
                    targetPort,
                    uri.getPath(),
                    uri.getQuery(),
                    uri.getFragment());
            log.debug("Rewrote stream-load redirect {} -> {}", location, rewritten);
            return rewritten.toString();
        } catch (URISyntaxException e) {
            log.warn("Could not rewrite stream-load redirect {}: {}", location, e.getMessage());
            return location;
        }
    }

    static boolean isSuccessBody(String body) {
        return body != null && body.contains("\"Status\"") && body.contains("\"Success\"");
    }

    private String basicAuth() {
        String token = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    /** Visible for tests. */
    DorisEndpointRouter beRouterForTest() {
        return beRouter;
    }

    public record StreamLoadResult(boolean success, int httpStatus, String body) {
    }
}
