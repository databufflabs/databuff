package com.databuff.apm.ingest.receiver.compression;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class GrpcZstdCodecTest {

    @Test
    void roundTripsZstdStream() throws Exception {
        byte[] payload = "hello-zstd".getBytes(StandardCharsets.UTF_8);
        GrpcZstdCodec codec = GrpcZstdCodec.INSTANCE;
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();

        try (var out = codec.compress(compressed)) {
            out.write(payload);
        }

        assertThat(codec.getMessageEncoding()).isEqualTo("zstd");
        assertThat(compressed.toByteArray()).isNotEmpty();

        byte[] decoded;
        try (var in = codec.decompress(new ByteArrayInputStream(compressed.toByteArray()))) {
            decoded = in.readAllBytes();
        }
        assertThat(decoded).isEqualTo(payload);
    }
}
