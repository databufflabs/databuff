package com.databuff.apm.web.metric;

import org.junit.jupiter.api.Test;

import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

class MetricTypeDtoTest {

    @Test
    void exposesSettersAndGetters() {
        MetricTypeDto dto = new MetricTypeDto();
        dto.setType1("service");
        dto.setType2("http");
        dto.setType3("metrics");
        dto.setApp("demo");
        dto.setBuiltin(true);
        dto.setMetricList(new TreeSet<>(java.util.List.of("m1", "m2")));

        assertThat(dto.getType1()).isEqualTo("service");
        assertThat(dto.getType2()).isEqualTo("http");
        assertThat(dto.getType3()).isEqualTo("metrics");
        assertThat(dto.getApp()).isEqualTo("demo");
        assertThat(dto.getBuiltin()).isTrue();
        assertThat(dto.getMetricList()).containsExactly("m1", "m2");
    }

    @Test
    void comparesByFieldsInOrder() {
        MetricTypeDto a = base("a", "1", "x", "app1");
        MetricTypeDto b = base("b", "1", "x", "app1");
        assertThat(a.compareTo(b)).isNegative();

        assertThat(base("a", "1", "x", "app1").compareTo(base("a", "2", "x", "app1"))).isNegative();
        assertThat(base("a", "1", "x", "app1").compareTo(base("a", "1", "y", "app1"))).isNegative();
        assertThat(base("a", "1", "x", "app1").compareTo(base("a", "1", "x", "app2"))).isNegative();
        assertThat(base("a", "1", "x", "app1").compareTo(base("a", "1", "x", "app1"))).isZero();
    }

    @Test
    void comparesNullsFirst() {
        MetricTypeDto withNull = new MetricTypeDto();
        withNull.setType1(null);
        MetricTypeDto withValue = new MetricTypeDto();
        withValue.setType1("a");

        assertThat(new MetricTypeDto().compareTo(new MetricTypeDto())).isZero();
        assertThat(withNull.compareTo(withValue)).isNegative();
        assertThat(withValue.compareTo(withNull)).isPositive();
    }

    @Test
    void equalsAndHashCode() {
        MetricTypeDto a = base("a", "1", "x", "app1");
        MetricTypeDto b = base("a", "1", "x", "app1");
        MetricTypeDto c = base("a", "1", "x", "other");

        assertThat(a.equals(a)).isTrue();
        assertThat(a.equals(b)).isTrue();
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a.equals(c)).isFalse();
        assertThat(a.equals(null)).isFalse();
        assertThat(a.equals("not-a-dto")).isFalse();
    }

    @Test
    void metricListIsSorted() {
        MetricTypeDto dto = new MetricTypeDto();
        dto.getMetricList().addAll(java.util.List.of("z-metric", "a-metric", "m-metric"));

        assertThat(dto.getMetricList()).containsExactly("a-metric", "m-metric", "z-metric");
    }

    private static MetricTypeDto base(String type1, String type2, String type3, String app) {
        MetricTypeDto dto = new MetricTypeDto();
        dto.setType1(type1);
        dto.setType2(type2);
        dto.setType3(type3);
        dto.setApp(app);
        return dto;
    }
}
