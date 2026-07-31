-- Set time_series compaction for append-only telemetry tables (trace / log / metric).
-- Metadata-only: changes compaction_policy; no data rewrite. Defaults for
-- time_series_compaction_* thresholds are left unchanged.

USE databuff;

ALTER TABLE trace_dc_span SET ("compaction_policy" = "time_series");
ALTER TABLE log_dc_record SET ("compaction_policy" = "time_series");
ALTER TABLE metric_jvm SET ("compaction_policy" = "time_series");
ALTER TABLE metric_service SET ("compaction_policy" = "time_series");
ALTER TABLE metric_service_config SET ("compaction_policy" = "time_series");
ALTER TABLE metric_service_cpu SET ("compaction_policy" = "time_series");
ALTER TABLE metric_service_db SET ("compaction_policy" = "time_series");
ALTER TABLE metric_service_db_connection_pool SET ("compaction_policy" = "time_series");
ALTER TABLE metric_service_db_connection_pool_get SET ("compaction_policy" = "time_series");
ALTER TABLE metric_service_exception SET ("compaction_policy" = "time_series");
ALTER TABLE metric_service_flow SET ("compaction_policy" = "time_series");
ALTER TABLE metric_service_health_status SET ("compaction_policy" = "time_series");
ALTER TABLE metric_service_http SET ("compaction_policy" = "time_series");
ALTER TABLE metric_service_http_connection_pool SET ("compaction_policy" = "time_series");
ALTER TABLE metric_service_http_connection_pool_get SET ("compaction_policy" = "time_series");
ALTER TABLE metric_service_instance SET ("compaction_policy" = "time_series");
ALTER TABLE metric_service_io SET ("compaction_policy" = "time_series");
ALTER TABLE metric_service_mem SET ("compaction_policy" = "time_series");
ALTER TABLE metric_service_mq SET ("compaction_policy" = "time_series");
ALTER TABLE metric_service_net SET ("compaction_policy" = "time_series");
ALTER TABLE metric_service_object_pool SET ("compaction_policy" = "time_series");
ALTER TABLE metric_service_object_pool_get SET ("compaction_policy" = "time_series");
ALTER TABLE metric_service_redis SET ("compaction_policy" = "time_series");
ALTER TABLE metric_service_rpc SET ("compaction_policy" = "time_series");
ALTER TABLE metric_service_remote SET ("compaction_policy" = "time_series");
ALTER TABLE metric_service_tcp SET ("compaction_policy" = "time_series");
ALTER TABLE metric_service_thread_pool SET ("compaction_policy" = "time_series");
ALTER TABLE metric_service_thread_pool_cost SET ("compaction_policy" = "time_series");
ALTER TABLE metric_service_trace SET ("compaction_policy" = "time_series");
