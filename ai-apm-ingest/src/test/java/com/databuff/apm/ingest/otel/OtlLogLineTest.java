package com.databuff.apm.ingest.otel;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OtlLogLineTest {

    @Test
    void toJsonBytesTruncatesBodyByConfiguredStringLength() throws Exception {
        String longBody = "人".repeat(100);
        OtlLogLine line = new OtlLogLine(
                null,
                1L, 1L, "2026-01-01 00:00:00",
                "svc", "svc", "i1", "host",
                "", "", "INFO", 9,
                longBody, null, null);

        String json = new String(line.toJsonBytes(10));
        assertThat(json).contains("\"log_id\":\"");
        assertThat(json).contains("\"body\":\"" + "人".repeat(10) + "\"");
        assertThat(json).doesNotContain("人".repeat(11));
    }
}
