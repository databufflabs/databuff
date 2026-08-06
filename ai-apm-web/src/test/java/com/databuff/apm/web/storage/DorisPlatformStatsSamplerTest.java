package com.databuff.apm.web.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DorisPlatformStatsSamplerTest {

    @Test
    void truncateDim_keepsNodeOnly() {
        assertEquals("10.0.0.1", DorisPlatformStatsSampler.truncateDim("10.0.0.1"));
        assertEquals("", DorisPlatformStatsSampler.truncateDim(""));
    }

    @Test
    void truncateDim_truncatesTo128() {
        assertEquals(128, DorisPlatformStatsSampler.truncateDim("h".repeat(200)).length());
    }

    @Test
    void parseNumber_handlesUnits() {
        assertEquals(90.89d, DorisPlatformStatsSampler.parseNumber("90.89 %"));
        assertNull(DorisPlatformStatsSampler.parseNumber("abc"));
    }

    @Test
    void parsePort_fallsBack() {
        assertEquals(8040, DorisPlatformStatsSampler.parsePort("8040", 8040));
        assertEquals(8030, DorisPlatformStatsSampler.parsePort("", 8030));
    }
}
