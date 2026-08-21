package com.databuff.apm.ingest.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** Span resource ignore rules bound from {@code ingest.trace}. */
@ConfigurationProperties(prefix = "ingest.trace")
public record TraceIgnoreProperties(
        List<String> ignoreResources,
        List<String> ignoreResourceRegex) {

    public TraceIgnoreProperties {
        ignoreResources = immutableOrEmpty(ignoreResources);
        ignoreResourceRegex = immutableOrEmpty(ignoreResourceRegex);
    }

    private static List<String> immutableOrEmpty(List<String> values) {
        return values == null || values.isEmpty() ? List.of() : List.copyOf(values);
    }
}
