package com.databuff.apm.web.storage;

import com.databuff.apm.common.storage.DorisConnectionConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DorisAvailabilityMonitorTest {

    @Test
    void startupProbeMarksUnavailableWhenDorisUnreachable() {
        DorisAvailability availability = new DorisAvailability();
        DorisConnectionConfig config = new DorisConnectionConfig("127.0.0.1", 1, 8030);
        DorisAvailabilityMonitor monitor = new DorisAvailabilityMonitor(
                availability,
                config,
                "root",
                "");

        monitor.probeAtStartup();

        assertThat(availability.isUnavailable()).isTrue();
        assertThat(availability.reason()).contains("startup probe failed");
    }
}
