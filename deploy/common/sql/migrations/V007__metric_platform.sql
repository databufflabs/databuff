-- 0.1.6 -> v0.1.7: platform self-monitoring metrics (metric_platform).
-- dim: write.* table name, or doris.*.cpu mode; else "". Process / Doris Host in `instance`.
-- ts/metric_time: previous closed minute (flush ~2s after boundary).

USE databuff;

CREATE TABLE IF NOT EXISTS metric_platform (
  `metric_time` DATETIME     NOT NULL COMMENT 'closed minute bucket (Asia/Shanghai)',
  `ts`          BIGINT       NOT NULL COMMENT 'closed minute start epoch ms',
  `component`   VARCHAR(32)  NOT NULL COMMENT 'ingest|web',
  `instance`    VARCHAR(128) NOT NULL COMMENT 'hostname/pod; doris.* uses Doris Host',
  `metric`      VARCHAR(128) NOT NULL COMMENT 'qualified name; dimensions encoded when possible',
  `dim`         VARCHAR(128) NOT NULL DEFAULT "" COMMENT 'write.*: Doris table; doris.*.cpu: mode; else empty',
  `cnt`         BIGINT SUM           COMMENT 'counter delta / timer count',
  `val_sum`     DOUBLE SUM           COMMENT 'timer sum (ms) / bytes sum',
  `val_max`     DOUBLE MAX,
  `val_min`     DOUBLE MIN,
  `val_gauge`   DOUBLE REPLACE       COMMENT 'queue depth / cpu / heap / lag',
  INDEX idx_component (`component`) USING INVERTED COMMENT 'inverted index for tag component',
  INDEX idx_metric (`metric`) USING INVERTED COMMENT 'inverted index for tag metric',
  INDEX idx_instance (`instance`) USING INVERTED COMMENT 'inverted index for tag instance',
  INDEX idx_dim (`dim`) USING INVERTED COMMENT 'inverted index for tag dim'
) ENGINE=OLAP
AGGREGATE KEY(`metric_time`, `ts`, `component`, `instance`, `metric`, `dim`)
PARTITION BY RANGE(`metric_time`) ()
DISTRIBUTED BY HASH(`component`) BUCKETS 3
PROPERTIES (
  "replication_num" = "1",
  "dynamic_partition.enable" = "true",
  "dynamic_partition.time_unit" = "DAY",
  "dynamic_partition.start" = "-30",
  "dynamic_partition.end" = "3",
  "dynamic_partition.prefix" = "p",
  "dynamic_partition.buckets" = "3"
);
