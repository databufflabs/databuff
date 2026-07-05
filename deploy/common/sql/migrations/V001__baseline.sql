-- 0.1.1 → 0.1.2 (step 1): schema version ledger for in-place upgrades.
-- Installs created before the migration framework lack this table; version row
-- is recorded by migrate-schema.sh after each migration file runs.

USE databuff;

CREATE TABLE IF NOT EXISTS schema_version (
  `id`         INT      NOT NULL COMMENT 'singleton row id',
  `version`    INT      NOT NULL COMMENT 'applied migration version',
  `applied_at` DATETIME NOT NULL COMMENT 'last migration time'
) ENGINE=OLAP
UNIQUE KEY(`id`)
DISTRIBUTED BY HASH(`id`) BUCKETS 1
PROPERTIES ("replication_num" = "1");
