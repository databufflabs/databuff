package com.databuff.apm.ingest.receiver.compression;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import io.grpc.Codec;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * gRPC message codec for {@code zstd}, compatible with OpenTelemetry Collector.
 */
public final class GrpcZstdCodec implements Codec {

    public static final GrpcZstdCodec INSTANCE = new GrpcZstdCodec();

    private GrpcZstdCodec() {
    }

    @Override
    public String getMessageEncoding() {
        return "zstd";
    }

    @Override
    public OutputStream compress(OutputStream os) throws IOException {
        return new ZstdOutputStream(os);
    }

    @Override
    public InputStream decompress(InputStream is) throws IOException {
        return new ZstdInputStream(is);
    }
}
