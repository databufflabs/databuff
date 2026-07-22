package com.databuff.apm.ingest.receiver.compression;

import com.github.luben.zstd.ZstdOutputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;
import org.junit.jupiter.api.Test;
import org.xerial.snappy.Snappy;
import org.xerial.snappy.SnappyFramedOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OtlpHttpContentDecoderTest {

    private static final byte[] PAYLOAD = "otlp-compression-test".getBytes(StandardCharsets.UTF_8);

    @Test
    void decodesGzip() throws Exception {
        assertThat(decode("gzip", gzip(PAYLOAD))).isEqualTo(PAYLOAD);
    }

    @Test
    void decodesZlibAndDeflate() throws Exception {
        byte[] zlib = zlib(PAYLOAD);
        assertThat(decode("zlib", zlib)).isEqualTo(PAYLOAD);
        assertThat(decode("deflate", zlib)).isEqualTo(PAYLOAD);
    }

    @Test
    void decodesZstd() throws Exception {
        assertThat(decode("zstd", zstd(PAYLOAD))).isEqualTo(PAYLOAD);
    }

    @Test
    void decodesSnappyBlock() throws Exception {
        assertThat(decode("snappy", Snappy.compress(PAYLOAD))).isEqualTo(PAYLOAD);
    }

    @Test
    void decodesSnappyFramedAsSnappyAndXSnappyFramed() throws Exception {
        byte[] framed = snappyFramed(PAYLOAD);
        assertThat(decode("snappy", framed)).isEqualTo(PAYLOAD);
        assertThat(decode("x-snappy-framed", framed)).isEqualTo(PAYLOAD);
    }

    @Test
    void decodesLz4() throws Exception {
        assertThat(decode("lz4", lz4(PAYLOAD))).isEqualTo(PAYLOAD);
    }

    @Test
    void identityAndNonePassThrough() throws Exception {
        assertThat(decode("", PAYLOAD)).isEqualTo(PAYLOAD);
        assertThat(decode("none", PAYLOAD)).isEqualTo(PAYLOAD);
        assertThat(decode("identity", PAYLOAD)).isEqualTo(PAYLOAD);
    }

    @Test
    void rejectsUnknownEncoding() {
        assertThatThrownBy(() -> decode("brotli", PAYLOAD))
                .isInstanceOf(UnsupportedCompressionException.class)
                .hasMessageContaining("brotli");
    }

    private static byte[] decode(String encoding, byte[] compressed) throws Exception {
        try (InputStream in =
                OtlpHttpContentDecoder.decode(encoding, new ByteArrayInputStream(compressed))) {
            return in.readAllBytes();
        }
    }

    private static byte[] gzip(byte[] raw) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(raw);
        }
        return out.toByteArray();
    }

    private static byte[] zlib(byte[] raw) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream zlib = new DeflaterOutputStream(out)) {
            zlib.write(raw);
        }
        return out.toByteArray();
    }

    private static byte[] zstd(byte[] raw) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZstdOutputStream zstd = new ZstdOutputStream(out)) {
            zstd.write(raw);
        }
        return out.toByteArray();
    }

    private static byte[] snappyFramed(byte[] raw) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (SnappyFramedOutputStream snappy = new SnappyFramedOutputStream(out)) {
            snappy.write(raw);
        }
        return out.toByteArray();
    }

    private static byte[] lz4(byte[] raw) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (LZ4FrameOutputStream lz4 = new LZ4FrameOutputStream(out)) {
            lz4.write(raw);
        }
        return out.toByteArray();
    }
}
