package com.databuff.apm.web.storage;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DorisAvailabilityTest {

    @Test
    void startsUnavailableUntilMarkedAvailable() throws SQLException {
        DorisAvailability availability = new DorisAvailability();

        assertThat(availability.isUnavailable()).isTrue();
        assertThat(availability.reason()).contains("awaiting startup probe");
        assertThatThrownBy(availability::beforeConnection)
                .isInstanceOf(SQLException.class);

        assertThat(availability.markAvailable()).isTrue();
        assertThat(availability.isUnavailable()).isFalse();
        availability.beforeConnection();
    }

    @Test
    void gateFailsFastWhenUnavailable() {
        DorisAvailability availability = new DorisAvailability();
        availability.markUnavailable("test");

        assertThat(availability.isUnavailable()).isTrue();
        assertThatThrownBy(availability::beforeConnection)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("test");
    }

    @Test
    void markAvailableClearsGate() throws SQLException {
        DorisAvailability availability = new DorisAvailability();
        availability.markUnavailable("test");

        assertThat(availability.markAvailable()).isTrue();
        assertThat(availability.isUnavailable()).isFalse();
        availability.beforeConnection();
    }
}
