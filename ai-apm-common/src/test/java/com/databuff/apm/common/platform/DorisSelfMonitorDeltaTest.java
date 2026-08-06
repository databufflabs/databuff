package com.databuff.apm.common.platform;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DorisSelfMonitorDeltaTest {

    @Test
    void parseType_marksCpuAsCounter_withModeInDim() {
        String body = """
                # TYPE doris_be_cpu counter
                doris_be_cpu{device="cpu",mode="user"} 100
                doris_be_cpu{device="cpu",mode="softirq"} 20
                # TYPE doris_be_memory_allocated_bytes gauge
                doris_be_memory_allocated_bytes 1000
                # TYPE doris_fe_query_total counter
                doris_fe_query_total 50
                """;
        Map<String, DorisSelfMonitorMapper.AggregatedMetric> mapped =
                DorisSelfMonitorMapper.mapAndAggregateTyped(DorisPrometheusTextParser.parse(body));
        DorisSelfMonitorMapper.AggregatedMetric user = mapped.get("doris.cpu\0user");
        assertTrue(user.cumulative());
        assertEquals("doris.cpu", user.metric());
        assertEquals("user", user.dim());
        assertEquals(100.0, user.value());
        assertEquals("softirq", mapped.get("doris.cpu\0softirq").dim());
        assertFalse(mapped.get("doris.process_mem_bytes\0").cumulative());
        assertTrue(mapped.get("doris.query_success\0").cumulative());
    }

    @Test
    void isCpuMetric_stripsRolePrefix() {
        assertTrue(DorisSelfMonitorMapper.isCpuMetric("doris.be.cpu"));
        assertTrue(DorisSelfMonitorMapper.isCpuMetric("doris.cpu"));
        assertTrue(DorisSelfMonitorMapper.isCpuMetric("web.doris.be.cpu"));
        assertFalse(DorisSelfMonitorMapper.isCpuMetric("doris.be.process_mem_bytes"));
        assertFalse(DorisSelfMonitorMapper.isCpuMetric("doris.be.cpu.user"));
    }

    @Test
    void cpuModeDeltasToPercents_normalizesToHundred() {
        Map<String, Double> pct = DorisSelfMonitorMapper.cpuModeDeltasToPercents(Map.of(
                "idle", 70.0,
                "user", 20.0,
                "system", 10.0));
        assertEquals(70.0, pct.get("idle"), 1e-9);
        assertEquals(20.0, pct.get("user"), 1e-9);
        assertEquals(10.0, pct.get("system"), 1e-9);
        double sum = pct.values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(100.0, sum, 1e-9);
    }

    @Test
    void cpuModeDeltasToPercents_zeroTotal_yieldsZeros() {
        Map<String, Double> pct = DorisSelfMonitorMapper.cpuModeDeltasToPercents(Map.of(
                "idle", 0.0,
                "user", 0.0));
        assertEquals(0.0, pct.get("idle"));
        assertEquals(0.0, pct.get("user"));
    }

    @Test
    void withRole_prefixesBeOrFe() {
        assertEquals("doris.be.cpu", DorisSelfMonitorMapper.withRole("doris.cpu", "be"));
        assertEquals("doris.fe.jvm_heap_size_bytes.used",
                DorisSelfMonitorMapper.withRole("doris.jvm_heap_size_bytes.used", "fe"));
        assertEquals("doris.be.cpu", DorisSelfMonitorMapper.withRole("doris.be.cpu", "be"));
        assertEquals("doris.fe.query_success", DorisSelfMonitorMapper.withRole("doris.query_success", "FE"));
    }

    @Test
    void isLikelyCumulative_stripsRolePrefix() {
        assertTrue(DorisSelfMonitorMapper.isLikelyCumulativeLogical("doris.be.cpu"));
        assertTrue(DorisSelfMonitorMapper.isLikelyCumulativeLogical("doris.be.cpu.user"));
        assertTrue(DorisSelfMonitorMapper.isLikelyCumulativeLogical("doris.fe.query_success"));
        assertFalse(DorisSelfMonitorMapper.isLikelyCumulativeLogical("doris.be.process_mem_bytes"));
    }

    @Test
    void cumulativeDelta_skipsFirstThenDiffs() {
        DorisCumulativeDelta delta = new DorisCumulativeDelta();
        assertTrue(delta.delta("h\\0cpu", 100).isEmpty());
        OptionalDouble d = delta.delta("h\\0cpu", 130);
        assertTrue(d.isPresent());
        assertEquals(30.0, d.getAsDouble());
        assertEquals(0.0, delta.delta("h\\0cpu", 10).orElse(-1));
    }

    @Test
    void unknownType_usesLogicalHeuristic() {
        String body = """
                doris_be_memtable_flush_duration_us 999
                doris_be_memory_allocated_bytes 1000
                """;
        Map<String, DorisSelfMonitorMapper.AggregatedMetric> mapped =
                DorisSelfMonitorMapper.mapAndAggregateTyped(DorisPrometheusTextParser.parse(body));
        assertTrue(mapped.get("doris.memtable_flush_duration_us\0").cumulative());
        assertFalse(mapped.get("doris.process_mem_bytes\0").cumulative());
    }
}
