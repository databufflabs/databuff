package com.databuff.apm.web.storage;

import com.databuff.apm.common.storage.DorisAccessGate;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks Doris reachability for Web troubleshooting mode.
 * When marked unavailable, JDBC reads fail immediately instead of waiting on the pool.
 */
@Component
public class DorisAvailability implements DorisAccessGate {

    private static final String STARTUP_PENDING_REASON = "awaiting startup probe";

    private final AtomicBoolean unavailable = new AtomicBoolean(true);
    private final AtomicReference<String> reason = new AtomicReference<>(STARTUP_PENDING_REASON);

    public boolean isUnavailable() {
        return unavailable.get();
    }

    public String reason() {
        return reason.get();
    }

    public void markUnavailable(String why) {
        unavailable.set(true);
        reason.set(why == null ? "" : why.trim());
    }

    public boolean markAvailable() {
        boolean wasUnavailable = unavailable.getAndSet(false);
        reason.set("");
        return wasUnavailable;
    }

    @Override
    public void beforeConnection() throws SQLException {
        if (unavailable.get()) {
            String detail = reason.get();
            String message = detail == null || detail.isBlank()
                    ? "Doris unavailable (troubleshooting mode)"
                    : "Doris unavailable: " + detail;
            throw new SQLException(message, "08001", 2000);
        }
    }
}
