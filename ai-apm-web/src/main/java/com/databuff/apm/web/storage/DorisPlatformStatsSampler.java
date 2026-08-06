package com.databuff.apm.web.storage;

import com.databuff.apm.common.cluster.coordination.ClusterInstanceCoordinator;
import com.databuff.apm.common.platform.DorisCumulativeDelta;
import com.databuff.apm.common.platform.DorisPrometheusTextParser;
import com.databuff.apm.common.platform.DorisSelfMonitorMapper;
import com.databuff.apm.common.platform.PlatformMetricNames;
import com.databuff.apm.common.platform.PlatformMetrics;
import com.databuff.apm.common.storage.DorisConnectionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Doris cluster self-metrics for {@link PlatformMetrics}.
 * <p>
 * Flow (Doris 4.1 has no {@code information_schema.*metrics} views):
 * <ol>
 *   <li>{@code SHOW BACKENDS} / {@code SHOW FRONTENDS} — discover nodes + alive/capacity gauges</li>
 *   <li>For each alive node, HTTP GET {@code http://{Host}:{HttpPort}/metrics}</li>
 *   <li>Map Ultra 自监控看板 series → {@code doris.<fe|be>.*}；Prometheus counter 存<strong>刮取间隔差值</strong>，
 *       gauge 存瞬时值（{@code instance}=SHOW Host；CPU {@code mode} 等进 {@code dim}）。
 *       CPU 在差值后按 mode 归一成<strong>占本间隔 CPU 时间的百分比</strong>（各 mode 之和 ≈ 100）</li>
 * </ol>
 * Only the web cluster leader polls. Gated by {@code apm.platform-metrics.doris-enabled}.
 * Sample cron defaults to second 50 of each minute so gauges are set before
 * {@link PlatformMetrics} clock-aligned flush (~:02) stamps the previous closed minute.
 */
@Component
@ConditionalOnProperty(name = "apm.platform-metrics.enabled", havingValue = "true", matchIfMissing = true)
public class DorisPlatformStatsSampler {

    private static final Logger log = LoggerFactory.getLogger(DorisPlatformStatsSampler.class);
    private static final int DIM_MAX = 128;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(3);

    private final DataSource dorisDataSource;
    private final DorisAvailability dorisAvailability;
    private final DorisConnectionConfig dorisConnectionConfig;
    private final ClusterInstanceCoordinator clusterCoordinator;
    private final boolean enabled;
    private final HttpClient httpClient;

    private final AtomicBoolean backendsUnsupported = new AtomicBoolean();
    private final AtomicBoolean frontendsUnsupported = new AtomicBoolean();
    private final AtomicBoolean wasLeader = new AtomicBoolean(true);
    private final AtomicBoolean loggedDisabled = new AtomicBoolean();
    private final DorisCumulativeDelta cumulativeDelta = new DorisCumulativeDelta();

    public DorisPlatformStatsSampler(
            DataSource dorisDataSource,
            DorisAvailability dorisAvailability,
            DorisConnectionConfig dorisConnectionConfig,
            ClusterInstanceCoordinator clusterCoordinator,
            @Value("${apm.platform-metrics.doris-enabled:true}") boolean enabled) {
        this.dorisDataSource = dorisDataSource;
        this.dorisAvailability = dorisAvailability;
        this.dorisConnectionConfig = dorisConnectionConfig;
        this.clusterCoordinator = clusterCoordinator;
        this.enabled = enabled;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Scheduled(cron = "${apm.platform-metrics.doris-sample-cron:50 * * * * *}")
    void sample() {
        if (!enabled) {
            if (loggedDisabled.compareAndSet(false, true)) {
                log.info("Doris platform metrics poll disabled (apm.platform-metrics.doris-enabled=false)");
            }
            return;
        }
        boolean leader = clusterCoordinator.isLeader();
        if (!leader) {
            if (wasLeader.getAndSet(false)) {
                log.info("Doris platform metrics poll skipped; not web leader nodeId={}",
                        clusterCoordinator.localNodeId());
            }
            return;
        }
        wasLeader.set(true);

        double up = dorisAvailability.isUnavailable() ? 0.0 : 1.0;
        // JDBC 探活：instance = FE Host（dim 空，留给 type 类标签）
        for (String feHost : dorisConnectionConfig.feHosts()) {
            String host = truncateDim(feHost);
            if (!host.isEmpty()) {
                PlatformMetrics.gauge(PlatformMetricNames.DORIS_UP, "", host).set(up);
            }
        }
        if (dorisAvailability.isUnavailable()) {
            return;
        }

        List<NodeTarget> beTargets = new ArrayList<>();
        List<NodeTarget> feTargets = new ArrayList<>();
        try (Connection connection = dorisDataSource.getConnection()) {
            sampleBackends(connection, beTargets);
            sampleFrontends(connection, feTargets);
        } catch (SQLException e) {
            if (log.isDebugEnabled()) {
                log.debug("Doris SHOW BACKENDS/FRONTENDS skipped: {}", e.toString());
            }
            return;
        }

        for (NodeTarget t : beTargets) {
            scrapeMetrics(t, "be");
        }
        for (NodeTarget t : feTargets) {
            scrapeMetrics(t, "fe");
        }
    }

    private void scrapeMetrics(NodeTarget target, String role) {
        String url = "http://" + target.host() + ":" + target.httpPort() + "/metrics";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(HTTP_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                if (log.isDebugEnabled()) {
                    log.debug("Doris {} /metrics HTTP {} from {}", role, response.statusCode(), target.authority());
                }
                return;
            }
            Map<String, DorisSelfMonitorMapper.AggregatedMetric> mapped =
                    DorisSelfMonitorMapper.mapAndAggregateTyped(
                            DorisPrometheusTextParser.parse(response.body()));
            String host = truncateDim(target.host());
            // CPU modes must be percent-normalized together after per-mode deltas.
            Map<String, Double> cpuModeDeltas = new LinkedHashMap<>();
            String cpuRelative = null;
            for (DorisSelfMonitorMapper.AggregatedMetric agg : mapped.values()) {
                // 刮取指标带上 fe./be.；CPU mode 等在 dim，Host 在 instance
                String relative = DorisSelfMonitorMapper.withRole(agg.metric(), role);
                String typeDim = truncateDim(agg.dim());
                double toStore = agg.value();
                if (agg.cumulative()) {
                    var delta = cumulativeDelta.delta(host + '\0' + relative + '\0' + typeDim, agg.value());
                    if (delta.isEmpty()) {
                        continue; // first sample: establish baseline only
                    }
                    toStore = delta.getAsDouble();
                }
                if (DorisSelfMonitorMapper.isCpuMetric(relative)) {
                    cpuRelative = relative;
                    cpuModeDeltas.put(typeDim, toStore);
                    continue;
                }
                PlatformMetrics.gauge(relative, typeDim, host).set(toStore);
            }
            if (cpuRelative != null && !cpuModeDeltas.isEmpty()) {
                Map<String, Double> percents = DorisSelfMonitorMapper.cpuModeDeltasToPercents(cpuModeDeltas);
                for (Map.Entry<String, Double> e : percents.entrySet()) {
                    PlatformMetrics.gauge(cpuRelative, e.getKey(), host).set(e.getValue());
                }
            }
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("Doris {} /metrics scrape failed {}: {}", role, target.authority(), e.toString());
            }
        }
    }

    private void sampleBackends(Connection connection, List<NodeTarget> scrapeTargets) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SHOW BACKENDS")) {
            ResultSetMetaData meta = resultSet.getMetaData();
            int aliveCol = findColumn(meta, "Alive");
            int hostCol = findColumn(meta, "Host");
            int httpPortCol = findColumn(meta, "HttpPort");
            int tabletCol = findColumn(meta, "TabletNum");
            int usedPctCol = findColumn(meta, "UsedPct");
            int runningCol = findColumn(meta, "RunningTasks");
            if (aliveCol < 0 || hostCol < 0 || httpPortCol < 0) {
                logOnceUnsupported(backendsUnsupported, "SHOW BACKENDS missing Alive/Host/HttpPort");
                return;
            }
            while (resultSet.next()) {
                String host = nullToEmpty(resultSet.getString(hostCol)).trim();
                String instance = truncateDim(host);
                if (instance.isEmpty()) {
                    continue;
                }
                boolean isAlive = isTruthy(resultSet.getString(aliveCol));
                // instance = BE Host；dim 空
                PlatformMetrics.gauge(PlatformMetricNames.DORIS_BE_ALIVE, "", instance)
                        .set(isAlive ? 1.0 : 0.0);
                if (tabletCol > 0) {
                    Double tablets = parseNumber(resultSet.getString(tabletCol));
                    if (tablets != null) {
                        PlatformMetrics.gauge(PlatformMetricNames.doris("be.tablet_num"), "", instance)
                                .set(tablets);
                    }
                }
                if (usedPctCol > 0) {
                    Double pct = parseNumber(resultSet.getString(usedPctCol));
                    if (pct != null) {
                        PlatformMetrics.gauge(PlatformMetricNames.doris("be.used_pct"), "", instance)
                                .set(pct);
                    }
                }
                if (runningCol > 0) {
                    Double tasks = parseNumber(resultSet.getString(runningCol));
                    if (tasks != null) {
                        PlatformMetrics.gauge(PlatformMetricNames.doris("be.running_tasks"), "", instance)
                                .set(tasks);
                    }
                }
                if (!isAlive) {
                    continue;
                }
                int httpPort = parsePort(resultSet.getString(httpPortCol), 8040);
                scrapeTargets.add(new NodeTarget(host, httpPort));
            }
        } catch (SQLException e) {
            logOnceUnsupported(backendsUnsupported, "SHOW BACKENDS unsupported: " + e.getMessage());
        }
    }

    private void sampleFrontends(Connection connection, List<NodeTarget> scrapeTargets) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SHOW FRONTENDS")) {
            ResultSetMetaData meta = resultSet.getMetaData();
            int aliveCol = findColumn(meta, "Alive");
            int hostCol = findColumn(meta, "Host");
            int httpPortCol = findColumn(meta, "HttpPort");
            int masterCol = findColumn(meta, "IsMaster");
            if (aliveCol < 0 || hostCol < 0 || httpPortCol < 0) {
                logOnceUnsupported(frontendsUnsupported, "SHOW FRONTENDS missing Alive/Host/HttpPort");
                return;
            }
            while (resultSet.next()) {
                String host = nullToEmpty(resultSet.getString(hostCol)).trim();
                String instance = truncateDim(host);
                if (instance.isEmpty()) {
                    continue;
                }
                boolean isAlive = isTruthy(resultSet.getString(aliveCol));
                // instance = FE Host；dim 空
                PlatformMetrics.gauge(PlatformMetricNames.DORIS_FE_ALIVE, "", instance)
                        .set(isAlive ? 1.0 : 0.0);
                if (masterCol > 0) {
                    PlatformMetrics.gauge(PlatformMetricNames.doris("fe.is_master"), "", instance)
                            .set(isTruthy(resultSet.getString(masterCol)) ? 1.0 : 0.0);
                }
                if (!isAlive) {
                    continue;
                }
                int httpPort = parsePort(resultSet.getString(httpPortCol), 8030);
                scrapeTargets.add(new NodeTarget(host, httpPort));
            }
        } catch (SQLException e) {
            logOnceUnsupported(frontendsUnsupported, "SHOW FRONTENDS unsupported: " + e.getMessage());
        }
    }

    record NodeTarget(String host, int httpPort) {
        String authority() {
            return host + ":" + httpPort;
        }
    }

    static String truncateDim(String instance) {
        String inst = instance == null ? "" : instance.trim();
        if (inst.length() <= DIM_MAX) {
            return inst;
        }
        return inst.substring(0, DIM_MAX);
    }

    static Double parseNumber(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isDigit(c) || c == '.' || c == '-' || c == '+') {
                i++;
            } else {
                break;
            }
        }
        if (i == 0) {
            return null;
        }
        try {
            return Double.parseDouble(s.substring(0, i));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static int parsePort(String raw, int fallback) {
        Double n = parseNumber(raw);
        if (n == null) {
            return fallback;
        }
        int p = n.intValue();
        return p > 0 && p <= 65535 ? p : fallback;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static int findColumn(ResultSetMetaData meta, String name) throws SQLException {
        int columns = meta.getColumnCount();
        for (int i = 1; i <= columns; i++) {
            String label = meta.getColumnLabel(i);
            if (label != null && label.equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isTruthy(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized);
    }

    private static void logOnceUnsupported(AtomicBoolean flag, String message) {
        if (flag.compareAndSet(false, true) && log.isDebugEnabled()) {
            log.debug("{}", message);
        }
    }
}
