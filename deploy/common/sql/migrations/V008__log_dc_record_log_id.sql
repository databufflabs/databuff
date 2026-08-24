-- v0.1.7 -> next: add per-record log_id to log_dc_record.
-- GitHub #73: 日志分析概览和日志详情显示内容不一致. Root cause is that
-- time_ns is NOT a unique key (DUPLICATE KEY table); high-throughput services
-- share the same nanosecond, so detailSql `WHERE time_ns = ? LIMIT 1` can hit a
-- different row than the one clicked in the list.
-- OTel logs have no built-in per-record id, so ingest generates a 32-hex random
-- id (ThreadLocalRandom, not UUID.randomUUID) and writes it here. The detail
-- query always prunes by a log_time window from time_ns; when log_id is present
-- it pins that row (and service_id when the list row has one). Existing rows
-- keep log_id='' and the same request uses time_ns (+ service_id) instead —
-- one SQL, not a two-step fallback. Fresh installs take the column from
-- databuff.sql; this upgrades existing DBs.
-- Deploy: apply this ALTER and wait for it to finish before rolling web/ingest
-- (new web SELECTs log_id).
-- NOTE: plain `ADD COLUMN` (no `IF NOT EXISTS`) — Doris 4.1.x parser rejects
-- `ADD COLUMN IF NOT EXISTS` ("no viable alternative at input 'ADD COLUMN IF'").
-- Idempotency is provided by the schema_version gate (this migration only runs
-- when going 7 -> 8), not by SQL-level IF NOT EXISTS.
-- This iteration also adds structured alert metric fields; no separate migration
-- version is introduced.

USE databuff;

ALTER TABLE log_dc_record
  ADD COLUMN `log_id` VARCHAR(32) NOT NULL DEFAULT '' COMMENT 'ingest-generated random id (UUID hex); unique per record';

ALTER TABLE config_event
  ADD COLUMN `metric_id` VARCHAR(128) NULL COMMENT 'structured metric identifier behind the message',
  ADD COLUMN `metric_label` VARCHAR(128) NULL COMMENT 'metric display label',
  ADD COLUMN `metric_unit` VARCHAR(32) NULL COMMENT 'metric unit, e.g. %',
  ADD COLUMN `metric_value` DOUBLE NULL COMMENT 'current metric value at trigger time',
  ADD COLUMN `metric_threshold` DOUBLE NULL COMMENT 'breached threshold for the resolved level',
  ADD COLUMN `comparator` VARCHAR(16) NULL COMMENT 'gt|gte|lt|lte';
