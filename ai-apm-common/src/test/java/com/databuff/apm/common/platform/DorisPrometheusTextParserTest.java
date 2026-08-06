package com.databuff.apm.common.platform;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DorisPrometheusTextParserTest {

    @Test
    void parseAndMap_ultraAligned() {
        String body = """
                # TYPE doris_be_cpu counter
                doris_be_cpu{device="cpu0",mode="idle"} 1
                doris_be_cpu{device="cpu",mode="idle"} 9591356
                # TYPE doris_be_memory_allocated_bytes gauge
                doris_be_memory_allocated_bytes 1000
                # TYPE doris_be_thread_pool_active_threads gauge
                doris_be_thread_pool_active_threads{thread_pool_name="Seg",id="a"} 2
                doris_be_thread_pool_active_threads{thread_pool_name="Seg",id="b"} 3
                # TYPE doris_fe_query_total counter
                doris_fe_query_total 10007
                doris_fe_query_total{user="root"} 10007
                # TYPE doris_fe_edit_log counter
                doris_fe_edit_log{type="write"} 101571
                """;
        Map<String, Double> mapped = DorisSelfMonitorMapper.mapAndAggregate(DorisPrometheusTextParser.parse(body));
        assertEquals(9591356.0, mapped.get("doris.cpu.idle"));
        assertEquals(1000.0, mapped.get("doris.process_mem_bytes"));
        assertEquals(5.0, mapped.get("doris.thread_pool.Seg"));
        assertEquals(10007.0, mapped.get("doris.query_success"));
        assertEquals(101571.0, mapped.get("doris.edit_log_write"));
        assertTrue(mapped.keySet().stream().noneMatch(k -> k.contains("cpu0")));
    }
}
