package com.databuff.apm.common.meta;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceTypeClassifierTest {

    @Test
    void classifiesDatabaseServices() {
        var classification = ServiceTypeClassifier.classify("[mysql]dc_databuff");
        assertThat(classification.serviceType()).isEqualTo("db");
        assertThat(classification.type()).isEqualTo("mysql");
    }

    @Test
    void classifiesElasticsearchAsDatabase() {
        var classification = ServiceTypeClassifier.classify("[elasticsearch]es:9200");
        assertThat(classification.serviceType()).isEqualTo("db");
        assertThat(classification.type()).isEqualTo("elasticsearch");
        assertThat(classification.technology()).isEqualTo("elasticsearch");
    }

    @Test
    void classifiesWebServices() {
        var classification = ServiceTypeClassifier.classify("demo-order");
        assertThat(classification.serviceType()).isEqualTo("web");
        assertThat(classification.type()).isEqualTo("web");
    }

    @Test
    void classifiesMqCacheAndRemote() {
        assertThat(ServiceTypeClassifier.classify("[kafka]events").serviceType()).isEqualTo("mq");
        assertThat(ServiceTypeClassifier.classify("[redis]cache").serviceType()).isEqualTo("cache");
        assertThat(ServiceTypeClassifier.classify("payment-gateway").serviceType()).isEqualTo("custom");
        assertThat(ServiceTypeClassifier.classify("  ").serviceType()).isEqualTo("web");
        ServiceTypeClassifier.classify("cached-svc");
        assertThat(ServiceTypeClassifier.classify("cached-svc")).isNotNull();
    }
}
