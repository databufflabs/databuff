package com.databuff.apm.ingest.receiver.compression;

import io.grpc.Codec;
import org.xerial.snappy.SnappyFramedInputStream;
import org.xerial.snappy.SnappyFramedOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * gRPC message codec for {@code snappy}, compatible with OpenTelemetry Collector
 * (framed snappy stream format).
 */
public final class GrpcSnappyCodec implements Codec {

    public static final GrpcSnappyCodec INSTANCE = new GrpcSnappyCodec();

    private GrpcSnappyCodec() {
    }

    @Override
    public String getMessageEncoding() {
        return "snappy";
    }

    @Override
    public OutputStream compress(OutputStream os) throws IOException {
        return new SnappyFramedOutputStream(os);
    }

    @Override
    public InputStream decompress(InputStream is) throws IOException {
        return new SnappyFramedInputStream(is);
    }
}
