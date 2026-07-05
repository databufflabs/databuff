-- 0.1.1 → 0.1.2: OTLP logs table (see databuff.sql for full definition).
-- Fresh installs already include this table via databuff.sql.

USE databuff;

CREATE TABLE IF NOT EXISTS log_dc_record (
  `log_time`            DATETIME      NOT NULL COMMENT 'wall clock from time_unix_nano',
  `service_id`          VARCHAR(64)   NOT NULL COMMENT 'ServiceKeyUtil.of(service.name)',
  `service`             VARCHAR(255)  NOT NULL,
  `trace_id`            VARCHAR(64)   NOT NULL DEFAULT '' COMMENT 'OTel trace_id hex',
  `span_id`             VARCHAR(64)   NOT NULL DEFAULT '' COMMENT 'OTel span_id hex',
  `hostname`            VARCHAR(255)  NOT NULL DEFAULT '' COMMENT 'OTel host.name',
  `service_instance`    VARCHAR(512)  NOT NULL DEFAULT '' COMMENT 'OTel service.instance.id',
  `severity`            VARCHAR(32)   NOT NULL DEFAULT 'UNSPECIFIED',
  `severity_number`     INT           NOT NULL DEFAULT 0,
  `body`                VARCHAR(65533)         COMMENT 'log message text',
  `attributes_json`     VARCHAR(10000)         COMMENT 'LogRecord attributes JSON',
  `resource_json`       VARCHAR(5000)          COMMENT 'Resource attributes JSON',
  `time_ns`             BIGINT        NOT NULL COMMENT 'original time_unix_nano',
  `observed_time_ns`    BIGINT        NOT NULL DEFAULT 0
) ENGINE=OLAP
DUPLICATE KEY(`log_time`, `service_id`, `service`)
PARTITION BY RANGE(`log_time`) ()
DISTRIBUTED BY HASH(`trace_id`) BUCKETS 16
PROPERTIES (
  "replication_num" = "1",
  "dynamic_partition.enable" = "true",
  "dynamic_partition.time_unit" = "DAY",
  "dynamic_partition.start" = "-30",
  "dynamic_partition.end" = "3",
  "dynamic_partition.prefix" = "p"
);
