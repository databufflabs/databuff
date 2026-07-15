package com.databuff.apm.web.storage;

import com.databuff.apm.common.storage.DorisConnectionConfig;
import com.databuff.apm.web.persistence.PersistenceStartupHydrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Doris reachability probe at startup and on a fixed delay.
 * When unavailable, JDBC fast-fail gate keeps the AI portal usable for ops troubleshooting.
 * When Doris recovers, the gate clears and persistence is re-hydrated without restarting web.
 */
@Component
public class DorisAvailabilityMonitor {

    private static final Logger log = LoggerFactory.getLogger(DorisAvailabilityMonitor.class);

    private final DorisAvailability availability;
    private final DorisConnectionConfig connectionConfig;
    private final PersistenceStartupHydrator persistenceHydrator;
    private final String username;
    private final String password;
    private final int connectTimeoutMs;
    private final int socketTimeoutMs;
    private final AtomicBoolean startupProbed = new AtomicBoolean(false);
    private final AtomicBoolean probing = new AtomicBoolean(false);
    /** Test seam: when non-null, overrides JDBC ping. */
    private volatile Boolean probeOverride;

    public DorisAvailabilityMonitor(
            DorisAvailability availability,
            DorisConnectionConfig connectionConfig,
            @Nullable PersistenceStartupHydrator persistenceHydrator,
            @Value("${apm.doris.username:root}") String username,
            @Value("${apm.doris.password:}") String password,
            @Value("${apm.doris.availability.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${apm.doris.availability.socket-timeout-ms:3000}") int socketTimeoutMs) {
        this.availability = availability;
        this.connectionConfig = connectionConfig;
        this.persistenceHydrator = persistenceHydrator;
        this.username = username;
        this.password = password;
        this.connectTimeoutMs = Math.max(500, connectTimeoutMs);
        this.socketTimeoutMs = Math.max(500, socketTimeoutMs);
    }

    /** Runs before the web port opens so JDBC fast-fail is active when Doris is down. */
    @PostConstruct
    void probeAtStartup() {
        if (!startupProbed.compareAndSet(false, true)) {
            return;
        }
        applyProbeResult("startup");
    }

    /**
     * Re-probe Doris after startup. fixedDelay avoids overlapping probes when a ping times out.
     */
    @Scheduled(fixedDelayString = "${apm.doris.availability.probe-interval-ms:60000}")
    void probePeriodically() {
        if (!startupProbed.get()) {
            return;
        }
        if (!probing.compareAndSet(false, true)) {
            return;
        }
        try {
            applyProbeResult("periodic");
        } finally {
            probing.set(false);
        }
    }

    private void applyProbeResult(String source) {
        boolean up = probe(connectTimeoutMs, socketTimeoutMs);
        if (up) {
            boolean wasUnavailable = availability.markAvailable();
            if ("startup".equals(source)) {
                log.info("Doris startup probe OK");
                return;
            }
            if (wasUnavailable) {
                log.info("Doris recovered (periodic probe OK); exiting troubleshooting mode");
                if (persistenceHydrator != null) {
                    persistenceHydrator.scheduleRecoveryHydrate();
                }
            }
            return;
        }
        boolean wasAvailable = !availability.isUnavailable();
        String reason = "startup".equals(source) ? "startup probe failed" : "periodic probe failed";
        availability.markUnavailable(reason);
        if (wasAvailable || "startup".equals(source)) {
            log.warn("Doris {} probe failed; JDBC fast-fail gate enabled", source);
        }
    }

    void overrideProbeResult(Boolean result) {
        this.probeOverride = result;
    }

    private boolean probe(int connectTimeoutMs, int socketTimeoutMs) {
        Boolean override = probeOverride;
        if (override != null) {
            return override;
        }
        return DorisJdbcProbe.ping(connectionConfig, username, password, connectTimeoutMs, socketTimeoutMs);
    }
}
