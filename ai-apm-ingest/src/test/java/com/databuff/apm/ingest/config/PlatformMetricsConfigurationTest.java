package com.databuff.apm.ingest.config;

import com.databuff.apm.common.platform.PlatformMetrics;
import com.databuff.apm.common.storage.DorisStreamLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PlatformMetricsConfigurationTest {

    @AfterEach
    void tearDown() {
        PlatformMetrics.stop();
    }

    @Test
    void disabledSkipsStartAndInstallsNoopInstruments() {
        PlatformMetricsConfiguration config = new PlatformMetricsConfiguration(
                mock(DorisStreamLoader.class), "databuff", false);

        config.startPlatformMetrics();

        assertThat(PlatformMetrics.isActive()).isFalse();
    }

    @Test
    void enabledStartsSelfMetricsWithConfiguredDatabase() {
        PlatformMetricsConfiguration config = new PlatformMetricsConfiguration(
                mock(DorisStreamLoader.class), "custom-db", true);

        config.startPlatformMetrics();

        assertThat(PlatformMetrics.isActive()).isTrue();
        PlatformMetrics.stop();
        assertThat(PlatformMetrics.isActive()).isFalse();
    }

    @Test
    void stopPlatformMetricsTearsDownActiveMetrics() {
        PlatformMetricsConfiguration config = new PlatformMetricsConfiguration(
                mock(DorisStreamLoader.class), "databuff", true);
        config.startPlatformMetrics();
        assertThat(PlatformMetrics.isActive()).isTrue();

        config.stopPlatformMetrics();

        assertThat(PlatformMetrics.isActive()).isFalse();
    }
}
