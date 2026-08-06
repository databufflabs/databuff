package com.databuff.apm.ingest.receiver.compression;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class GrpcSnappyCodecTest {

    @Test
    void roundTripsFramedSnappyStream() throws Exception {
        byte[] payload = "hello-snappy".getBytes(StandardCharsets.UTF_8);
        GrpcSnappyCodec codec = GrpcSnappyCodec.INSTANCE;
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();

        try (var out = codec.compress(compressed)) {
            out.write(payload);
        }

        assertThat(codec.getMessageEncoding()).isEqualTo("snappy");
        assertThat(compressed.toByteArray()).isNotEmpty();

        byte[] decoded;
        try (var in = codec.decompress(new ByteArrayInputStream(compressed.toByteArray()))) {
            decoded = in.readAllBytes();
        }
        assertThat(decoded).isEqualTo(payload);
    }
}
