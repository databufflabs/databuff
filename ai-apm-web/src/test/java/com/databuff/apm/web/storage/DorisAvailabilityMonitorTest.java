package com.databuff.apm.web.storage;

import com.databuff.apm.common.storage.DorisConnectionConfig;
import com.databuff.apm.web.persistence.PersistenceStartupHydrator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class DorisAvailabilityMonitorTest {

    @Test
    void startupProbeMarksUnavailableWhenDorisUnreachable() {
        DorisAvailability availability = new DorisAvailability();
        DorisAvailabilityMonitor monitor = newMonitor(availability, null);

        monitor.probeAtStartup();

        assertThat(availability.isUnavailable()).isTrue();
        assertThat(availability.reason()).contains("startup probe failed");
    }

    @Test
    void periodicProbeMarksUnavailableAfterRuntimeOutage() {
        DorisAvailability availability = new DorisAvailability();
        DorisAvailabilityMonitor monitor = newMonitor(availability, null);
        monitor.overrideProbeResult(true);
        monitor.probeAtStartup();
        assertThat(availability.isUnavailable()).isFalse();

        monitor.overrideProbeResult(false);
        monitor.probePeriodically();

        assertThat(availability.isUnavailable()).isTrue();
        assertThat(availability.reason()).contains("periodic probe failed");
    }

    @Test
    void periodicProbeAlwaysForceReloadsWhenDorisUp() {
        DorisAvailability availability = new DorisAvailability();
        PersistenceStartupHydrator hydrator = mock(PersistenceStartupHydrator.class);
        DorisAvailabilityMonitor monitor = newMonitor(availability, hydrator);
        monitor.overrideProbeResult(true);
        monitor.probeAtStartup();
        monitor.probePeriodically();

        verify(hydrator).ensureHydrated(true);
    }

    @Test
    void periodicProbeForceReloadsAfterDorisRecovery() {
        DorisAvailability availability = new DorisAvailability();
        PersistenceStartupHydrator hydrator = mock(PersistenceStartupHydrator.class);
        DorisAvailabilityMonitor monitor = newMonitor(availability, hydrator);
        monitor.overrideProbeResult(false);
        monitor.probeAtStartup();
        assertThat(availability.isUnavailable()).isTrue();

        monitor.overrideProbeResult(true);
        monitor.probePeriodically();

        assertThat(availability.isUnavailable()).isFalse();
        verify(hydrator).ensureHydrated(true);
    }

    @Test
    void periodicProbeSkipsWhileStartupNotDone() {
        DorisAvailability availability = new DorisAvailability();
        PersistenceStartupHydrator hydrator = mock(PersistenceStartupHydrator.class);
        DorisAvailabilityMonitor monitor = newMonitor(availability, hydrator);
        monitor.overrideProbeResult(true);

        monitor.probePeriodically();

        assertThat(availability.isUnavailable()).isTrue();
        assertThat(availability.reason()).contains("awaiting startup probe");
        verifyNoInteractions(hydrator);
    }

    @Test
    void periodicProbeDoesNotTouchHydratorWhenDorisDown() {
        DorisAvailability availability = new DorisAvailability();
        PersistenceStartupHydrator hydrator = mock(PersistenceStartupHydrator.class);
        DorisAvailabilityMonitor monitor = newMonitor(availability, hydrator);
        monitor.overrideProbeResult(true);
        monitor.probeAtStartup();

        monitor.overrideProbeResult(false);
        monitor.probePeriodically();

        verify(hydrator, never()).ensureHydrated(false);
        verify(hydrator, never()).ensureHydrated(true);
    }

    private static DorisAvailabilityMonitor newMonitor(
            DorisAvailability availability,
            PersistenceStartupHydrator hydrator) {
        return new DorisAvailabilityMonitor(
                availability,
                new DorisConnectionConfig("127.0.0.1", 1, 8030),
                hydrator,
                "root",
                "",
                3000,
                3000);
    }
}
