-- Expand VARCHAR lengths for URL / SQL / JSON / resource fields that overflow in production
-- and fail Stream Load / JDBC writes (GitHub #38 and related tables).
-- Also folds in GitHub #39: log_dc_record.body VARCHAR(65533) -> STRING for CJK-heavy bodies.
-- Also seeds built-in entry-overview detection rules (avgDuration >1s, error.pct >10%, enabled).
-- Fresh installs take the new sizes (and STRING body) from databuff.sql; this migration upgrades existing DBs.

USE databuff;

-- 1. Spans: URL and resource often exceed 500
ALTER TABLE trace_dc_span MODIFY COLUMN `resource` VARCHAR(4096) NOT NULL;
ALTER TABLE trace_dc_span MODIFY COLUMN `meta.http.url` VARCHAR(4096);

-- 2. Logs: attribute / resource JSON blobs
ALTER TABLE log_dc_record MODIFY COLUMN `attributes_json` VARCHAR(15000) COMMENT 'LogRecord attributes JSON';
ALTER TABLE log_dc_record MODIFY COLUMN `resource_json` VARCHAR(15000) COMMENT 'Resource attributes JSON';

-- 3. Issue #38 core metric tables
ALTER TABLE metric_service_db MODIFY COLUMN `resource` VARCHAR(1024);
ALTER TABLE metric_service_db MODIFY COLUMN `rootResource` VARCHAR(1024);
ALTER TABLE metric_service_db MODIFY COLUMN `sqlContent` VARCHAR(1024);

ALTER TABLE metric_service_http MODIFY COLUMN `resource` VARCHAR(1024);
ALTER TABLE metric_service_http MODIFY COLUMN `rootResource` VARCHAR(1024);
ALTER TABLE metric_service_http MODIFY COLUMN `url` VARCHAR(4096);

ALTER TABLE metric_service_db_connection_pool MODIFY COLUMN `connectionPoolUrl` VARCHAR(4096);

-- 4. Same-pattern resource/rootResource (and related long tags) on other metric tables
ALTER TABLE metric_service_config MODIFY COLUMN `resource` VARCHAR(1024);
ALTER TABLE metric_service_config MODIFY COLUMN `rootResource` VARCHAR(1024);

ALTER TABLE metric_service_exception MODIFY COLUMN `resource` VARCHAR(1024);
ALTER TABLE metric_service_exception MODIFY COLUMN `rootResource` VARCHAR(1024);

ALTER TABLE metric_service_flow MODIFY COLUMN `parentResource` VARCHAR(1024);
ALTER TABLE metric_service_flow MODIFY COLUMN `resource` VARCHAR(1024);

ALTER TABLE metric_service_mq MODIFY COLUMN `resource` VARCHAR(1024);
ALTER TABLE metric_service_mq MODIFY COLUMN `rootResource` VARCHAR(1024);
ALTER TABLE metric_service_mq MODIFY COLUMN `topic` VARCHAR(1024);

ALTER TABLE metric_service_redis MODIFY COLUMN `command` VARCHAR(1024);
ALTER TABLE metric_service_redis MODIFY COLUMN `resource` VARCHAR(1024);
ALTER TABLE metric_service_redis MODIFY COLUMN `rootResource` VARCHAR(1024);

ALTER TABLE metric_service_rpc MODIFY COLUMN `resource` VARCHAR(1024);
ALTER TABLE metric_service_rpc MODIFY COLUMN `rootResource` VARCHAR(1024);

ALTER TABLE metric_service_remote MODIFY COLUMN `resource` VARCHAR(1024);
ALTER TABLE metric_service_remote MODIFY COLUMN `rootResource` VARCHAR(1024);

ALTER TABLE metric_service_thread_pool_cost MODIFY COLUMN `rootResource` VARCHAR(1024);

ALTER TABLE metric_service_trace MODIFY COLUMN `resource` VARCHAR(1024);

-- 5. GitHub #39: log body can exceed VARCHAR(65533) UTF-8 bytes (esp. CJK).
-- STRING default soft limit is ~1MB (BE string_type_length_soft_limit_bytes).
-- Heavyweight schema change: rewrites tablets; monitor with SHOW ALTER TABLE COLUMN.
-- Fresh installs take STRING from databuff.sql; this upgrades existing DBs.
ALTER TABLE log_dc_record MODIFY COLUMN `body` STRING COMMENT 'log message text (STRING; ingest truncates by Java String.length)';

-- 6. Built-in detection rules (all services entry overview, enabled by default).
-- Fresh installs get the same rows from databuff.sql (id 1/2).
-- High ids avoid colliding with user-created rules on existing DBs.
INSERT INTO config_event_rule
  (id, rule_name, classify, detection_way, service, metric, threshold, comparator, enabled, query_json, updated_at)
VALUES
  (900001, '服务入口平均耗时过高', 'singleMetric', 'threshold', '*', 'service.avgDuration', 1000, 'gt', 1,
   '{"1":{"way":"threshold","period":60,"unit":"ms","view_unit":"ms","_scale":1,"time_aggregator":"avg","comparison":">","thresholds":{"critical":1000,"warning":null},"A":{"metric":"service.avgDuration","aggs":"avg","by":["service"],"from":[]}}}',
   NOW()),
  (900002, '服务入口错误率过高', 'singleMetric', 'threshold', '*', 'service.error.pct', 10, 'gt', 1,
   '{"1":{"way":"threshold","period":60,"unit":"%","view_unit":"%","_scale":1,"time_aggregator":"avg","comparison":">","thresholds":{"critical":10,"warning":null},"A":{"metric":"service.error.pct","aggs":"avg","by":["service"],"from":[]}}}',
   NOW());
