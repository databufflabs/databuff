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

    public DorisStreamLoader(DorisConnectionConfig config, String username, String password) {
        this.config = Objects.requireNonNull(config);
        this.username = username == null ? "root" : username;
        this.password = password == null ? "" : password;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
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
            String beUrl = "http://" + config.effectiveBeHttpHost() + ":" + config.beHttpPort()
                    + "/api/" + database + "/" + table + "/_stream_load";
            HttpResponse<String> beResponse = putNdjson(beUrl, label, auth, rows, effective, BE_TIMEOUT);
            return toResult(beResponse);
        }
        String feUrl = "http://" + config.feHost() + ":" + config.httpPort()
                + "/api/" + database + "/" + table + "/_stream_load";
        HttpResponse<String> feResponse = putNdjson(feUrl, label, auth, rows, effective, FE_TIMEOUT);
        int status = feResponse.statusCode();
        if (status == 307 || status == 308) {
            String location = feResponse.headers().firstValue("Location").orElse("");
            if (location.isBlank()) {
                throw new IOException("Stream load redirect missing Location header");
            }
            String beUrl = rewriteRedirectLocation(location);
            HttpResponse<String> beResponse = putNdjson(beUrl, label, auth, rows, effective, BE_TIMEOUT);
            return toResult(beResponse);
        }
        return toResult(feResponse);
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
        // Supplier creates a fresh stream per subscribe (HttpClient may retry).
        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.ofInputStream(
                () -> new NdjsonRowsInputStream(rows));
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Authorization", auth)
                .header("label", label)
                .header("format", "json")
                .header("read_json_by_line", "true")
                .PUT(body);
        if (profile != null) {
            for (Map.Entry<String, String> header : profile.headers().entrySet()) {
                builder.header(header.getKey(), header.getValue());
            }
        }
        // Chunked streaming body + Expect: 100-continue breaks on FE 307 with JDK HttpClient.
        // Direct-BE path already skips expectContinue; keep FE redirects on a plain PUT.
        try {
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Stream load interrupted", e);
        }
    }

    String rewriteRedirectLocation(String location) {
        if (!config.rewritesBeRedirect()) {
            return location;
        }
        try {
            URI uri = URI.create(location);
            String targetHost = config.effectiveBeHttpHost();
            if (targetHost.equals(uri.getHost()) && config.beHttpPort() == uri.getPort()) {
                return location;
            }
            URI rewritten = new URI(
                    uri.getScheme(),
                    null,
                    targetHost,
                    config.beHttpPort(),
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

    public record StreamLoadResult(boolean success, int httpStatus, String body) {
    }
}
