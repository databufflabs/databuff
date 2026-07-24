package com.databuff.apm.common.storage;

import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.common.serde.DCSpanJsonEncoder;
import com.databuff.apm.common.serde.SpanDorisJsonRow;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NdjsonRowsInputStreamTest {

    @Test
    void streamsBytesRowsWithNewlines() throws Exception {
        byte[] body;
        try (NdjsonRowsInputStream in = new NdjsonRowsInputStream(List.of(
                DorisJsonRow.ofBytes("{\"a\":1}".getBytes(StandardCharsets.UTF_8)),
                DorisJsonRow.ofBytes("{\"b\":2}".getBytes(StandardCharsets.UTF_8))))) {
            body = in.readAllBytes();
        }
        assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo("{\"a\":1}\n{\"b\":2}");
    }

    @Test
    void lazySpanRowMatchesEagerEncode() throws Exception {
        DcSpan span = new DcSpan();
        span.trace_id = "t1";
        span.span_id = "s1";
        span.service = "svc";
        byte[] eager = DCSpanJsonEncoder.encode(span);
        ByteArrayOutputStream lazy = new ByteArrayOutputStream();
        new SpanDorisJsonRow(span).writeTo(lazy);
        assertThat(lazy.toByteArray()).isEqualTo(eager);

        byte[] streamed;
        try (NdjsonRowsInputStream in = new NdjsonRowsInputStream(List.of(new SpanDorisJsonRow(span)))) {
            streamed = in.readAllBytes();
        }
        assertThat(streamed).isEqualTo(eager);
    }
}
