package com.databuff.apm.demo.fault;

import java.time.Instant;

/** Immutable profile read by one synthetic telemetry batch. */
public record DemoFaultSnapshot(
        boolean active,
        String faultRunId,
        String configVersion,
        int cacheTtlSeconds,
        Instant startedAt,
        Instant autoRecoverAt) {

    public static final String NORMAL_CONFIG_VERSION = "demo-cache-ttl-300";
    public static final String FAULT_CONFIG_VERSION = "demo-cache-ttl-0";

    public static DemoFaultSnapshot normal() {
        return new DemoFaultSnapshot(
                false, "", NORMAL_CONFIG_VERSION, 300, null, null);
    }

    public static DemoFaultSnapshot active(
            String faultRunId, Instant startedAt, Instant autoRecoverAt) {
        return new DemoFaultSnapshot(
                true, faultRunId, FAULT_CONFIG_VERSION, 0, startedAt, autoRecoverAt);
    }
}
