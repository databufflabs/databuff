package com.databuff.apm.common.metric;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetricQueryDefinitionTest {

    @Test
    void gettersAndSettersRoundTrip() {
        MetricQueryDefinition def = new MetricQueryDefinition();
        def.setId(9L);
        def.setIdentifier("svc.req.cnt");
        def.setType1("t1");
        def.setType2("t2");
        def.setType3("t3");
        def.setApp("demo");
        def.setDatabase("databuff");
        def.setMeasurement("svc.req");
        def.setField("cnt");
        def.setDesc("请求数");
        def.setTagKey(Map.of("env", "env"));
        def.setTagValue(Map.of("env", "prod"));
        def.setFieldValue(Map.of("min", 0));
        def.setKeys(Map.of("k", "v"));
        def.setUnit("count");
        def.setUnitCn("个");
        def.setMetricCn("请求数");
        def.setAggregatorType("sum");
        def.setFormula("sum(\"cnt\")");
        def.setIsOpen(true);
        def.setCore(true);
        def.setBuiltin(true);
        def.setDorisTable("metric_svc");

        assertThat(def.getId()).isEqualTo(9L);
        assertThat(def.getIdentifier()).isEqualTo("svc.req.cnt");
        assertThat(def.getType1()).isEqualTo("t1");
        assertThat(def.getType2()).isEqualTo("t2");
        assertThat(def.getType3()).isEqualTo("t3");
        assertThat(def.getApp()).isEqualTo("demo");
        assertThat(def.getDatabase()).isEqualTo("databuff");
        assertThat(def.getMeasurement()).isEqualTo("svc.req");
        assertThat(def.getField()).isEqualTo("cnt");
        assertThat(def.getDesc()).isEqualTo("请求数");
        assertThat(def.getTagKey()).containsEntry("env", "env");
        assertThat(def.getTagValue()).containsEntry("env", "prod");
        assertThat(def.getFieldValue()).containsEntry("min", 0);
        assertThat(def.getKeys()).containsEntry("k", "v");
        assertThat(def.getUnit()).isEqualTo("count");
        assertThat(def.getUnitCn()).isEqualTo("个");
        assertThat(def.getMetricCn()).isEqualTo("请求数");
        assertThat(def.getAggregatorType()).isEqualTo("sum");
        assertThat(def.getFormula()).isEqualTo("sum(\"cnt\")");
        assertThat(def.getIsOpen()).isTrue();
        assertThat(def.getCore()).isTrue();
        assertThat(def.getBuiltin()).isTrue();
        assertThat(def.getDorisTable()).isEqualTo("metric_svc");
    }
}
