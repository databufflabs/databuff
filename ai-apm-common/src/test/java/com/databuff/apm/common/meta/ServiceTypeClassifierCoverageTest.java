package com.databuff.apm.common.meta;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceTypeClassifierCoverageTest {

    @Test
    void classify_nullOrBlankYieldsWeb() {
        var c = ServiceTypeClassifier.classify(null);
        assertThat(c.serviceType()).isEqualTo("web");
        assertThat(c.type()).isEqualTo("web");
        assertThat(c.technology()).isEqualTo("web");
        assertThat(ServiceTypeClassifier.classify("").serviceType()).isEqualTo("web");
    }

    @Test
    void classify_cacheHitsReturnSameInstance() {
        ServiceTypeClassifier.Classification first = ServiceTypeClassifier.classify("pay-svc");
        ServiceTypeClassifier.Classification second = ServiceTypeClassifier.classify("pay-svc");
        assertThat(second).isSameAs(first);
    }

    @Test
    void bracketComponent_noClosingBracketYieldsNull() {
        assertThat(ServiceTypeClassifier.classify("[]").serviceType()).isEqualTo("web");
        assertThat(ServiceTypeClassifier.classify("[").serviceType()).isEqualTo("web");
    }

    @Test
    void dbTypeIcon_normalizesComponentVariants() {
        assertThat(ServiceTypeClassifier.classify("[mariadb]cluster").type()).isEqualTo("mysql");
        assertThat(ServiceTypeClassifier.classify("[postgres]main").type()).isEqualTo("postgres");
        assertThat(ServiceTypeClassifier.classify("[mongodb]main").type()).isEqualTo("mongo");
        assertThat(ServiceTypeClassifier.classify("[redis]database").type()).isEqualTo("redis");
        assertThat(ServiceTypeClassifier.classify("[oracle]scott").type()).isEqualTo("oracle");
        assertThat(ServiceTypeClassifier.classify("[postgres]main").serviceType()).isEqualTo("db");
    }

    @Test
    void dbTypeIcon_nonBracketNameLookup() {
        assertThat(ServiceTypeClassifier.classify("elasticsearch-cluster").type()).isEqualTo("elasticsearch");
        assertThat(ServiceTypeClassifier.classify("mysql-shard").type()).isEqualTo("mysql");
        assertThat(ServiceTypeClassifier.classify("db").serviceType()).isEqualTo("db");
    }

    @Test
    void remoteBracketsAreRemote() {
        var c = ServiceTypeClassifier.classify("[remote]api:443");
        assertThat(c.serviceType()).isEqualTo("remote");
        assertThat(c.type()).isEqualTo("remote");
    }

    @Test
    void technology_inferForWebServices() {
        assertThat(ServiceTypeClassifier.classify("order-service").technology()).isEqualTo("java");
        assertThat(ServiceTypeClassifier.classify("spring-boot-app").technology()).isEqualTo("java");
        assertThat(ServiceTypeClassifier.classify("java-app").technology()).isEqualTo("java");
        assertThat(ServiceTypeClassifier.classify("golang-worker").technology()).isEqualTo("go");
        assertThat(ServiceTypeClassifier.classify("node-api").technology()).isEqualTo("nodejs");
        assertThat(ServiceTypeClassifier.classify("python-etl").technology()).isEqualTo("python");
        assertThat(ServiceTypeClassifier.classify("frontend-static").technology()).isEqualTo("web");
    }

    @Test
    void mqAndCacheClassified() {
        assertThat(ServiceTypeClassifier.classify("[rocketmq]events").serviceType()).isEqualTo("mq");
        assertThat(ServiceTypeClassifier.classify("memcached-pool").serviceType()).isEqualTo("cache");
        assertThat(ServiceTypeClassifier.classify("[rocketmq]events").type()).isEqualTo("kafka");
    }
}
