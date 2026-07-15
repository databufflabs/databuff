-- Expand VARCHAR lengths for URL / SQL / JSON / resource fields that overflow in production
-- and fail Stream Load / JDBC writes (GitHub #38 and related tables).
-- Fresh installs take the new sizes from databuff.sql; this migration upgrades existing DBs.

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
