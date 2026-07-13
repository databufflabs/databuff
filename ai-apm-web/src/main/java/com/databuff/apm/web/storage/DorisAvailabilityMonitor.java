package com.databuff.apm.web.storage;

import com.databuff.apm.common.storage.DorisConnectionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One-shot Doris probe during context startup, before the web port accepts traffic.
 * If the probe fails, JDBC fast-fail gate stays on for the lifetime of this process.
 */
@Component
public class DorisAvailabilityMonitor {

    private static final Logger log = LoggerFactory.getLogger(DorisAvailabilityMonitor.class);
    private static final int STARTUP_CONNECT_TIMEOUT_MS = 3_000;
    private static final int STARTUP_SOCKET_TIMEOUT_MS = 3_000;

    private final DorisAvailability availability;
    private final DorisConnectionConfig connectionConfig;
    private final String username;
    private final String password;
    private final AtomicBoolean probed = new AtomicBoolean(false);

    public DorisAvailabilityMonitor(
            DorisAvailability availability,
            DorisConnectionConfig connectionConfig,
            @Value("${apm.doris.username:root}") String username,
            @Value("${apm.doris.password:}") String password) {
        this.availability = availability;
        this.connectionConfig = connectionConfig;
        this.username = username;
        this.password = password;
    }

    /** Runs before the web port opens so JDBC fast-fail is active when Doris is down. */
    @PostConstruct
    void probeAtStartup() {
        if (!probed.compareAndSet(false, true)) {
            return;
        }
        if (probe(STARTUP_CONNECT_TIMEOUT_MS, STARTUP_SOCKET_TIMEOUT_MS)) {
            availability.markAvailable();
            log.info("Doris startup probe OK");
            return;
        }
        availability.markUnavailable("startup probe failed");
        log.warn("Doris startup probe failed; fast-fail gate enabled for this web process");
    }

    private boolean probe(int connectTimeoutMs, int socketTimeoutMs) {
        return DorisJdbcProbe.ping(connectionConfig, username, password, connectTimeoutMs, socketTimeoutMs);
    }
}
