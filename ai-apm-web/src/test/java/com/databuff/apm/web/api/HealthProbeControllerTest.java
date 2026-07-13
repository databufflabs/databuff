package com.databuff.apm.web.api;

import com.databuff.apm.web.storage.DorisAvailability;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HealthProbeControllerTest {

    @Test
    void healthReturnsUpWhenDorisAvailable() {
        DorisAvailability availability = new DorisAvailability();
        availability.markAvailable();
        assertThat(new HealthProbeController(availability).health())
                .containsEntry("status", "UP")
                .containsEntry("doris", "UP");
    }

    @Test
    void healthReportsDorisUnavailableInTroubleshootingMode() {
        DorisAvailability availability = new DorisAvailability();
        availability.markUnavailable("test");
        assertThat(new HealthProbeController(availability).health())
                .containsEntry("status", "UP")
                .containsEntry("doris", "UNAVAILABLE");
    }
}
