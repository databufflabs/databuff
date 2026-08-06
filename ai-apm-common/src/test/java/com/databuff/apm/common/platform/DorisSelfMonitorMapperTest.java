package com.databuff.apm.common.platform;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DorisSelfMonitorMapperTest {

    @Test
    void map_nullSampleOrMissingNameYieldsEmpty() {
        assertThat(DorisSelfMonitorMapper.map(null)).isEmpty();
        assertThat(DorisSelfMonitorMapper.mapAndAggregateTyped(null)).isEmpty();
        assertThat(DorisSelfMonitorMapper.mapAndAggregate(null)).isEmpty();
    }

    @Test
    void map_coversFullBeSwitchMatrix() {
        String body = """
                # TYPE doris_be_cpu counter
                doris_be_cpu{device="cpu",mode="user"} 100
                # TYPE doris_be_memory_allocated_bytes gauge
                doris_be_memory_allocated_bytes 1024
                # TYPE doris_be_disks_local_used_capacity gauge
                doris_be_disks_local_used_capacity{path="/data1"} 55
                # TYPE doris_be_disks_total_capacity gauge
                doris_be_disks_total_capacity{path="/data1"} 100
                # TYPE doris_be_jvm_heap_size_bytes gauge
                doris_be_jvm_heap_size_bytes{type="used"} 500
                # TYPE doris_be_max_network_receive_bytes_rate gauge
                doris_be_max_network_receive_bytes_rate 1
                # TYPE doris_be_max_network_send_bytes_rate gauge
                doris_be_max_network_send_bytes_rate 2
                # TYPE doris_be_compaction_bytes_total counter
                doris_be_compaction_bytes_total{type="cumulative"} 9
                # TYPE doris_be_compaction_mem_bytes gauge
                doris_be_compaction_mem_bytes 7
                # TYPE doris_be_engine_requests_total counter
                doris_be_engine_requests_total{type="read",status="OK"} 5
                # TYPE doris_be_memtable_flush_total counter
                doris_be_memtable_flush_total 6
                # TYPE doris_be_memtable_flush_duration_us counter
                doris_be_memtable_flush_duration_us 8
                # TYPE doris_be_memtable_flush_disk_bytes_total counter
                doris_be_memtable_flush_disk_bytes_total 10
                # TYPE doris_be_memtable_flush_io_time_us counter
                doris_be_memtable_flush_io_time_us 11
                # TYPE doris_be_process_fd_num_limit_hard gauge
                doris_be_process_fd_num_limit_hard 65536
                # TYPE doris_be_process_fd_num_used gauge
                doris_be_process_fd_num_used 100
                # TYPE doris_be_query_scan_bytes counter
                doris_be_query_scan_bytes 12
                # TYPE doris_be_query_scan_rows counter
                doris_be_query_scan_rows 13
                # TYPE doris_be_segment_read counter
                doris_be_segment_read{type="rows"} 14
                # TYPE doris_be_stream_load counter
                doris_be_stream_load{type="rows"} 15
                # TYPE doris_be_tablet_base_max_compaction_score gauge
                doris_be_tablet_base_max_compaction_score 1.5
                # TYPE doris_be_tablet_cumulative_max_compaction_score gauge
                doris_be_tablet_cumulative_max_compaction_score 2.5
                # TYPE doris_be_tablet_update_max_compaction_score gauge
                doris_be_tablet_update_max_compaction_score 3.5
                # TYPE doris_be_thread_pool_active_threads gauge
                doris_be_thread_pool_active_threads{thread_pool_name="pip_pool"} 4
                # TYPE doris_be_stream_load_txn_request counter
                doris_be_stream_load_txn_request{type="success"} 16
                # TYPE doris_be_async_delta_writer_execute_time_ns_total counter
                doris_be_async_delta_writer_execute_time_ns_total 17
                # TYPE doris_be_async_delta_writer_queue_count gauge
                doris_be_async_delta_writer_queue_count 18
                # TYPE doris_be_async_delta_writer_task_pending_duration_us gauge
                doris_be_async_delta_writer_task_pending_duration_us 19
                """;
        Map<String, DorisSelfMonitorMapper.AggregatedMetric> mapped =
                DorisSelfMonitorMapper.mapAndAggregateTyped(DorisPrometheusTextParser.parse(body));

        assertValue(mapped, "doris.cpu\0user", 100.0);
        assertValue(mapped, "doris.process_mem_bytes\0", 1024.0);
        assertValue(mapped, "doris.disks_data_used_capacity.data1\0", 55.0);
        assertValue(mapped, "doris.disks_total_capacity.data1\0", 100.0);
        assertValue(mapped, "doris.jvm_heap_size_bytes.used\0", 500.0);
        assertValue(mapped, "doris.max_network_receive_bytes_rate\0", 1.0);
        assertValue(mapped, "doris.max_network_send_bytes_rate\0", 2.0);
        assertValue(mapped, "doris.compaction_bytes_total.cumulative\0", 9.0);
        assertValue(mapped, "doris.compaction_mem_bytes\0", 7.0);
        assertValue(mapped, "doris.engine_requests_total.read.OK\0", 5.0);
        assertValue(mapped, "doris.memtable_flush_total\0", 6.0);
        assertValue(mapped, "doris.memtable_flush_duration_us\0", 8.0);
        assertValue(mapped, "doris.memtable_flush_disk_bytes_total\0", 10.0);
        assertValue(mapped, "doris.memtable_flush_io_time_us\0", 11.0);
        assertValue(mapped, "doris.process_fd_num_limit_hard\0", 65536.0);
        assertValue(mapped, "doris.process_fd_num_used\0", 100.0);
        assertValue(mapped, "doris.query_scan_bytes\0", 12.0);
        assertValue(mapped, "doris.query_scan_rows\0", 13.0);
        assertValue(mapped, "doris.segment_read.rows\0", 14.0);
        assertValue(mapped, "doris.stream_load.rows\0", 15.0);
        assertValue(mapped, "doris.tablet_base_max_compaction_score\0", 1.5);
        assertValue(mapped, "doris.tablet_cumulative_max_compaction_score\0", 2.5);
        assertValue(mapped, "doris.tablet_update_max_compaction_score\0", 3.5);
        assertValue(mapped, "doris.thread_pool.pip_pool\0", 4.0);
        assertValue(mapped, "doris.txn_request.success\0", 16.0);
        assertValue(mapped, "doris.async_delta_writer_execute_time_ns_total\0", 17.0);
        assertValue(mapped, "doris.async_delta_writer_queue_count\0", 18.0);
        assertValue(mapped, "doris.async_delta_writer_task_pending_duration_us\0", 19.0);

        assertThat(mapped.get("doris.cpu\0user").cumulative()).isTrue();
        assertThat(mapped.get("doris.process_mem_bytes\0").cumulative()).isFalse();
    }

    @Test
    void map_coversFeSwitchMatrixAndSkippedBranches() {
        String body = """
                # TYPE doris_fe_query_total counter
                doris_fe_query_total 20
                doris_fe_query_total{user="hive"} 1
                # TYPE doris_fe_edit_log counter
                doris_fe_edit_log{type="write"} 21
                doris_fe_edit_log{type="other"} 1
                # TYPE doris_fe_image_write counter
                doris_fe_image_write{type="image"} 22
                # TYPE doris_fe_txn_counter counter
                doris_fe_txn_counter{type="begin"} 23
                # TYPE doris_fe_thread_pool gauge
                doris_fe_thread_pool{type="active_thread_num",name="some-pool"} 24
                doris_fe_thread_pool{type="active_thread_num",name="PUBLISH_VERSION_EXEC-1"} 1
                doris_fe_thread_pool{type="active_thread_num"} 1
                doris_fe_thread_pool{type="other"} 1
                # TYPE doris_be_cpu counter
                doris_be_cpu{device="cpu0",mode="user"} 1
                doris_be_cpu{device="cpu"} 1
                # TYPE doris_be_thread_pool_active_threads gauge
                doris_be_thread_pool_active_threads 1
                # TYPE doris_be_jvm_gc counter
                doris_be_jvm_gc{name="G1 Young Generation",type="count"} 3
                doris_be_jvm_gc{name="CMS Old",type="count"} 1
                doris_be_jvm_gc{name="Weird"} 1
                # TYPE doris_be_jvm_heap_size_bytes gauge
                jvm_heap_size_bytes{type="committed"} 99
                # TYPE doris_unknown_metric gauge
                doris_unknown_metric 1
                """;
        Map<String, DorisSelfMonitorMapper.AggregatedMetric> mapped =
                DorisSelfMonitorMapper.mapAndAggregateTyped(DorisPrometheusTextParser.parse(body));

        assertValue(mapped, "doris.query_success\0", 20.0);
        assertValue(mapped, "doris.edit_log_write\0", 21.0);
        assertValue(mapped, "doris.image_write.image\0", 22.0);
        assertValue(mapped, "doris.txn_request.begin\0", 23.0);
        assertValue(mapped, "doris.thread_pool.some-pool\0", 24.0);
        assertValue(mapped, "doris.jvm_young_gc.count\0", 3.0);
        assertValue(mapped, "doris.jvm_old_gc.count\0", 1.0);
        assertValue(mapped, "doris.jvm_heap_size_bytes.committed\0", 99.0);

        assertThat(mapped).doesNotContainKey("doris.cpu\0user");
        assertThat(mapped).doesNotContainKey("doris.query_success\0hive");
        assertThat(mapped).doesNotContainKey("doris.edit_log_write");
        assertThat(mapped).doesNotContainKey("doris.jvm_young_gc\0");
        assertThat(mapped).doesNotContainKey("doris.jvm_old_gc\0");
        assertThat(mapped).doesNotContainKey("doris.thread_pool.PUBLISH_VERSION_EXEC-1");
        assertThat(mapped).doesNotContainKey("doris.thread_pool\0");
        assertThat(mapped).doesNotContainKey("doris.unknown_metric\0");
        assertThat(mapped).doesNotContainKey("doris.cpu\0cpu0");
    }

    @Test
    void withRole_coversIdempotentAndNonDorisPaths() {
        assertThat(DorisSelfMonitorMapper.withRole(null, "be")).isNull();
        assertThat(DorisSelfMonitorMapper.withRole("  ", "be")).isEqualTo("  ");
        assertThat(DorisSelfMonitorMapper.withRole("doris.cpu", "other")).isEqualTo("doris.cpu");
        assertThat(DorisSelfMonitorMapper.withRole("doris.cpu", null)).isEqualTo("doris.cpu");
        assertThat(DorisSelfMonitorMapper.withRole("web.doris.cpu", "fe")).isEqualTo("web.doris.fe.cpu");
        assertThat(DorisSelfMonitorMapper.withRole("ingest.otel.trace.req", "be")).isEqualTo("ingest.otel.trace.req");
        assertThat(DorisSelfMonitorMapper.withRole("doris.be.cpu", "fe")).isEqualTo("doris.be.cpu");
        assertThat(DorisSelfMonitorMapper.withRole("doris.fe.query_success", "be")).isEqualTo("doris.fe.query_success");
    }

    @Test
    void cpuModeDeltasToPercents_clampsNonFiniteAndNegative() {
        Map<String, Double> pct = DorisSelfMonitorMapper.cpuModeDeltasToPercents(Map.of(
                "idle", -5.0,
                "user", Double.NaN,
                "system", Double.POSITIVE_INFINITY,
                "io", 10.0));
        assertThat(pct.get("idle")).isEqualTo(0.0);
        assertThat(pct.get("user")).isEqualTo(0.0);
        assertThat(pct.get("system")).isEqualTo(0.0);
        assertThat(pct.get("io")).isEqualTo(100.0);
    }

    @Test
    void cpuModeDeltasToPercents_nullKeyAndEmptyInputs() {
        assertThat(DorisSelfMonitorMapper.cpuModeDeltasToPercents(null)).isEmpty();
        assertThat(DorisSelfMonitorMapper.cpuModeDeltasToPercents(Map.of())).isEmpty();
        Map<String, Double> pct = DorisSelfMonitorMapper.cpuModeDeltasToPercents(
                Map.of("user", 5.0, "system", 5.0));
        pct.put(null, 0.0);
        assertThat(pct).containsKey(null);
    }

    @Test
    void isLikelyCumulativeLogical_coversPrefixHeuristics() {
        assertThat(DorisSelfMonitorMapper.isLikelyCumulativeLogical(null)).isFalse();
        assertThat(DorisSelfMonitorMapper.isLikelyCumulativeLogical("")).isFalse();
        assertThat(DorisSelfMonitorMapper.isLikelyCumulativeLogical("doris.cpu")).isTrue();
        assertThat(DorisSelfMonitorMapper.isLikelyCumulativeLogical("doris.cpu.user")).isTrue();
        assertThat(DorisSelfMonitorMapper.isLikelyCumulativeLogical("doris.memtable_flush_disk_bytes_total")).isTrue();
        assertThat(DorisSelfMonitorMapper.isLikelyCumulativeLogical("doris.compaction_bytes_total.cumulative")).isTrue();
        assertThat(DorisSelfMonitorMapper.isLikelyCumulativeLogical("doris.engine_requests_total.read")).isTrue();
        assertThat(DorisSelfMonitorMapper.isLikelyCumulativeLogical("doris.segment_read.rows")).isTrue();
        assertThat(DorisSelfMonitorMapper.isLikelyCumulativeLogical("doris.stream_load.rows")).isTrue();
        assertThat(DorisSelfMonitorMapper.isLikelyCumulativeLogical("doris.jvm_young_gc.count")).isTrue();
        assertThat(DorisSelfMonitorMapper.isLikelyCumulativeLogical("doris.jvm_old_gc.count")).isTrue();
        assertThat(DorisSelfMonitorMapper.isLikelyCumulativeLogical("doris.image_write.image")).isTrue();
        assertThat(DorisSelfMonitorMapper.isLikelyCumulativeLogical("doris.txn_request.begin")).isTrue();
        assertThat(DorisSelfMonitorMapper.isLikelyCumulativeLogical("doris.async_delta_writer_execute_time_ns_total")).isTrue();
        assertThat(DorisSelfMonitorMapper.isLikelyCumulativeLogical("doris.query_success")).isTrue();
        assertThat(DorisSelfMonitorMapper.isLikelyCumulativeLogical("doris.query_scan_bytes")).isTrue();
        assertThat(DorisSelfMonitorMapper.isLikelyCumulativeLogical("doris.query_scan_rows")).isTrue();
        assertThat(DorisSelfMonitorMapper.isLikelyCumulativeLogical("doris.edit_log_write")).isTrue();
        assertThat(DorisSelfMonitorMapper.isLikelyCumulativeLogical("doris.memtable_flush_total")).isTrue();
        assertThat(DorisSelfMonitorMapper.isLikelyCumulativeLogical("doris.memtable_flush_duration_us")).isTrue();
        assertThat(DorisSelfMonitorMapper.isLikelyCumulativeLogical("doris.memtable_flush_io_time_us")).isTrue();
        assertThat(DorisSelfMonitorMapper.isLikelyCumulativeLogical("doris.memtable_flush_disk_bytes_total")).isTrue();
        assertThat(DorisSelfMonitorMapper.isLikelyCumulativeLogical("doris.process_mem_bytes")).isFalse();
        assertThat(DorisSelfMonitorMapper.isLikelyCumulativeLogical("doris.jvm_heap_size_bytes.used")).isFalse();
        assertThat(DorisSelfMonitorMapper.isCpuMetric(null)).isFalse();
    }

    @Test
    void map_pathSuffixHandlesTrailingSlashAndBlankPath() {
        String body = """
                # TYPE doris_be_disks_local_used_capacity gauge
                doris_be_disks_local_used_capacity{path="/data/"} 1
                doris_be_disks_local_used_capacity 2
                doris_be_disks_local_used_capacity{path="data"} 3
                """;
        Map<String, DorisSelfMonitorMapper.AggregatedMetric> mapped =
                DorisSelfMonitorMapper.mapAndAggregateTyped(DorisPrometheusTextParser.parse(body));
        assertValue(mapped, "doris.disks_data_used_capacity.data\0", 4.0);
        assertValue(mapped, "doris.disks_data_used_capacity\0", 2.0);
    }

    @Test
    void map_joinSuffixBlankCombinations() {
        String body = """
                # TYPE doris_be_engine_requests_total counter
                doris_be_engine_requests_total{type="read"} 1
                doris_be_engine_requests_total{status="OK"} 2
                doris_be_engine_requests_total 3
                """;
        Map<String, DorisSelfMonitorMapper.AggregatedMetric> mapped =
                DorisSelfMonitorMapper.mapAndAggregateTyped(DorisPrometheusTextParser.parse(body));
        assertValue(mapped, "doris.engine_requests_total.read\0", 1.0);
        assertValue(mapped, "doris.engine_requests_total.OK\0", 2.0);
        assertValue(mapped, "doris.engine_requests_total\0", 3.0);
    }

    @Test
    void map_labelSuffixBlankValue() {
        String body = """
                # TYPE doris_be_jvm_heap_size_bytes gauge
                doris_be_jvm_heap_size_bytes{type=""} 42
                """;
        Map<String, DorisSelfMonitorMapper.AggregatedMetric> mapped =
                DorisSelfMonitorMapper.mapAndAggregateTyped(DorisPrometheusTextParser.parse(body));
        assertValue(mapped, "doris.jvm_heap_size_bytes\0", 42.0);
    }

    @Test
    void mapAndAggregate_mergesWithLegacyDotKey() {
        String body = """
                # TYPE doris_be_cpu counter
                doris_be_cpu{device="cpu",mode="user"} 100
                doris_be_cpu{device="cpu",mode="user"} 50
                """;
        Map<String, Double> agg = DorisSelfMonitorMapper.mapAndAggregate(DorisPrometheusTextParser.parse(body));
        assertThat(agg.get("doris.cpu.user")).isEqualTo(150.0);
    }

    @Test
    void mapAndAggregateTyped_accumulatesSameDimAndKeepsCumulative() {
        String body = """
                # TYPE doris_be_cpu counter
                doris_be_cpu{device="cpu",mode="user"} 100
                doris_be_cpu{device="cpu",mode="user"} 50
                """;
        Map<String, DorisSelfMonitorMapper.AggregatedMetric> mapped =
                DorisSelfMonitorMapper.mapAndAggregateTyped(DorisPrometheusTextParser.parse(body));
        DorisSelfMonitorMapper.AggregatedMetric user = mapped.get("doris.cpu\0user");
        assertThat(user).isNotNull();
        assertThat(user.value()).isEqualTo(150.0);
        assertThat(user.dim()).isEqualTo("user");
        assertThat(user.cumulative()).isTrue();
    }

    private static void assertValue(
            Map<String, DorisSelfMonitorMapper.AggregatedMetric> mapped, String key, double value) {
        DorisSelfMonitorMapper.AggregatedMetric m = mapped.get(key);
        assertThat(m).as("expected key %s in %s", key, mapped.keySet()).isNotNull();
        assertThat(m.value()).isEqualTo(value);
    }
}
