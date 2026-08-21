package com.databuff.apm.ingest.config;

import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.ingest.trace.SpanResourceIgnoreFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ByteArrayResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TraceIgnorePropertiesTest {

    @Test
    void bindsYamlSequencesAndEnablesFilter() {
        PropertySource<?> yaml = yamlPropertySource("""
                ingest:
                  trace:
                    ignore-resources:
                      - PING
                      - /actuator/prometheus
                    ignore-resource-regex:
                      - /actuator(/.*)?$
                      - ^SELECT 1$
                """);

        contextRunner(yaml).run(context -> {
            assertThat(context).hasNotFailed();
            TraceIgnoreProperties properties = context.getBean(TraceIgnoreProperties.class);
            assertThat(properties.ignoreResources()).containsExactly("PING", "/actuator/prometheus");
            assertThat(properties.ignoreResourceRegex()).containsExactly("/actuator(/.*)?$", "^SELECT 1$");

            SpanResourceIgnoreFilter filter = filter(properties);
            assertThat(filter.shouldIgnore(span("PING"))).isTrue();
            assertThat(filter.shouldIgnore(span("/actuator/prometheus"))).isTrue();
            assertThat(filter.shouldIgnore(span("/actuator/health"))).isTrue();
            assertThat(filter.shouldIgnore(span("SELECT 1"))).isTrue();
            assertThat(filter.shouldIgnore(span("/api/orders"))).isFalse();
        });
    }

    @Test
    void bindsCommaSeparatedEnvironmentVariables() {
        PropertySource<?> environment = new SystemEnvironmentPropertySource(
                "test-environment",
                Map.of(
                        "INGEST_TRACE_IGNORE_RESOURCES", "PING,/actuator/prometheus",
                        "INGEST_TRACE_IGNORE_RESOURCE_REGEX", "/actuator(/.*)?$,^SELECT 1$"));

        contextRunner(environment).run(context -> {
            assertThat(context).hasNotFailed();
            TraceIgnoreProperties properties = context.getBean(TraceIgnoreProperties.class);
            assertThat(properties.ignoreResources()).containsExactly("PING", "/actuator/prometheus");
            assertThat(properties.ignoreResourceRegex()).containsExactly("/actuator(/.*)?$", "^SELECT 1$");
            assertThat(filter(properties).shouldIgnore(span("/actuator/health"))).isTrue();
            assertThat(filter(properties).shouldIgnore(span("SELECT 1"))).isTrue();
        });
    }

    @Test
    void missingRulesBindAsEmptyLists() {
        new ApplicationContextRunner()
                .withUserConfiguration(BindingConfiguration.class)
                .run(context -> {
                    TraceIgnoreProperties properties = context.getBean(TraceIgnoreProperties.class);
                    assertThat(properties.ignoreResources()).isEmpty();
                    assertThat(properties.ignoreResourceRegex()).isEmpty();
                    assertThat(filter(properties).isEmpty()).isTrue();
                });
    }

    private static ApplicationContextRunner contextRunner(PropertySource<?> source) {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(source))
                .withUserConfiguration(BindingConfiguration.class);
    }

    private static PropertySource<?> yamlPropertySource(String yaml) {
        ByteArrayResource resource = new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8));
        try {
            return new YamlPropertySourceLoader().load("test-yaml", resource).get(0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static SpanResourceIgnoreFilter filter(TraceIgnoreProperties properties) {
        return new IngestPipelineConfiguration().spanResourceIgnoreFilter(properties);
    }

    private static DcSpan span(String resource) {
        DcSpan span = new DcSpan();
        span.resource = resource;
        return span;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TraceIgnoreProperties.class)
    static class BindingConfiguration {
    }
}
