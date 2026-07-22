package com.databuff.apm.ingest.receiver.compression;

/** Thrown when OTLP/HTTP {@code Content-Encoding} is not supported by Ingest. */
public class UnsupportedCompressionException extends RuntimeException {

    public UnsupportedCompressionException(String message) {
        super(message);
    }
}
