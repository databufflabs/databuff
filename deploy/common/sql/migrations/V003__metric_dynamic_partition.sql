-- 0.1.x -> next: metric tables daily dynamic partition (30-day retention).
-- Adds metric_time (DATETIME) partition key; backfills from existing ts (epoch millis).
-- Requires create_history_partition so Doris materializes [today+start, today+end] before INSERT.
-- INSERT keeps only the last 30 days (matches dynamic_partition.start retention).

USE databuff;

SET time_zone = '+08:00';

-- metric_jvm
CREATE TABLE metric_jvm__v3 (

  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `instance` VARCHAR(512),
  `service` VARCHAR(512),
  `service_id` VARCHAR(512),
  `service_instance` VARCHAR(512),
  `tag_host` VARCHAR(512),
  `thread_count` DOUBLE REPLACE,
  `cpu_load_process` DOUBLE REPLACE,
  `cpu_load_system` DOUBLE REPLACE,
  `gc_eden_size` DOUBLE REPLACE,
  `gc_major_collection_count` BIGINT SUM,
  `gc_major_collection_time` DOUBLE SUM,
  `gc_metaspace_size` DOUBLE REPLACE,
  `gc_minor_collection_count` BIGINT SUM,
  `gc_minor_collection_time` DOUBLE SUM,
  `gc_old_gen_size` DOUBLE REPLACE,
  `gc_survivor_size` DOUBLE REPLACE,
  `buffer_pool_direct_capacity` DOUBLE REPLACE,
  `buffer_pool_direct_count` BIGINT SUM,
  `buffer_pool_direct_used` DOUBLE REPLACE,
  `buffer_pool_mapped_capacity` DOUBLE REPLACE,
  `buffer_pool_mapped_count` BIGINT SUM,
  `buffer_pool_mapped_used` DOUBLE REPLACE,
  `loaded_classes_count` DOUBLE REPLACE,
  `memory_heap_committed` DOUBLE REPLACE,
  `memory_heap_init` DOUBLE REPLACE,
  `memory_heap_max` DOUBLE REPLACE,
  `memory_heap_used` DOUBLE REPLACE,
  `memory_heap_free` DOUBLE REPLACE,
  `memory_heap_pct` DOUBLE REPLACE,
  `memory_noheap_committed` DOUBLE REPLACE,
  `memory_noheap_init` DOUBLE REPLACE,
  `memory_noheap_max` DOUBLE REPLACE,
  `memory_noheap_used` DOUBLE REPLACE,
  INDEX idx_instance (`instance`) USING INVERTED COMMENT 'inverted index for tag instance',
  INDEX idx_service (`service`) USING INVERTED COMMENT 'inverted index for tag service',
  INDEX idx_service_id (`service_id`) USING INVERTED COMMENT 'inverted index for tag service_id',
  INDEX idx_service_instance (`service_instance`) USING INVERTED COMMENT 'inverted index for tag service_instance',
  INDEX idx_tag_host (`tag_host`) USING INVERTED COMMENT 'inverted index for tag tag_host'
) ENGINE=OLAP
AGGREGATE KEY(`metric_time`, `ts`, `instance`, `service`, `service_id`, `service_instance`, `tag_host`)
PARTITION BY RANGE(`metric_time`) ()
DISTRIBUTED BY HASH(`service_id`) BUCKETS 3
PROPERTIES (
  "replication_num" = "1",
  "dynamic_partition.enable" = "true",
  "dynamic_partition.time_unit" = "DAY",
  "dynamic_partition.start" = "-30",
  "dynamic_partition.end" = "3",
  "dynamic_partition.prefix" = "p",
  "dynamic_partition.create_history_partition" = "true"
);
INSERT INTO metric_jvm__v3 (`metric_time`, `ts`, `instance`, `service`, `service_id`, `service_instance`, `tag_host`, `thread_count`, `cpu_load_process`, `cpu_load_system`, `gc_eden_size`, `gc_major_collection_count`, `gc_major_collection_time`, `gc_metaspace_size`, `gc_minor_collection_count`, `gc_minor_collection_time`, `gc_old_gen_size`, `gc_survivor_size`, `buffer_pool_direct_capacity`, `buffer_pool_direct_count`, `buffer_pool_direct_used`, `buffer_pool_mapped_capacity`, `buffer_pool_mapped_count`, `buffer_pool_mapped_used`, `loaded_classes_count`, `memory_heap_committed`, `memory_heap_init`, `memory_heap_max`, `memory_heap_used`, `memory_heap_free`, `memory_heap_pct`, `memory_noheap_committed`, `memory_noheap_init`, `memory_noheap_max`, `memory_noheap_used`)
SELECT FROM_UNIXTIME(FLOOR(`ts` / 1000)) AS `metric_time`, `ts`, `instance`, `service`, `service_id`, `service_instance`, `tag_host`, `thread_count`, `cpu_load_process`, `cpu_load_system`, `gc_eden_size`, `gc_major_collection_count`, `gc_major_collection_time`, `gc_metaspace_size`, `gc_minor_collection_count`, `gc_minor_collection_time`, `gc_old_gen_size`, `gc_survivor_size`, `buffer_pool_direct_capacity`, `buffer_pool_direct_count`, `buffer_pool_direct_used`, `buffer_pool_mapped_capacity`, `buffer_pool_mapped_count`, `buffer_pool_mapped_used`, `loaded_classes_count`, `memory_heap_committed`, `memory_heap_init`, `memory_heap_max`, `memory_heap_used`, `memory_heap_free`, `memory_heap_pct`, `memory_noheap_committed`, `memory_noheap_init`, `memory_noheap_max`, `memory_noheap_used` FROM metric_jvm WHERE FROM_UNIXTIME(FLOOR(`ts` / 1000)) >= DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY);
DROP TABLE metric_jvm;
ALTER TABLE metric_jvm__v3 RENAME metric_jvm;

-- metric_service
CREATE TABLE metric_service__v3 (

  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `errorType` VARCHAR(512),
  `service` VARCHAR(512),
  `service_id` VARCHAR(512),
  `service_instance` VARCHAR(512),
  `apdex` DOUBLE SUM,
  `cnt` BIGINT SUM,
  `error` BIGINT SUM,
  `healthStatus` DOUBLE SUM,
  `histogramCount` BIGINT SUM,
  `histogramMax` DOUBLE MAX,
  `maxDuration` DOUBLE MAX,
  `minDuration` DOUBLE MIN,
  `reqBodyLength` DOUBLE SUM,
  `respBodyLength` DOUBLE SUM,
  `slowCnt` BIGINT SUM,
  `sumCpuTime` DOUBLE SUM,
  `sumDuration` DOUBLE SUM,
  `verySlowCnt` BIGINT SUM,
  INDEX idx_errorType (`errorType`) USING INVERTED COMMENT 'inverted index for tag errorType',
  INDEX idx_service (`service`) USING INVERTED COMMENT 'inverted index for tag service',
  INDEX idx_service_id (`service_id`) USING INVERTED COMMENT 'inverted index for tag service_id',
  INDEX idx_service_instance (`service_instance`) USING INVERTED COMMENT 'inverted index for tag service_instance'
) ENGINE=OLAP
AGGREGATE KEY(`metric_time`, `ts`, `errorType`, `service`, `service_id`, `service_instance`)
PARTITION BY RANGE(`metric_time`) ()
DISTRIBUTED BY HASH(`service_id`) BUCKETS 3
PROPERTIES (
  "replication_num" = "1",
  "dynamic_partition.enable" = "true",
  "dynamic_partition.time_unit" = "DAY",
  "dynamic_partition.start" = "-30",
  "dynamic_partition.end" = "3",
  "dynamic_partition.prefix" = "p",
  "dynamic_partition.create_history_partition" = "true"
);
INSERT INTO metric_service__v3 (`metric_time`, `ts`, `errorType`, `service`, `service_id`, `service_instance`, `apdex`, `cnt`, `error`, `healthStatus`, `histogramCount`, `histogramMax`, `maxDuration`, `minDuration`, `reqBodyLength`, `respBodyLength`, `slowCnt`, `sumCpuTime`, `sumDuration`, `verySlowCnt`)
SELECT FROM_UNIXTIME(FLOOR(`ts` / 1000)) AS `metric_time`, `ts`, `errorType`, `service`, `service_id`, `service_instance`, `apdex`, `cnt`, `error`, `healthStatus`, `histogramCount`, `histogramMax`, `maxDuration`, `minDuration`, `reqBodyLength`, `respBodyLength`, `slowCnt`, `sumCpuTime`, `sumDuration`, `verySlowCnt` FROM metric_service WHERE FROM_UNIXTIME(FLOOR(`ts` / 1000)) >= DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY);
DROP TABLE metric_service;
ALTER TABLE metric_service__v3 RENAME metric_service;

-- metric_service_config
CREATE TABLE metric_service_config__v3 (

  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `config.type` VARCHAR(512),
  `durationRange` VARCHAR(512),
  `isIn` VARCHAR(512),
  `isOut` VARCHAR(512),
  `operation` VARCHAR(512),
  `resource` VARCHAR(512),
  `rootComponentType` VARCHAR(512),
  `rootResource` VARCHAR(512),
  `service` VARCHAR(512),
  `service_id` VARCHAR(512),
  `service_instance` VARCHAR(512),
  `srcService` VARCHAR(512),
  `srcServiceId` VARCHAR(512),
  `srcServiceInstance` VARCHAR(512),
  `cnt` BIGINT SUM,
  `error` BIGINT SUM,
  `histogramCount` BIGINT SUM,
  `histogramMax` DOUBLE MAX,
  `maxDuration` DOUBLE MAX,
  `minDuration` DOUBLE MIN,
  `slow` BIGINT SUM,
  `sumDuration` DOUBLE SUM,
  INDEX idx_config_type (`config.type`) USING INVERTED COMMENT 'inverted index for tag config.type',
  INDEX idx_durationRange (`durationRange`) USING INVERTED COMMENT 'inverted index for tag durationRange',
  INDEX idx_isIn (`isIn`) USING INVERTED COMMENT 'inverted index for tag isIn',
  INDEX idx_isOut (`isOut`) USING INVERTED COMMENT 'inverted index for tag isOut',
  INDEX idx_operation (`operation`) USING INVERTED COMMENT 'inverted index for tag operation',
  INDEX idx_resource (`resource`) USING INVERTED COMMENT 'inverted index for tag resource',
  INDEX idx_rootComponentType (`rootComponentType`) USING INVERTED COMMENT 'inverted index for tag rootComponentType',
  INDEX idx_rootResource (`rootResource`) USING INVERTED COMMENT 'inverted index for tag rootResource',
  INDEX idx_service (`service`) USING INVERTED COMMENT 'inverted index for tag service',
  INDEX idx_service_id (`service_id`) USING INVERTED COMMENT 'inverted index for tag service_id',
  INDEX idx_service_instance (`service_instance`) USING INVERTED COMMENT 'inverted index for tag service_instance',
  INDEX idx_srcService (`srcService`) USING INVERTED COMMENT 'inverted index for tag srcService',
  INDEX idx_srcServiceId (`srcServiceId`) USING INVERTED COMMENT 'inverted index for tag srcServiceId',
  INDEX idx_srcServiceInstance (`srcServiceInstance`) USING INVERTED COMMENT 'inverted index for tag srcServiceInstance'
) ENGINE=OLAP
AGGREGATE KEY(`metric_time`, `ts`, `config.type`, `durationRange`, `isIn`, `isOut`, `operation`, `resource`, `rootComponentType`, `rootResource`, `service`, `service_id`, `service_instance`, `srcService`, `srcServiceId`, `srcServiceInstance`)
PARTITION BY RANGE(`metric_time`) ()
DISTRIBUTED BY HASH(`service_id`) BUCKETS 3
PROPERTIES (
  "replication_num" = "1",
  "dynamic_partition.enable" = "true",
  "dynamic_partition.time_unit" = "DAY",
  "dynamic_partition.start" = "-30",
  "dynamic_partition.end" = "3",
  "dynamic_partition.prefix" = "p",
  "dynamic_partition.create_history_partition" = "true"
);
INSERT INTO metric_service_config__v3 (`metric_time`, `ts`, `config.type`, `durationRange`, `isIn`, `isOut`, `operation`, `resource`, `rootComponentType`, `rootResource`, `service`, `service_id`, `service_instance`, `srcService`, `srcServiceId`, `srcServiceInstance`, `cnt`, `error`, `histogramCount`, `histogramMax`, `maxDuration`, `minDuration`, `slow`, `sumDuration`)
SELECT FROM_UNIXTIME(FLOOR(`ts` / 1000)) AS `metric_time`, `ts`, `config.type`, `durationRange`, `isIn`, `isOut`, `operation`, `resource`, `rootComponentType`, `rootResource`, `service`, `service_id`, `service_instance`, `srcService`, `srcServiceId`, `srcServiceInstance`, `cnt`, `error`, `histogramCount`, `histogramMax`, `maxDuration`, `minDuration`, `slow`, `sumDuration` FROM metric_service_config WHERE FROM_UNIXTIME(FLOOR(`ts` / 1000)) >= DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY);
DROP TABLE metric_service_config;
ALTER TABLE metric_service_config__v3 RENAME metric_service_config;

-- metric_service_cpu
CREATE TABLE metric_service_cpu__v3 (

  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `service` VARCHAR(512),
  `serviceCode` VARCHAR(512),
  `service_id` VARCHAR(512),
  `service_instance` VARCHAR(512),
  `usage_pct` DOUBLE SUM,
  INDEX idx_service (`service`) USING INVERTED COMMENT 'inverted index for tag service',
  INDEX idx_serviceCode (`serviceCode`) USING INVERTED COMMENT 'inverted index for tag serviceCode',
  INDEX idx_service_id (`service_id`) USING INVERTED COMMENT 'inverted index for tag service_id',
  INDEX idx_service_instance (`service_instance`) USING INVERTED COMMENT 'inverted index for tag service_instance'
) ENGINE=OLAP
AGGREGATE KEY(`metric_time`, `ts`, `service`, `serviceCode`, `service_id`, `service_instance`)
PARTITION BY RANGE(`metric_time`) ()
DISTRIBUTED BY HASH(`service_id`) BUCKETS 3
PROPERTIES (
  "replication_num" = "1",
  "dynamic_partition.enable" = "true",
  "dynamic_partition.time_unit" = "DAY",
  "dynamic_partition.start" = "-30",
  "dynamic_partition.end" = "3",
  "dynamic_partition.prefix" = "p",
  "dynamic_partition.create_history_partition" = "true"
);
INSERT INTO metric_service_cpu__v3 (`metric_time`, `ts`, `service`, `serviceCode`, `service_id`, `service_instance`, `usage_pct`)
SELECT FROM_UNIXTIME(FLOOR(`ts` / 1000)) AS `metric_time`, `ts`, `service`, `serviceCode`, `service_id`, `service_instance`, `usage_pct` FROM metric_service_cpu WHERE FROM_UNIXTIME(FLOOR(`ts` / 1000)) >= DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY);
DROP TABLE metric_service_cpu;
ALTER TABLE metric_service_cpu__v3 RENAME metric_service_cpu;

-- metric_service_db
CREATE TABLE metric_service_db__v3 (

  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `dbType` VARCHAR(512),
  `durationRange` VARCHAR(512),
  `isIn` VARCHAR(512),
  `isOut` VARCHAR(512),
  `isSlow` VARCHAR(512),
  `resource` VARCHAR(512),
  `rootComponentType` VARCHAR(512),
  `rootResource` VARCHAR(512),
  `service` VARCHAR(512),
  `service_id` VARCHAR(512),
  `service_instance` VARCHAR(512),
  `sqlContent` VARCHAR(512),
  `sqlDatabase` VARCHAR(512),
  `sqlOperation` VARCHAR(512),
  `srcService` VARCHAR(512),
  `srcServiceId` VARCHAR(512),
  `srcServiceInstance` VARCHAR(512),
  `cnt` BIGINT SUM,
  `error` BIGINT SUM,
  `histogramCount` BIGINT SUM,
  `histogramMax` DOUBLE MAX,
  `maxDuration` DOUBLE MAX,
  `minDuration` DOUBLE MIN,
  `readRows` DOUBLE SUM,
  `readRowsCnt` BIGINT SUM,
  `slow` BIGINT SUM,
  `slowCnt` BIGINT SUM,
  `sumDuration` DOUBLE SUM,
  `updateRows` DOUBLE SUM,
  `updateRowsCnt` BIGINT SUM,
  INDEX idx_dbType (`dbType`) USING INVERTED COMMENT 'inverted index for tag dbType',
  INDEX idx_durationRange (`durationRange`) USING INVERTED COMMENT 'inverted index for tag durationRange',
  INDEX idx_isIn (`isIn`) USING INVERTED COMMENT 'inverted index for tag isIn',
  INDEX idx_isOut (`isOut`) USING INVERTED COMMENT 'inverted index for tag isOut',
  INDEX idx_isSlow (`isSlow`) USING INVERTED COMMENT 'inverted index for tag isSlow',
  INDEX idx_resource (`resource`) USING INVERTED COMMENT 'inverted index for tag resource',
  INDEX idx_rootComponentType (`rootComponentType`) USING INVERTED COMMENT 'inverted index for tag rootComponentType',
  INDEX idx_rootResource (`rootResource`) USING INVERTED COMMENT 'inverted index for tag rootResource',
  INDEX idx_service (`service`) USING INVERTED COMMENT 'inverted index for tag service',
  INDEX idx_service_id (`service_id`) USING INVERTED COMMENT 'inverted index for tag service_id',
  INDEX idx_service_instance (`service_instance`) USING INVERTED COMMENT 'inverted index for tag service_instance',
  INDEX idx_sqlContent (`sqlContent`) USING INVERTED COMMENT 'inverted index for tag sqlContent',
  INDEX idx_sqlDatabase (`sqlDatabase`) USING INVERTED COMMENT 'inverted index for tag sqlDatabase',
  INDEX idx_sqlOperation (`sqlOperation`) USING INVERTED COMMENT 'inverted index for tag sqlOperation',
  INDEX idx_srcService (`srcService`) USING INVERTED COMMENT 'inverted index for tag srcService',
  INDEX idx_srcServiceId (`srcServiceId`) USING INVERTED COMMENT 'inverted index for tag srcServiceId',
  INDEX idx_srcServiceInstance (`srcServiceInstance`) USING INVERTED COMMENT 'inverted index for tag srcServiceInstance'
) ENGINE=OLAP
AGGREGATE KEY(`metric_time`, `ts`, `dbType`, `durationRange`, `isIn`, `isOut`, `isSlow`, `resource`, `rootComponentType`, `rootResource`, `service`, `service_id`, `service_instance`, `sqlContent`, `sqlDatabase`, `sqlOperation`, `srcService`, `srcServiceId`, `srcServiceInstance`)
PARTITION BY RANGE(`metric_time`) ()
DISTRIBUTED BY HASH(`service_id`) BUCKETS 3
PROPERTIES (
  "replication_num" = "1",
  "dynamic_partition.enable" = "true",
  "dynamic_partition.time_unit" = "DAY",
  "dynamic_partition.start" = "-30",
  "dynamic_partition.end" = "3",
  "dynamic_partition.prefix" = "p",
  "dynamic_partition.create_history_partition" = "true"
);
INSERT INTO metric_service_db__v3 (`metric_time`, `ts`, `dbType`, `durationRange`, `isIn`, `isOut`, `isSlow`, `resource`, `rootComponentType`, `rootResource`, `service`, `service_id`, `service_instance`, `sqlContent`, `sqlDatabase`, `sqlOperation`, `srcService`, `srcServiceId`, `srcServiceInstance`, `cnt`, `error`, `histogramCount`, `histogramMax`, `maxDuration`, `minDuration`, `readRows`, `readRowsCnt`, `slow`, `slowCnt`, `sumDuration`, `updateRows`, `updateRowsCnt`)
SELECT FROM_UNIXTIME(FLOOR(`ts` / 1000)) AS `metric_time`, `ts`, `dbType`, `durationRange`, `isIn`, `isOut`, `isSlow`, `resource`, `rootComponentType`, `rootResource`, `service`, `service_id`, `service_instance`, `sqlContent`, `sqlDatabase`, `sqlOperation`, `srcService`, `srcServiceId`, `srcServiceInstance`, `cnt`, `error`, `histogramCount`, `histogramMax`, `maxDuration`, `minDuration`, `readRows`, `readRowsCnt`, `slow`, `slowCnt`, `sumDuration`, `updateRows`, `updateRowsCnt` FROM metric_service_db WHERE FROM_UNIXTIME(FLOOR(`ts` / 1000)) >= DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY);
DROP TABLE metric_service_db;
ALTER TABLE metric_service_db__v3 RENAME metric_service_db;

-- metric_service_db_connection_pool
CREATE TABLE metric_service_db_connection_pool__v3 (

  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `connectionPoolDbType` VARCHAR(512),
  `connectionPoolName` VARCHAR(512),
  `connectionPoolType` VARCHAR(512),
  `connectionPoolUrl` VARCHAR(512),
  `connectionPoolUsername` VARCHAR(512),
  `driverClassName` VARCHAR(512),
  `service` VARCHAR(512),
  `service_id` VARCHAR(512),
  `service_instance` VARCHAR(512),
  `activeSize` DOUBLE SUM,
  `idleSize` DOUBLE SUM,
  `maxSize` DOUBLE SUM,
  `waiterNum` DOUBLE SUM,
  INDEX idx_connectionPoolDbType (`connectionPoolDbType`) USING INVERTED COMMENT 'inverted index for tag connectionPoolDbType',
  INDEX idx_connectionPoolName (`connectionPoolName`) USING INVERTED COMMENT 'inverted index for tag connectionPoolName',
  INDEX idx_connectionPoolType (`connectionPoolType`) USING INVERTED COMMENT 'inverted index for tag connectionPoolType',
  INDEX idx_connectionPoolUrl (`connectionPoolUrl`) USING INVERTED COMMENT 'inverted index for tag connectionPoolUrl',
  INDEX idx_connectionPoolUsername (`connectionPoolUsername`) USING INVERTED COMMENT 'inverted index for tag connectionPoolUsername',
  INDEX idx_driverClassName (`driverClassName`) USING INVERTED COMMENT 'inverted index for tag driverClassName',
  INDEX idx_service (`service`) USING INVERTED COMMENT 'inverted index for tag service',
  INDEX idx_service_id (`service_id`) USING INVERTED COMMENT 'inverted index for tag service_id',
  INDEX idx_service_instance (`service_instance`) USING INVERTED COMMENT 'inverted index for tag service_instance'
) ENGINE=OLAP
AGGREGATE KEY(`metric_time`, `ts`, `connectionPoolDbType`, `connectionPoolName`, `connectionPoolType`, `connectionPoolUrl`, `connectionPoolUsername`, `driverClassName`, `service`, `service_id`, `service_instance`)
PARTITION BY RANGE(`metric_time`) ()
DISTRIBUTED BY HASH(`service_id`) BUCKETS 3
PROPERTIES (
  "replication_num" = "1",
  "dynamic_partition.enable" = "true",
  "dynamic_partition.time_unit" = "DAY",
  "dynamic_partition.start" = "-30",
  "dynamic_partition.end" = "3",
  "dynamic_partition.prefix" = "p",
  "dynamic_partition.create_history_partition" = "true"
);
INSERT INTO metric_service_db_connection_pool__v3 (`metric_time`, `ts`, `connectionPoolDbType`, `connectionPoolName`, `connectionPoolType`, `connectionPoolUrl`, `connectionPoolUsername`, `driverClassName`, `service`, `service_id`, `service_instance`, `activeSize`, `idleSize`, `maxSize`, `waiterNum`)
SELECT FROM_UNIXTIME(FLOOR(`ts` / 1000)) AS `metric_time`, `ts`, `connectionPoolDbType`, `connectionPoolName`, `connectionPoolType`, `connectionPoolUrl`, `connectionPoolUsername`, `driverClassName`, `service`, `service_id`, `service_instance`, `activeSize`, `idleSize`, `maxSize`, `waiterNum` FROM metric_service_db_connection_pool WHERE FROM_UNIXTIME(FLOOR(`ts` / 1000)) >= DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY);
DROP TABLE metric_service_db_connection_pool;
ALTER TABLE metric_service_db_connection_pool__v3 RENAME metric_service_db_connection_pool;

-- metric_service_db_connection_pool_get
CREATE TABLE metric_service_db_connection_pool_get__v3 (

  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `connectionPoolName` VARCHAR(512),
  `service` VARCHAR(512),
  `service_id` VARCHAR(512),
  `service_instance` VARCHAR(512),
  `waitTime` DOUBLE SUM,
  `count` DOUBLE SUM,
  INDEX idx_connectionPoolName (`connectionPoolName`) USING INVERTED COMMENT 'inverted index for tag connectionPoolName',
  INDEX idx_service (`service`) USING INVERTED COMMENT 'inverted index for tag service',
  INDEX idx_service_id (`service_id`) USING INVERTED COMMENT 'inverted index for tag service_id',
  INDEX idx_service_instance (`service_instance`) USING INVERTED COMMENT 'inverted index for tag service_instance'
) ENGINE=OLAP
AGGREGATE KEY(`metric_time`, `ts`, `connectionPoolName`, `service`, `service_id`, `service_instance`)
PARTITION BY RANGE(`metric_time`) ()
DISTRIBUTED BY HASH(`service_id`) BUCKETS 3
PROPERTIES (
  "replication_num" = "1",
  "dynamic_partition.enable" = "true",
  "dynamic_partition.time_unit" = "DAY",
  "dynamic_partition.start" = "-30",
  "dynamic_partition.end" = "3",
  "dynamic_partition.prefix" = "p",
  "dynamic_partition.create_history_partition" = "true"
);
INSERT INTO metric_service_db_connection_pool_get__v3 (`metric_time`, `ts`, `connectionPoolName`, `service`, `service_id`, `service_instance`, `waitTime`, `count`)
SELECT FROM_UNIXTIME(FLOOR(`ts` / 1000)) AS `metric_time`, `ts`, `connectionPoolName`, `service`, `service_id`, `service_instance`, `waitTime`, `count` FROM metric_service_db_connection_pool_get WHERE FROM_UNIXTIME(FLOOR(`ts` / 1000)) >= DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY);
DROP TABLE metric_service_db_connection_pool_get;
ALTER TABLE metric_service_db_connection_pool_get__v3 RENAME metric_service_db_connection_pool_get;

-- metric_service_exception
CREATE TABLE metric_service_exception__v3 (

  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `componentService` VARCHAR(512),
  `componentServiceId` VARCHAR(512),
  `componentServiceInstance` VARCHAR(512),
  `exceptionCode` VARCHAR(512),
  `exceptionName` VARCHAR(512),
  `isIn` VARCHAR(512),
  `isOut` VARCHAR(512),
  `resource` VARCHAR(512),
  `rootComponentType` VARCHAR(512),
  `rootResource` VARCHAR(512),
  `service` VARCHAR(512),
  `service_id` VARCHAR(512),
  `service_instance` VARCHAR(512),
  `cnt` BIGINT SUM,
  `error` BIGINT SUM,
  INDEX idx_componentService (`componentService`) USING INVERTED COMMENT 'inverted index for tag componentService',
  INDEX idx_componentServiceId (`componentServiceId`) USING INVERTED COMMENT 'inverted index for tag componentServiceId',
  INDEX idx_componentServiceInstance (`componentServiceInstance`) USING INVERTED COMMENT 'inverted index for tag componentServiceInstance',
  INDEX idx_exceptionCode (`exceptionCode`) USING INVERTED COMMENT 'inverted index for tag exceptionCode',
  INDEX idx_exceptionName (`exceptionName`) USING INVERTED COMMENT 'inverted index for tag exceptionName',
  INDEX idx_isIn (`isIn`) USING INVERTED COMMENT 'inverted index for tag isIn',
  INDEX idx_isOut (`isOut`) USING INVERTED COMMENT 'inverted index for tag isOut',
  INDEX idx_resource (`resource`) USING INVERTED COMMENT 'inverted index for tag resource',
  INDEX idx_rootComponentType (`rootComponentType`) USING INVERTED COMMENT 'inverted index for tag rootComponentType',
  INDEX idx_rootResource (`rootResource`) USING INVERTED COMMENT 'inverted index for tag rootResource',
  INDEX idx_service (`service`) USING INVERTED COMMENT 'inverted index for tag service',
  INDEX idx_service_id (`service_id`) USING INVERTED COMMENT 'inverted index for tag service_id',
  INDEX idx_service_instance (`service_instance`) USING INVERTED COMMENT 'inverted index for tag service_instance'
) ENGINE=OLAP
AGGREGATE KEY(`metric_time`, `ts`, `componentService`, `componentServiceId`, `componentServiceInstance`, `exceptionCode`, `exceptionName`, `isIn`, `isOut`, `resource`, `rootComponentType`, `rootResource`, `service`, `service_id`, `service_instance`)
PARTITION BY RANGE(`metric_time`) ()
DISTRIBUTED BY HASH(`service_id`) BUCKETS 3
PROPERTIES (
  "replication_num" = "1",
  "dynamic_partition.enable" = "true",
  "dynamic_partition.time_unit" = "DAY",
  "dynamic_partition.start" = "-30",
  "dynamic_partition.end" = "3",
  "dynamic_partition.prefix" = "p",
  "dynamic_partition.create_history_partition" = "true"
);
INSERT INTO metric_service_exception__v3 (`metric_time`, `ts`, `componentService`, `componentServiceId`, `componentServiceInstance`, `exceptionCode`, `exceptionName`, `isIn`, `isOut`, `resource`, `rootComponentType`, `rootResource`, `service`, `service_id`, `service_instance`, `cnt`, `error`)
SELECT FROM_UNIXTIME(FLOOR(`ts` / 1000)) AS `metric_time`, `ts`, `componentService`, `componentServiceId`, `componentServiceInstance`, `exceptionCode`, `exceptionName`, `isIn`, `isOut`, `resource`, `rootComponentType`, `rootResource`, `service`, `service_id`, `service_instance`, `cnt`, `error` FROM metric_service_exception WHERE FROM_UNIXTIME(FLOOR(`ts` / 1000)) >= DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY);
DROP TABLE metric_service_exception;
ALTER TABLE metric_service_exception__v3 RENAME metric_service_exception;

-- metric_service_flow
CREATE TABLE metric_service_flow__v3 (

  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `entryInterfacePathId` VARCHAR(512),
  `entryPathId` VARCHAR(512),
  `interfacePathId` VARCHAR(512),
  `isIn` VARCHAR(512),
  `parentInterfacePathId` VARCHAR(512),
  `parentPathId` VARCHAR(512),
  `parentResource` VARCHAR(512),
  `parentService` VARCHAR(512),
  `parentServiceId` VARCHAR(512),
  `pathId` VARCHAR(512),
  `resource` VARCHAR(512),
  `service` VARCHAR(512),
  `service_id` VARCHAR(512),
  `cnt` BIGINT SUM,
  `error` BIGINT SUM,
  `slow` BIGINT SUM,
  `srcCall` BIGINT SUM,
  `sumDuration` DOUBLE SUM,
  INDEX idx_entryInterfacePathId (`entryInterfacePathId`) USING INVERTED COMMENT 'inverted index for tag entryInterfacePathId',
  INDEX idx_entryPathId (`entryPathId`) USING INVERTED COMMENT 'inverted index for tag entryPathId',
  INDEX idx_interfacePathId (`interfacePathId`) USING INVERTED COMMENT 'inverted index for tag interfacePathId',
  INDEX idx_isIn (`isIn`) USING INVERTED COMMENT 'inverted index for tag isIn',
  INDEX idx_parentInterfacePathId (`parentInterfacePathId`) USING INVERTED COMMENT 'inverted index for tag parentInterfacePathId',
  INDEX idx_parentPathId (`parentPathId`) USING INVERTED COMMENT 'inverted index for tag parentPathId',
  INDEX idx_parentResource (`parentResource`) USING INVERTED COMMENT 'inverted index for tag parentResource',
  INDEX idx_parentService (`parentService`) USING INVERTED COMMENT 'inverted index for tag parentService',
  INDEX idx_parentServiceId (`parentServiceId`) USING INVERTED COMMENT 'inverted index for tag parentServiceId',
  INDEX idx_pathId (`pathId`) USING INVERTED COMMENT 'inverted index for tag pathId',
  INDEX idx_resource (`resource`) USING INVERTED COMMENT 'inverted index for tag resource',
  INDEX idx_service (`service`) USING INVERTED COMMENT 'inverted index for tag service',
  INDEX idx_service_id (`service_id`) USING INVERTED COMMENT 'inverted index for tag service_id'
) ENGINE=OLAP
AGGREGATE KEY(`metric_time`, `ts`, `entryInterfacePathId`, `entryPathId`, `interfacePathId`, `isIn`, `parentInterfacePathId`, `parentPathId`, `parentResource`, `parentService`, `parentServiceId`, `pathId`, `resource`, `service`, `service_id`)
PARTITION BY RANGE(`metric_time`) ()
DISTRIBUTED BY HASH(`service_id`) BUCKETS 3
PROPERTIES (
  "replication_num" = "1",
  "dynamic_partition.enable" = "true",
  "dynamic_partition.time_unit" = "DAY",
  "dynamic_partition.start" = "-30",
  "dynamic_partition.end" = "3",
  "dynamic_partition.prefix" = "p",
  "dynamic_partition.create_history_partition" = "true"
);
INSERT INTO metric_service_flow__v3 (`metric_time`, `ts`, `entryInterfacePathId`, `entryPathId`, `interfacePathId`, `isIn`, `parentInterfacePathId`, `parentPathId`, `parentResource`, `parentService`, `parentServiceId`, `pathId`, `resource`, `service`, `service_id`, `cnt`, `error`, `slow`, `srcCall`, `sumDuration`)
SELECT FROM_UNIXTIME(FLOOR(`ts` / 1000)) AS `metric_time`, `ts`, `entryInterfacePathId`, `entryPathId`, `interfacePathId`, `isIn`, `parentInterfacePathId`, `parentPathId`, `parentResource`, `parentService`, `parentServiceId`, `pathId`, `resource`, `service`, `service_id`, `cnt`, `error`, `slow`, `srcCall`, `sumDuration` FROM metric_service_flow WHERE FROM_UNIXTIME(FLOOR(`ts` / 1000)) >= DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY);
DROP TABLE metric_service_flow;
ALTER TABLE metric_service_flow__v3 RENAME metric_service_flow;

-- metric_service_health_status
CREATE TABLE metric_service_health_status__v3 (

  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `convergenceType` VARCHAR(512),
  `gid` VARCHAR(512),
  `host` VARCHAR(512),
  `level` VARCHAR(512),
  `policyId` VARCHAR(512),
  `policyName` VARCHAR(512),
  `problemId` VARCHAR(512),
  `service` VARCHAR(512),
  `service_id` VARCHAR(512),
  `service_instance` VARCHAR(512),
  `metricsVal` BIGINT SUM,
  INDEX idx_convergenceType (`convergenceType`) USING INVERTED COMMENT 'inverted index for tag convergenceType',
  INDEX idx_gid (`gid`) USING INVERTED COMMENT 'inverted index for tag gid',
  INDEX idx_host (`host`) USING INVERTED COMMENT 'inverted index for tag host',
  INDEX idx_level (`level`) USING INVERTED COMMENT 'inverted index for tag level',
  INDEX idx_policyId (`policyId`) USING INVERTED COMMENT 'inverted index for tag policyId',
  INDEX idx_policyName (`policyName`) USING INVERTED COMMENT 'inverted index for tag policyName',
  INDEX idx_problemId (`problemId`) USING INVERTED COMMENT 'inverted index for tag problemId',
  INDEX idx_service (`service`) USING INVERTED COMMENT 'inverted index for tag service',
  INDEX idx_service_id (`service_id`) USING INVERTED COMMENT 'inverted index for tag service_id',
  INDEX idx_service_instance (`service_instance`) USING INVERTED COMMENT 'inverted index for tag service_instance'
) ENGINE=OLAP
AGGREGATE KEY(`metric_time`, `ts`, `convergenceType`, `gid`, `host`, `level`, `policyId`, `policyName`, `problemId`, `service`, `service_id`, `service_instance`)
PARTITION BY RANGE(`metric_time`) ()
DISTRIBUTED BY HASH(`service_id`) BUCKETS 3
PROPERTIES (
  "replication_num" = "1",
  "dynamic_partition.enable" = "true",
  "dynamic_partition.time_unit" = "DAY",
  "dynamic_partition.start" = "-30",
  "dynamic_partition.end" = "3",
  "dynamic_partition.prefix" = "p",
  "dynamic_partition.create_history_partition" = "true"
);
INSERT INTO metric_service_health_status__v3 (`metric_time`, `ts`, `convergenceType`, `gid`, `host`, `level`, `policyId`, `policyName`, `problemId`, `service`, `service_id`, `service_instance`, `metricsVal`)
SELECT FROM_UNIXTIME(FLOOR(`ts` / 1000)) AS `metric_time`, `ts`, `convergenceType`, `gid`, `host`, `level`, `policyId`, `policyName`, `problemId`, `service`, `service_id`, `service_instance`, `metricsVal` FROM metric_service_health_status WHERE FROM_UNIXTIME(FLOOR(`ts` / 1000)) >= DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY);
DROP TABLE metric_service_health_status;
ALTER TABLE metric_service_health_status__v3 RENAME metric_service_health_status;

-- metric_service_http
CREATE TABLE metric_service_http__v3 (

  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `durationRange` VARCHAR(512),
  `httpCode` VARCHAR(512),
  `httpMethod` VARCHAR(512),
  `isIn` VARCHAR(512),
  `isOut` VARCHAR(512),
  `resource` VARCHAR(512),
  `rootComponentType` VARCHAR(512),
  `rootResource` VARCHAR(512),
  `service` VARCHAR(512),
  `service_id` VARCHAR(512),
  `service_instance` VARCHAR(512),
  `srcService` VARCHAR(512),
  `srcServiceId` VARCHAR(512),
  `srcServiceInstance` VARCHAR(512),
  `url` VARCHAR(512),
  `cnt` BIGINT SUM,
  `cpuTime` DOUBLE SUM,
  `error` BIGINT SUM,
  `histogramCount` BIGINT SUM,
  `histogramMax` DOUBLE MAX,
  `maxDuration` DOUBLE MAX,
  `minDuration` DOUBLE MIN,
  `reqBodyLength` DOUBLE SUM,
  `respBodyLength` DOUBLE SUM,
  `slow` BIGINT SUM,
  `slowCnt` BIGINT SUM,
  `sumDuration` DOUBLE SUM,
  `verySlowCnt` BIGINT SUM,
  INDEX idx_durationRange (`durationRange`) USING INVERTED COMMENT 'inverted index for tag durationRange',
  INDEX idx_httpCode (`httpCode`) USING INVERTED COMMENT 'inverted index for tag httpCode',
  INDEX idx_httpMethod (`httpMethod`) USING INVERTED COMMENT 'inverted index for tag httpMethod',
  INDEX idx_isIn (`isIn`) USING INVERTED COMMENT 'inverted index for tag isIn',
  INDEX idx_isOut (`isOut`) USING INVERTED COMMENT 'inverted index for tag isOut',
  INDEX idx_resource (`resource`) USING INVERTED COMMENT 'inverted index for tag resource',
  INDEX idx_rootComponentType (`rootComponentType`) USING INVERTED COMMENT 'inverted index for tag rootComponentType',
  INDEX idx_rootResource (`rootResource`) USING INVERTED COMMENT 'inverted index for tag rootResource',
  INDEX idx_service (`service`) USING INVERTED COMMENT 'inverted index for tag service',
  INDEX idx_service_id (`service_id`) USING INVERTED COMMENT 'inverted index for tag service_id',
  INDEX idx_service_instance (`service_instance`) USING INVERTED COMMENT 'inverted index for tag service_instance',
  INDEX idx_srcService (`srcService`) USING INVERTED COMMENT 'inverted index for tag srcService',
  INDEX idx_srcServiceId (`srcServiceId`) USING INVERTED COMMENT 'inverted index for tag srcServiceId',
  INDEX idx_srcServiceInstance (`srcServiceInstance`) USING INVERTED COMMENT 'inverted index for tag srcServiceInstance',
  INDEX idx_url (`url`) USING INVERTED COMMENT 'inverted index for tag url'
) ENGINE=OLAP
AGGREGATE KEY(`metric_time`, `ts`, `durationRange`, `httpCode`, `httpMethod`, `isIn`, `isOut`, `resource`, `rootComponentType`, `rootResource`, `service`, `service_id`, `service_instance`, `srcService`, `srcServiceId`, `srcServiceInstance`, `url`)
PARTITION BY RANGE(`metric_time`) ()
DISTRIBUTED BY HASH(`service_id`) BUCKETS 3
PROPERTIES (
  "replication_num" = "1",
  "dynamic_partition.enable" = "true",
  "dynamic_partition.time_unit" = "DAY",
  "dynamic_partition.start" = "-30",
  "dynamic_partition.end" = "3",
  "dynamic_partition.prefix" = "p",
  "dynamic_partition.create_history_partition" = "true"
);
INSERT INTO metric_service_http__v3 (`metric_time`, `ts`, `durationRange`, `httpCode`, `httpMethod`, `isIn`, `isOut`, `resource`, `rootComponentType`, `rootResource`, `service`, `service_id`, `service_instance`, `srcService`, `srcServiceId`, `srcServiceInstance`, `url`, `cnt`, `cpuTime`, `error`, `histogramCount`, `histogramMax`, `maxDuration`, `minDuration`, `reqBodyLength`, `respBodyLength`, `slow`, `slowCnt`, `sumDuration`, `verySlowCnt`)
SELECT FROM_UNIXTIME(FLOOR(`ts` / 1000)) AS `metric_time`, `ts`, `durationRange`, `httpCode`, `httpMethod`, `isIn`, `isOut`, `resource`, `rootComponentType`, `rootResource`, `service`, `service_id`, `service_instance`, `srcService`, `srcServiceId`, `srcServiceInstance`, `url`, `cnt`, `cpuTime`, `error`, `histogramCount`, `histogramMax`, `maxDuration`, `minDuration`, `reqBodyLength`, `respBodyLength`, `slow`, `slowCnt`, `sumDuration`, `verySlowCnt` FROM metric_service_http WHERE FROM_UNIXTIME(FLOOR(`ts` / 1000)) >= DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY);
DROP TABLE metric_service_http;
ALTER TABLE metric_service_http__v3 RENAME metric_service_http;

-- metric_service_http_connection_pool
CREATE TABLE metric_service_http_connection_pool__v3 (

  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `httpConnectionPoolName` VARCHAR(512),
  `service` VARCHAR(512),
  `service_id` VARCHAR(512),
  `service_instance` VARCHAR(512),
  `activeSize` DOUBLE SUM,
  `idleSize` DOUBLE SUM,
  `maxSize` DOUBLE SUM,
  `waiterNum` DOUBLE SUM,
  INDEX idx_httpConnectionPoolName (`httpConnectionPoolName`) USING INVERTED COMMENT 'inverted index for tag httpConnectionPoolName',
  INDEX idx_service (`service`) USING INVERTED COMMENT 'inverted index for tag service',
  INDEX idx_service_id (`service_id`) USING INVERTED COMMENT 'inverted index for tag service_id',
  INDEX idx_service_instance (`service_instance`) USING INVERTED COMMENT 'inverted index for tag service_instance'
) ENGINE=OLAP
AGGREGATE KEY(`metric_time`, `ts`, `httpConnectionPoolName`, `service`, `service_id`, `service_instance`)
PARTITION BY RANGE(`metric_time`) ()
DISTRIBUTED BY HASH(`service_id`) BUCKETS 3
PROPERTIES (
  "replication_num" = "1",
  "dynamic_partition.enable" = "true",
  "dynamic_partition.time_unit" = "DAY",
  "dynamic_partition.start" = "-30",
  "dynamic_partition.end" = "3",
  "dynamic_partition.prefix" = "p",
  "dynamic_partition.create_history_partition" = "true"
);
INSERT INTO metric_service_http_connection_pool__v3 (`metric_time`, `ts`, `httpConnectionPoolName`, `service`, `service_id`, `service_instance`, `activeSize`, `idleSize`, `maxSize`, `waiterNum`)
SELECT FROM_UNIXTIME(FLOOR(`ts` / 1000)) AS `metric_time`, `ts`, `httpConnectionPoolName`, `service`, `service_id`, `service_instance`, `activeSize`, `idleSize`, `maxSize`, `waiterNum` FROM metric_service_http_connection_pool WHERE FROM_UNIXTIME(FLOOR(`ts` / 1000)) >= DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY);
DROP TABLE metric_service_http_connection_pool;
ALTER TABLE metric_service_http_connection_pool__v3 RENAME metric_service_http_connection_pool;

-- metric_service_http_connection_pool_get
CREATE TABLE metric_service_http_connection_pool_get__v3 (

  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `httpConnectionPoolName` VARCHAR(512),
  `service` VARCHAR(512),
  `service_id` VARCHAR(512),
  `service_instance` VARCHAR(512),
  `waitTime` DOUBLE SUM,
  `count` DOUBLE SUM,
  INDEX idx_httpConnectionPoolName (`httpConnectionPoolName`) USING INVERTED COMMENT 'inverted index for tag httpConnectionPoolName',
  INDEX idx_service (`service`) USING INVERTED COMMENT 'inverted index for tag service',
  INDEX idx_service_id (`service_id`) USING INVERTED COMMENT 'inverted index for tag service_id',
  INDEX idx_service_instance (`service_instance`) USING INVERTED COMMENT 'inverted index for tag service_instance'
) ENGINE=OLAP
AGGREGATE KEY(`metric_time`, `ts`, `httpConnectionPoolName`, `service`, `service_id`, `service_instance`)
PARTITION BY RANGE(`metric_time`) ()
DISTRIBUTED BY HASH(`service_id`) BUCKETS 3
PROPERTIES (
  "replication_num" = "1",
  "dynamic_partition.enable" = "true",
  "dynamic_partition.time_unit" = "DAY",
  "dynamic_partition.start" = "-30",
  "dynamic_partition.end" = "3",
  "dynamic_partition.prefix" = "p",
  "dynamic_partition.create_history_partition" = "true"
);
INSERT INTO metric_service_http_connection_pool_get__v3 (`metric_time`, `ts`, `httpConnectionPoolName`, `service`, `service_id`, `service_instance`, `waitTime`, `count`)
SELECT FROM_UNIXTIME(FLOOR(`ts` / 1000)) AS `metric_time`, `ts`, `httpConnectionPoolName`, `service`, `service_id`, `service_instance`, `waitTime`, `count` FROM metric_service_http_connection_pool_get WHERE FROM_UNIXTIME(FLOOR(`ts` / 1000)) >= DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY);
DROP TABLE metric_service_http_connection_pool_get;
ALTER TABLE metric_service_http_connection_pool_get__v3 RENAME metric_service_http_connection_pool_get;

-- metric_service_instance
CREATE TABLE metric_service_instance__v3 (

  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `biz_pid_id` VARCHAR(512),
  `containerId` VARCHAR(512),
  `containerName` VARCHAR(512),
  `hostIp` VARCHAR(512),
  `hostname` VARCHAR(512),
  `javaVendor` VARCHAR(512),
  `javaVersion` VARCHAR(512),
  `k8sClusterId` VARCHAR(512),
  `k8sContainerId` VARCHAR(512),
  `k8sNamespace` VARCHAR(512),
  `k8sPodName` VARCHAR(512),
  `pid` VARCHAR(512),
  `pname` VARCHAR(512),
  `ports` VARCHAR(512),
  `service` VARCHAR(512),
  `service_id` VARCHAR(512),
  `service_instance` VARCHAR(512),
  `service_type` VARCHAR(512),
  `virtualService` VARCHAR(512),
  `metricsVal` BIGINT SUM,
  INDEX idx_biz_pid_id (`biz_pid_id`) USING INVERTED COMMENT 'inverted index for tag biz_pid_id',
  INDEX idx_containerId (`containerId`) USING INVERTED COMMENT 'inverted index for tag containerId',
  INDEX idx_containerName (`containerName`) USING INVERTED COMMENT 'inverted index for tag containerName',
  INDEX idx_hostIp (`hostIp`) USING INVERTED COMMENT 'inverted index for tag hostIp',
  INDEX idx_hostname (`hostname`) USING INVERTED COMMENT 'inverted index for tag hostname',
  INDEX idx_javaVendor (`javaVendor`) USING INVERTED COMMENT 'inverted index for tag javaVendor',
  INDEX idx_javaVersion (`javaVersion`) USING INVERTED COMMENT 'inverted index for tag javaVersion',
  INDEX idx_k8sClusterId (`k8sClusterId`) USING INVERTED COMMENT 'inverted index for tag k8sClusterId',
  INDEX idx_k8sContainerId (`k8sContainerId`) USING INVERTED COMMENT 'inverted index for tag k8sContainerId',
  INDEX idx_k8sNamespace (`k8sNamespace`) USING INVERTED COMMENT 'inverted index for tag k8sNamespace',
  INDEX idx_k8sPodName (`k8sPodName`) USING INVERTED COMMENT 'inverted index for tag k8sPodName',
  INDEX idx_pid (`pid`) USING INVERTED COMMENT 'inverted index for tag pid',
  INDEX idx_pname (`pname`) USING INVERTED COMMENT 'inverted index for tag pname',
  INDEX idx_ports (`ports`) USING INVERTED COMMENT 'inverted index for tag ports',
  INDEX idx_service (`service`) USING INVERTED COMMENT 'inverted index for tag service',
  INDEX idx_service_id (`service_id`) USING INVERTED COMMENT 'inverted index for tag service_id',
  INDEX idx_service_instance (`service_instance`) USING INVERTED COMMENT 'inverted index for tag service_instance',
  INDEX idx_service_type (`service_type`) USING INVERTED COMMENT 'inverted index for tag service_type',
  INDEX idx_virtualService (`virtualService`) USING INVERTED COMMENT 'inverted index for tag virtualService'
) ENGINE=OLAP
AGGREGATE KEY(`metric_time`, `ts`, `biz_pid_id`, `containerId`, `containerName`, `hostIp`, `hostname`, `javaVendor`, `javaVersion`, `k8sClusterId`, `k8sContainerId`, `k8sNamespace`, `k8sPodName`, `pid`, `pname`, `ports`, `service`, `service_id`, `service_instance`, `service_type`, `virtualService`)
PARTITION BY RANGE(`metric_time`) ()
DISTRIBUTED BY HASH(`service_id`) BUCKETS 3
PROPERTIES (
  "replication_num" = "1",
  "dynamic_partition.enable" = "true",
  "dynamic_partition.time_unit" = "DAY",
  "dynamic_partition.start" = "-30",
  "dynamic_partition.end" = "3",
  "dynamic_partition.prefix" = "p",
  "dynamic_partition.create_history_partition" = "true"
);
INSERT INTO metric_service_instance__v3 (`metric_time`, `ts`, `biz_pid_id`, `containerId`, `containerName`, `hostIp`, `hostname`, `javaVendor`, `javaVersion`, `k8sClusterId`, `k8sContainerId`, `k8sNamespace`, `k8sPodName`, `pid`, `pname`, `ports`, `service`, `service_id`, `service_instance`, `service_type`, `virtualService`, `metricsVal`)
SELECT FROM_UNIXTIME(FLOOR(`ts` / 1000)) AS `metric_time`, `ts`, `biz_pid_id`, `containerId`, `containerName`, `hostIp`, `hostname`, `javaVendor`, `javaVersion`, `k8sClusterId`, `k8sContainerId`, `k8sNamespace`, `k8sPodName`, `pid`, `pname`, `ports`, `service`, `service_id`, `service_instance`, `service_type`, `virtualService`, `metricsVal` FROM metric_service_instance WHERE FROM_UNIXTIME(FLOOR(`ts` / 1000)) >= DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY);
DROP TABLE metric_service_instance;
ALTER TABLE metric_service_instance__v3 RENAME metric_service_instance;

-- metric_service_io
CREATE TABLE metric_service_io__v3 (

  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `service` VARCHAR(512),
  `serviceCode` VARCHAR(512),
  `service_id` VARCHAR(512),
  `service_instance` VARCHAR(512),
  `read.rate` DOUBLE SUM,
  `write.rate` DOUBLE SUM,
  INDEX idx_service (`service`) USING INVERTED COMMENT 'inverted index for tag service',
  INDEX idx_serviceCode (`serviceCode`) USING INVERTED COMMENT 'inverted index for tag serviceCode',
  INDEX idx_service_id (`service_id`) USING INVERTED COMMENT 'inverted index for tag service_id',
  INDEX idx_service_instance (`service_instance`) USING INVERTED COMMENT 'inverted index for tag service_instance'
) ENGINE=OLAP
AGGREGATE KEY(`metric_time`, `ts`, `service`, `serviceCode`, `service_id`, `service_instance`)
PARTITION BY RANGE(`metric_time`) ()
DISTRIBUTED BY HASH(`service_id`) BUCKETS 3
PROPERTIES (
  "replication_num" = "1",
  "dynamic_partition.enable" = "true",
  "dynamic_partition.time_unit" = "DAY",
  "dynamic_partition.start" = "-30",
  "dynamic_partition.end" = "3",
  "dynamic_partition.prefix" = "p",
  "dynamic_partition.create_history_partition" = "true"
);
INSERT INTO metric_service_io__v3 (`metric_time`, `ts`, `service`, `serviceCode`, `service_id`, `service_instance`, `read.rate`, `write.rate`)
SELECT FROM_UNIXTIME(FLOOR(`ts` / 1000)) AS `metric_time`, `ts`, `service`, `serviceCode`, `service_id`, `service_instance`, `read.rate`, `write.rate` FROM metric_service_io WHERE FROM_UNIXTIME(FLOOR(`ts` / 1000)) >= DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY);
DROP TABLE metric_service_io;
ALTER TABLE metric_service_io__v3 RENAME metric_service_io;

-- metric_service_mem
CREATE TABLE metric_service_mem__v3 (

  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `service` VARCHAR(512),
  `serviceCode` VARCHAR(512),
  `service_id` VARCHAR(512),
  `service_instance` VARCHAR(512),
  `size` DOUBLE SUM,
  `usage_pct` DOUBLE SUM,
  `used` DOUBLE SUM,
  INDEX idx_service (`service`) USING INVERTED COMMENT 'inverted index for tag service',
  INDEX idx_serviceCode (`serviceCode`) USING INVERTED COMMENT 'inverted index for tag serviceCode',
  INDEX idx_service_id (`service_id`) USING INVERTED COMMENT 'inverted index for tag service_id',
  INDEX idx_service_instance (`service_instance`) USING INVERTED COMMENT 'inverted index for tag service_instance'
) ENGINE=OLAP
AGGREGATE KEY(`metric_time`, `ts`, `service`, `serviceCode`, `service_id`, `service_instance`)
PARTITION BY RANGE(`metric_time`) ()
DISTRIBUTED BY HASH(`service_id`) BUCKETS 3
PROPERTIES (
  "replication_num" = "1",
  "dynamic_partition.enable" = "true",
  "dynamic_partition.time_unit" = "DAY",
  "dynamic_partition.start" = "-30",
  "dynamic_partition.end" = "3",
  "dynamic_partition.prefix" = "p",
  "dynamic_partition.create_history_partition" = "true"
);
INSERT INTO metric_service_mem__v3 (`metric_time`, `ts`, `service`, `serviceCode`, `service_id`, `service_instance`, `size`, `usage_pct`, `used`)
SELECT FROM_UNIXTIME(FLOOR(`ts` / 1000)) AS `metric_time`, `ts`, `service`, `serviceCode`, `service_id`, `service_instance`, `size`, `usage_pct`, `used` FROM metric_service_mem WHERE FROM_UNIXTIME(FLOOR(`ts` / 1000)) >= DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY);
DROP TABLE metric_service_mem;
ALTER TABLE metric_service_mem__v3 RENAME metric_service_mem;

-- metric_service_mq
CREATE TABLE metric_service_mq__v3 (

  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `broker` VARCHAR(512),
  `durationRange` VARCHAR(512),
  `group` VARCHAR(512),
  `isConsume` VARCHAR(512),
  `isIn` VARCHAR(512),
  `isOut` VARCHAR(512),
  `partition` VARCHAR(512),
  `resource` VARCHAR(512),
  `rootComponentType` VARCHAR(512),
  `rootResource` VARCHAR(512),
  `service` VARCHAR(512),
  `service_id` VARCHAR(512),
  `service_instance` VARCHAR(512),
  `srcService` VARCHAR(512),
  `srcServiceId` VARCHAR(512),
  `srcServiceInstance` VARCHAR(512),
  `topic` VARCHAR(512),
  `type` VARCHAR(512),
  `cnt` BIGINT SUM,
  `cpuTime` DOUBLE SUM,
  `delay` DOUBLE SUM,
  `error` BIGINT SUM,
  `histogramCount` BIGINT SUM,
  `histogramMax` DOUBLE MAX,
  `maxDuration` DOUBLE MAX,
  `minDuration` DOUBLE MIN,
  `mqBodyLength` DOUBLE SUM,
  `slow` BIGINT SUM,
  `sumDuration` DOUBLE SUM,
  INDEX idx_broker (`broker`) USING INVERTED COMMENT 'inverted index for tag broker',
  INDEX idx_durationRange (`durationRange`) USING INVERTED COMMENT 'inverted index for tag durationRange',
  INDEX idx_group (`group`) USING INVERTED COMMENT 'inverted index for tag group',
  INDEX idx_isConsume (`isConsume`) USING INVERTED COMMENT 'inverted index for tag isConsume',
  INDEX idx_isIn (`isIn`) USING INVERTED COMMENT 'inverted index for tag isIn',
  INDEX idx_isOut (`isOut`) USING INVERTED COMMENT 'inverted index for tag isOut',
  INDEX idx_partition (`partition`) USING INVERTED COMMENT 'inverted index for tag partition',
  INDEX idx_resource (`resource`) USING INVERTED COMMENT 'inverted index for tag resource',
  INDEX idx_rootComponentType (`rootComponentType`) USING INVERTED COMMENT 'inverted index for tag rootComponentType',
  INDEX idx_rootResource (`rootResource`) USING INVERTED COMMENT 'inverted index for tag rootResource',
  INDEX idx_service (`service`) USING INVERTED COMMENT 'inverted index for tag service',
  INDEX idx_service_id (`service_id`) USING INVERTED COMMENT 'inverted index for tag service_id',
  INDEX idx_service_instance (`service_instance`) USING INVERTED COMMENT 'inverted index for tag service_instance',
  INDEX idx_srcService (`srcService`) USING INVERTED COMMENT 'inverted index for tag srcService',
  INDEX idx_srcServiceId (`srcServiceId`) USING INVERTED COMMENT 'inverted index for tag srcServiceId',
  INDEX idx_srcServiceInstance (`srcServiceInstance`) USING INVERTED COMMENT 'inverted index for tag srcServiceInstance',
  INDEX idx_topic (`topic`) USING INVERTED COMMENT 'inverted index for tag topic',
  INDEX idx_type (`type`) USING INVERTED COMMENT 'inverted index for tag type'
) ENGINE=OLAP
AGGREGATE KEY(`metric_time`, `ts`, `broker`, `durationRange`, `group`, `isConsume`, `isIn`, `isOut`, `partition`, `resource`, `rootComponentType`, `rootResource`, `service`, `service_id`, `service_instance`, `srcService`, `srcServiceId`, `srcServiceInstance`, `topic`, `type`)
PARTITION BY RANGE(`metric_time`) ()
DISTRIBUTED BY HASH(`service_id`) BUCKETS 3
PROPERTIES (
  "replication_num" = "1",
  "dynamic_partition.enable" = "true",
  "dynamic_partition.time_unit" = "DAY",
  "dynamic_partition.start" = "-30",
  "dynamic_partition.end" = "3",
  "dynamic_partition.prefix" = "p",
  "dynamic_partition.create_history_partition" = "true"
);
INSERT INTO metric_service_mq__v3 (`metric_time`, `ts`, `broker`, `durationRange`, `group`, `isConsume`, `isIn`, `isOut`, `partition`, `resource`, `rootComponentType`, `rootResource`, `service`, `service_id`, `service_instance`, `srcService`, `srcServiceId`, `srcServiceInstance`, `topic`, `type`, `cnt`, `cpuTime`, `delay`, `error`, `histogramCount`, `histogramMax`, `maxDuration`, `minDuration`, `mqBodyLength`, `slow`, `sumDuration`)
SELECT FROM_UNIXTIME(FLOOR(`ts` / 1000)) AS `metric_time`, `ts`, `broker`, `durationRange`, `group`, `isConsume`, `isIn`, `isOut`, `partition`, `resource`, `rootComponentType`, `rootResource`, `service`, `service_id`, `service_instance`, `srcService`, `srcServiceId`, `srcServiceInstance`, `topic`, `type`, `cnt`, `cpuTime`, `delay`, `error`, `histogramCount`, `histogramMax`, `maxDuration`, `minDuration`, `mqBodyLength`, `slow`, `sumDuration` FROM metric_service_mq WHERE FROM_UNIXTIME(FLOOR(`ts` / 1000)) >= DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY);
DROP TABLE metric_service_mq;
ALTER TABLE metric_service_mq__v3 RENAME metric_service_mq;

-- metric_service_net
CREATE TABLE metric_service_net__v3 (

  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `service` VARCHAR(512),
  `serviceCode` VARCHAR(512),
  `service_id` VARCHAR(512),
  `service_instance` VARCHAR(512),
  `bytes_rcvd` DOUBLE SUM,
  `bytes_sent` DOUBLE SUM,
  INDEX idx_service (`service`) USING INVERTED COMMENT 'inverted index for tag service',
  INDEX idx_serviceCode (`serviceCode`) USING INVERTED COMMENT 'inverted index for tag serviceCode',
  INDEX idx_service_id (`service_id`) USING INVERTED COMMENT 'inverted index for tag service_id',
  INDEX idx_service_instance (`service_instance`) USING INVERTED COMMENT 'inverted index for tag service_instance'
) ENGINE=OLAP
AGGREGATE KEY(`metric_time`, `ts`, `service`, `serviceCode`, `service_id`, `service_instance`)
PARTITION BY RANGE(`metric_time`) ()
DISTRIBUTED BY HASH(`service_id`) BUCKETS 3
PROPERTIES (
  "replication_num" = "1",
  "dynamic_partition.enable" = "true",
  "dynamic_partition.time_unit" = "DAY",
  "dynamic_partition.start" = "-30",
  "dynamic_partition.end" = "3",
  "dynamic_partition.prefix" = "p",
  "dynamic_partition.create_history_partition" = "true"
);
INSERT INTO metric_service_net__v3 (`metric_time`, `ts`, `service`, `serviceCode`, `service_id`, `service_instance`, `bytes_rcvd`, `bytes_sent`)
SELECT FROM_UNIXTIME(FLOOR(`ts` / 1000)) AS `metric_time`, `ts`, `service`, `serviceCode`, `service_id`, `service_instance`, `bytes_rcvd`, `bytes_sent` FROM metric_service_net WHERE FROM_UNIXTIME(FLOOR(`ts` / 1000)) >= DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY);
DROP TABLE metric_service_net;
ALTER TABLE metric_service_net__v3 RENAME metric_service_net;

-- metric_service_object_pool
CREATE TABLE metric_service_object_pool__v3 (

  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `objectPoolFairness` VARCHAR(512),
  `objectPoolName` VARCHAR(512),
  `objectPoolObjectClass` VARCHAR(512),
  `service` VARCHAR(512),
  `service_id` VARCHAR(512),
  `service_instance` VARCHAR(512),
  `activeSize` DOUBLE SUM,
  `idleSize` DOUBLE SUM,
  `maxSize` DOUBLE SUM,
  INDEX idx_objectPoolFairness (`objectPoolFairness`) USING INVERTED COMMENT 'inverted index for tag objectPoolFairness',
  INDEX idx_objectPoolName (`objectPoolName`) USING INVERTED COMMENT 'inverted index for tag objectPoolName',
  INDEX idx_objectPoolObjectClass (`objectPoolObjectClass`) USING INVERTED COMMENT 'inverted index for tag objectPoolObjectClass',
  INDEX idx_service (`service`) USING INVERTED COMMENT 'inverted index for tag service',
  INDEX idx_service_id (`service_id`) USING INVERTED COMMENT 'inverted index for tag service_id',
  INDEX idx_service_instance (`service_instance`) USING INVERTED COMMENT 'inverted index for tag service_instance'
) ENGINE=OLAP
AGGREGATE KEY(`metric_time`, `ts`, `objectPoolFairness`, `objectPoolName`, `objectPoolObjectClass`, `service`, `service_id`, `service_instance`)
PARTITION BY RANGE(`metric_time`) ()
DISTRIBUTED BY HASH(`service_id`) BUCKETS 3
PROPERTIES (
  "replication_num" = "1",
  "dynamic_partition.enable" = "true",
  "dynamic_partition.time_unit" = "DAY",
  "dynamic_partition.start" = "-30",
  "dynamic_partition.end" = "3",
  "dynamic_partition.prefix" = "p",
  "dynamic_partition.create_history_partition" = "true"
);
INSERT INTO metric_service_object_pool__v3 (`metric_time`, `ts`, `objectPoolFairness`, `objectPoolName`, `objectPoolObjectClass`, `service`, `service_id`, `service_instance`, `activeSize`, `idleSize`, `maxSize`)
SELECT FROM_UNIXTIME(FLOOR(`ts` / 1000)) AS `metric_time`, `ts`, `objectPoolFairness`, `objectPoolName`, `objectPoolObjectClass`, `service`, `service_id`, `service_instance`, `activeSize`, `idleSize`, `maxSize` FROM metric_service_object_pool WHERE FROM_UNIXTIME(FLOOR(`ts` / 1000)) >= DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY);
DROP TABLE metric_service_object_pool;
ALTER TABLE metric_service_object_pool__v3 RENAME metric_service_object_pool;

-- metric_service_object_pool_get
CREATE TABLE metric_service_object_pool_get__v3 (

  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `objectPoolName` VARCHAR(512),
  `service` VARCHAR(512),
  `service_id` VARCHAR(512),
  `service_instance` VARCHAR(512),
  `waitTime` DOUBLE SUM,
  `count` DOUBLE SUM,
  INDEX idx_objectPoolName (`objectPoolName`) USING INVERTED COMMENT 'inverted index for tag objectPoolName',
  INDEX idx_service (`service`) USING INVERTED COMMENT 'inverted index for tag service',
  INDEX idx_service_id (`service_id`) USING INVERTED COMMENT 'inverted index for tag service_id',
  INDEX idx_service_instance (`service_instance`) USING INVERTED COMMENT 'inverted index for tag service_instance'
) ENGINE=OLAP
AGGREGATE KEY(`metric_time`, `ts`, `objectPoolName`, `service`, `service_id`, `service_instance`)
PARTITION BY RANGE(`metric_time`) ()
DISTRIBUTED BY HASH(`service_id`) BUCKETS 3
PROPERTIES (
  "replication_num" = "1",
  "dynamic_partition.enable" = "true",
  "dynamic_partition.time_unit" = "DAY",
  "dynamic_partition.start" = "-30",
  "dynamic_partition.end" = "3",
  "dynamic_partition.prefix" = "p",
  "dynamic_partition.create_history_partition" = "true"
);
INSERT INTO metric_service_object_pool_get__v3 (`metric_time`, `ts`, `objectPoolName`, `service`, `service_id`, `service_instance`, `waitTime`, `count`)
SELECT FROM_UNIXTIME(FLOOR(`ts` / 1000)) AS `metric_time`, `ts`, `objectPoolName`, `service`, `service_id`, `service_instance`, `waitTime`, `count` FROM metric_service_object_pool_get WHERE FROM_UNIXTIME(FLOOR(`ts` / 1000)) >= DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY);
DROP TABLE metric_service_object_pool_get;
ALTER TABLE metric_service_object_pool_get__v3 RENAME metric_service_object_pool_get;

-- metric_service_redis
CREATE TABLE metric_service_redis__v3 (

  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `command` VARCHAR(512),
  `durationRange` VARCHAR(512),
  `isIn` VARCHAR(512),
  `isOut` VARCHAR(512),
  `resource` VARCHAR(512),
  `rootComponentType` VARCHAR(512),
  `rootResource` VARCHAR(512),
  `service` VARCHAR(512),
  `service_id` VARCHAR(512),
  `service_instance` VARCHAR(512),
  `srcService` VARCHAR(512),
  `srcServiceId` VARCHAR(512),
  `srcServiceInstance` VARCHAR(512),
  `cnt` BIGINT SUM,
  `error` BIGINT SUM,
  `histogramCount` BIGINT SUM,
  `histogramMax` DOUBLE MAX,
  `maxDuration` DOUBLE MAX,
  `minDuration` DOUBLE MIN,
  `reqBodyLength` DOUBLE SUM,
  `respBodyLength` DOUBLE SUM,
  `slow` BIGINT SUM,
  `sumDuration` DOUBLE SUM,
  INDEX idx_command (`command`) USING INVERTED COMMENT 'inverted index for tag command',
  INDEX idx_durationRange (`durationRange`) USING INVERTED COMMENT 'inverted index for tag durationRange',
  INDEX idx_isIn (`isIn`) USING INVERTED COMMENT 'inverted index for tag isIn',
  INDEX idx_isOut (`isOut`) USING INVERTED COMMENT 'inverted index for tag isOut',
  INDEX idx_resource (`resource`) USING INVERTED COMMENT 'inverted index for tag resource',
  INDEX idx_rootComponentType (`rootComponentType`) USING INVERTED COMMENT 'inverted index for tag rootComponentType',
  INDEX idx_rootResource (`rootResource`) USING INVERTED COMMENT 'inverted index for tag rootResource',
  INDEX idx_service (`service`) USING INVERTED COMMENT 'inverted index for tag service',
  INDEX idx_service_id (`service_id`) USING INVERTED COMMENT 'inverted index for tag service_id',
  INDEX idx_service_instance (`service_instance`) USING INVERTED COMMENT 'inverted index for tag service_instance',
  INDEX idx_srcService (`srcService`) USING INVERTED COMMENT 'inverted index for tag srcService',
  INDEX idx_srcServiceId (`srcServiceId`) USING INVERTED COMMENT 'inverted index for tag srcServiceId',
  INDEX idx_srcServiceInstance (`srcServiceInstance`) USING INVERTED COMMENT 'inverted index for tag srcServiceInstance'
) ENGINE=OLAP
AGGREGATE KEY(`metric_time`, `ts`, `command`, `durationRange`, `isIn`, `isOut`, `resource`, `rootComponentType`, `rootResource`, `service`, `service_id`, `service_instance`, `srcService`, `srcServiceId`, `srcServiceInstance`)
PARTITION BY RANGE(`metric_time`) ()
DISTRIBUTED BY HASH(`service_id`) BUCKETS 3
PROPERTIES (
  "replication_num" = "1",
  "dynamic_partition.enable" = "true",
  "dynamic_partition.time_unit" = "DAY",
  "dynamic_partition.start" = "-30",
  "dynamic_partition.end" = "3",
  "dynamic_partition.prefix" = "p",
  "dynamic_partition.create_history_partition" = "true"
);
INSERT INTO metric_service_redis__v3 (`metric_time`, `ts`, `command`, `durationRange`, `isIn`, `isOut`, `resource`, `rootComponentType`, `rootResource`, `service`, `service_id`, `service_instance`, `srcService`, `srcServiceId`, `srcServiceInstance`, `cnt`, `error`, `histogramCount`, `histogramMax`, `maxDuration`, `minDuration`, `reqBodyLength`, `respBodyLength`, `slow`, `sumDuration`)
SELECT FROM_UNIXTIME(FLOOR(`ts` / 1000)) AS `metric_time`, `ts`, `command`, `durationRange`, `isIn`, `isOut`, `resource`, `rootComponentType`, `rootResource`, `service`, `service_id`, `service_instance`, `srcService`, `srcServiceId`, `srcServiceInstance`, `cnt`, `error`, `histogramCount`, `histogramMax`, `maxDuration`, `minDuration`, `reqBodyLength`, `respBodyLength`, `slow`, `sumDuration` FROM metric_service_redis WHERE FROM_UNIXTIME(FLOOR(`ts` / 1000)) >= DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY);
DROP TABLE metric_service_redis;
ALTER TABLE metric_service_redis__v3 RENAME metric_service_redis;

-- metric_service_rpc
CREATE TABLE metric_service_rpc__v3 (

  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `durationRange` VARCHAR(512),
  `isIn` VARCHAR(512),
  `isOut` VARCHAR(512),
  `resource` VARCHAR(512),
  `rootComponentType` VARCHAR(512),
  `rootResource` VARCHAR(512),
  `service` VARCHAR(512),
  `service_id` VARCHAR(512),
  `service_instance` VARCHAR(512),
  `srcService` VARCHAR(512),
  `srcServiceId` VARCHAR(512),
  `srcServiceInstance` VARCHAR(512),
  `statusCode` VARCHAR(512),
  `type` VARCHAR(512),
  `cnt` BIGINT SUM,
  `cpuTime` DOUBLE SUM,
  `error` BIGINT SUM,
  `histogramCount` BIGINT SUM,
  `histogramMax` DOUBLE MAX,
  `maxDuration` DOUBLE MAX,
  `minDuration` DOUBLE MIN,
  `reqBodyLength` DOUBLE SUM,
  `respBodyLength` DOUBLE SUM,
  `slow` BIGINT SUM,
  `slowCnt` BIGINT SUM,
  `sumDuration` DOUBLE SUM,
  `verySlowCnt` BIGINT SUM,
  INDEX idx_durationRange (`durationRange`) USING INVERTED COMMENT 'inverted index for tag durationRange',
  INDEX idx_isIn (`isIn`) USING INVERTED COMMENT 'inverted index for tag isIn',
  INDEX idx_isOut (`isOut`) USING INVERTED COMMENT 'inverted index for tag isOut',
  INDEX idx_resource (`resource`) USING INVERTED COMMENT 'inverted index for tag resource',
  INDEX idx_rootComponentType (`rootComponentType`) USING INVERTED COMMENT 'inverted index for tag rootComponentType',
  INDEX idx_rootResource (`rootResource`) USING INVERTED COMMENT 'inverted index for tag rootResource',
  INDEX idx_service (`service`) USING INVERTED COMMENT 'inverted index for tag service',
  INDEX idx_service_id (`service_id`) USING INVERTED COMMENT 'inverted index for tag service_id',
  INDEX idx_service_instance (`service_instance`) USING INVERTED COMMENT 'inverted index for tag service_instance',
  INDEX idx_srcService (`srcService`) USING INVERTED COMMENT 'inverted index for tag srcService',
  INDEX idx_srcServiceId (`srcServiceId`) USING INVERTED COMMENT 'inverted index for tag srcServiceId',
  INDEX idx_srcServiceInstance (`srcServiceInstance`) USING INVERTED COMMENT 'inverted index for tag srcServiceInstance',
  INDEX idx_statusCode (`statusCode`) USING INVERTED COMMENT 'inverted index for tag statusCode',
  INDEX idx_type (`type`) USING INVERTED COMMENT 'inverted index for tag type'
) ENGINE=OLAP
AGGREGATE KEY(`metric_time`, `ts`, `durationRange`, `isIn`, `isOut`, `resource`, `rootComponentType`, `rootResource`, `service`, `service_id`, `service_instance`, `srcService`, `srcServiceId`, `srcServiceInstance`, `statusCode`, `type`)
PARTITION BY RANGE(`metric_time`) ()
DISTRIBUTED BY HASH(`service_id`) BUCKETS 3
PROPERTIES (
  "replication_num" = "1",
  "dynamic_partition.enable" = "true",
  "dynamic_partition.time_unit" = "DAY",
  "dynamic_partition.start" = "-30",
  "dynamic_partition.end" = "3",
  "dynamic_partition.prefix" = "p",
  "dynamic_partition.create_history_partition" = "true"
);
INSERT INTO metric_service_rpc__v3 (`metric_time`, `ts`, `durationRange`, `isIn`, `isOut`, `resource`, `rootComponentType`, `rootResource`, `service`, `service_id`, `service_instance`, `srcService`, `srcServiceId`, `srcServiceInstance`, `statusCode`, `type`, `cnt`, `cpuTime`, `error`, `histogramCount`, `histogramMax`, `maxDuration`, `minDuration`, `reqBodyLength`, `respBodyLength`, `slow`, `slowCnt`, `sumDuration`, `verySlowCnt`)
SELECT FROM_UNIXTIME(FLOOR(`ts` / 1000)) AS `metric_time`, `ts`, `durationRange`, `isIn`, `isOut`, `resource`, `rootComponentType`, `rootResource`, `service`, `service_id`, `service_instance`, `srcService`, `srcServiceId`, `srcServiceInstance`, `statusCode`, `type`, `cnt`, `cpuTime`, `error`, `histogramCount`, `histogramMax`, `maxDuration`, `minDuration`, `reqBodyLength`, `respBodyLength`, `slow`, `slowCnt`, `sumDuration`, `verySlowCnt` FROM metric_service_rpc WHERE FROM_UNIXTIME(FLOOR(`ts` / 1000)) >= DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY);
DROP TABLE metric_service_rpc;
ALTER TABLE metric_service_rpc__v3 RENAME metric_service_rpc;

-- metric_service_remote
CREATE TABLE metric_service_remote__v3 (

  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `durationRange` VARCHAR(512),
  `isIn` VARCHAR(512),
  `isOut` VARCHAR(512),
  `resource` VARCHAR(512),
  `rootComponentType` VARCHAR(512),
  `rootResource` VARCHAR(512),
  `service` VARCHAR(512),
  `service_id` VARCHAR(512),
  `service_instance` VARCHAR(512),
  `srcService` VARCHAR(512),
  `srcServiceId` VARCHAR(512),
  `srcServiceInstance` VARCHAR(512),
  `remoteType` VARCHAR(512),
  `cnt` BIGINT SUM,
  `cpuTime` DOUBLE SUM,
  `error` BIGINT SUM,
  `histogramCount` BIGINT SUM,
  `histogramMax` DOUBLE MAX,
  `maxDuration` DOUBLE MAX,
  `minDuration` DOUBLE MIN,
  `reqBodyLength` DOUBLE SUM,
  `respBodyLength` DOUBLE SUM,
  `slow` BIGINT SUM,
  `slowCnt` BIGINT SUM,
  `sumDuration` DOUBLE SUM,
  `verySlowCnt` BIGINT SUM,
  INDEX idx_durationRange (`durationRange`) USING INVERTED COMMENT 'inverted index for tag durationRange',
  INDEX idx_isIn (`isIn`) USING INVERTED COMMENT 'inverted index for tag isIn',
  INDEX idx_isOut (`isOut`) USING INVERTED COMMENT 'inverted index for tag isOut',
  INDEX idx_resource (`resource`) USING INVERTED COMMENT 'inverted index for tag resource',
  INDEX idx_rootComponentType (`rootComponentType`) USING INVERTED COMMENT 'inverted index for tag rootComponentType',
  INDEX idx_rootResource (`rootResource`) USING INVERTED COMMENT 'inverted index for tag rootResource',
  INDEX idx_service (`service`) USING INVERTED COMMENT 'inverted index for tag service',
  INDEX idx_service_id (`service_id`) USING INVERTED COMMENT 'inverted index for tag service_id',
  INDEX idx_service_instance (`service_instance`) USING INVERTED COMMENT 'inverted index for tag service_instance',
  INDEX idx_srcService (`srcService`) USING INVERTED COMMENT 'inverted index for tag srcService',
  INDEX idx_srcServiceId (`srcServiceId`) USING INVERTED COMMENT 'inverted index for tag srcServiceId',
  INDEX idx_srcServiceInstance (`srcServiceInstance`) USING INVERTED COMMENT 'inverted index for tag srcServiceInstance',
  INDEX idx_remoteType (`remoteType`) USING INVERTED COMMENT 'inverted index for tag remoteType'
) ENGINE=OLAP
AGGREGATE KEY(`metric_time`, `ts`, `durationRange`, `isIn`, `isOut`, `resource`, `rootComponentType`, `rootResource`, `service`, `service_id`, `service_instance`, `srcService`, `srcServiceId`, `srcServiceInstance`, `remoteType`)
PARTITION BY RANGE(`metric_time`) ()
DISTRIBUTED BY HASH(`service_id`) BUCKETS 3
PROPERTIES (
  "replication_num" = "1",
  "dynamic_partition.enable" = "true",
  "dynamic_partition.time_unit" = "DAY",
  "dynamic_partition.start" = "-30",
  "dynamic_partition.end" = "3",
  "dynamic_partition.prefix" = "p",
  "dynamic_partition.create_history_partition" = "true"
);
INSERT INTO metric_service_remote__v3 (`metric_time`, `ts`, `durationRange`, `isIn`, `isOut`, `resource`, `rootComponentType`, `rootResource`, `service`, `service_id`, `service_instance`, `srcService`, `srcServiceId`, `srcServiceInstance`, `remoteType`, `cnt`, `cpuTime`, `error`, `histogramCount`, `histogramMax`, `maxDuration`, `minDuration`, `reqBodyLength`, `respBodyLength`, `slow`, `slowCnt`, `sumDuration`, `verySlowCnt`)
SELECT FROM_UNIXTIME(FLOOR(`ts` / 1000)) AS `metric_time`, `ts`, `durationRange`, `isIn`, `isOut`, `resource`, `rootComponentType`, `rootResource`, `service`, `service_id`, `service_instance`, `srcService`, `srcServiceId`, `srcServiceInstance`, `remoteType`, `cnt`, `cpuTime`, `error`, `histogramCount`, `histogramMax`, `maxDuration`, `minDuration`, `reqBodyLength`, `respBodyLength`, `slow`, `slowCnt`, `sumDuration`, `verySlowCnt` FROM metric_service_remote WHERE FROM_UNIXTIME(FLOOR(`ts` / 1000)) >= DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY);
DROP TABLE metric_service_remote;
ALTER TABLE metric_service_remote__v3 RENAME metric_service_remote;

-- metric_service_tcp
CREATE TABLE metric_service_tcp__v3 (

  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `service` VARCHAR(512),
  `serviceCode` VARCHAR(512),
  `service_id` VARCHAR(512),
  `service_instance` VARCHAR(512),
  `conns_established` DOUBLE SUM,
  `retransmit` DOUBLE SUM,
  INDEX idx_service (`service`) USING INVERTED COMMENT 'inverted index for tag service',
  INDEX idx_serviceCode (`serviceCode`) USING INVERTED COMMENT 'inverted index for tag serviceCode',
  INDEX idx_service_id (`service_id`) USING INVERTED COMMENT 'inverted index for tag service_id',
  INDEX idx_service_instance (`service_instance`) USING INVERTED COMMENT 'inverted index for tag service_instance'
) ENGINE=OLAP
AGGREGATE KEY(`metric_time`, `ts`, `service`, `serviceCode`, `service_id`, `service_instance`)
PARTITION BY RANGE(`metric_time`) ()
DISTRIBUTED BY HASH(`service_id`) BUCKETS 3
PROPERTIES (
  "replication_num" = "1",
  "dynamic_partition.enable" = "true",
  "dynamic_partition.time_unit" = "DAY",
  "dynamic_partition.start" = "-30",
  "dynamic_partition.end" = "3",
  "dynamic_partition.prefix" = "p",
  "dynamic_partition.create_history_partition" = "true"
);
INSERT INTO metric_service_tcp__v3 (`metric_time`, `ts`, `service`, `serviceCode`, `service_id`, `service_instance`, `conns_established`, `retransmit`)
SELECT FROM_UNIXTIME(FLOOR(`ts` / 1000)) AS `metric_time`, `ts`, `service`, `serviceCode`, `service_id`, `service_instance`, `conns_established`, `retransmit` FROM metric_service_tcp WHERE FROM_UNIXTIME(FLOOR(`ts` / 1000)) >= DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY);
DROP TABLE metric_service_tcp;
ALTER TABLE metric_service_tcp__v3 RENAME metric_service_tcp;

-- metric_service_thread_pool
CREATE TABLE metric_service_thread_pool__v3 (

  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `service` VARCHAR(512),
  `service_id` VARCHAR(512),
  `service_instance` VARCHAR(512),
  `threadPoolName` VARCHAR(512),
  `activeCount` DOUBLE SUM,
  `completedTaskCount` BIGINT SUM,
  `corePoolSize` DOUBLE SUM,
  `largestPoolSize` DOUBLE SUM,
  `maximumPoolSize` DOUBLE SUM,
  `poolSize` DOUBLE SUM,
  `queueRemainingCapacity` DOUBLE SUM,
  `queueSize` DOUBLE SUM,
  `taskCount` BIGINT SUM,
  INDEX idx_service (`service`) USING INVERTED COMMENT 'inverted index for tag service',
  INDEX idx_service_id (`service_id`) USING INVERTED COMMENT 'inverted index for tag service_id',
  INDEX idx_service_instance (`service_instance`) USING INVERTED COMMENT 'inverted index for tag service_instance',
  INDEX idx_threadPoolName (`threadPoolName`) USING INVERTED COMMENT 'inverted index for tag threadPoolName'
) ENGINE=OLAP
AGGREGATE KEY(`metric_time`, `ts`, `service`, `service_id`, `service_instance`, `threadPoolName`)
PARTITION BY RANGE(`metric_time`) ()
DISTRIBUTED BY HASH(`service_id`) BUCKETS 3
PROPERTIES (
  "replication_num" = "1",
  "dynamic_partition.enable" = "true",
  "dynamic_partition.time_unit" = "DAY",
  "dynamic_partition.start" = "-30",
  "dynamic_partition.end" = "3",
  "dynamic_partition.prefix" = "p",
  "dynamic_partition.create_history_partition" = "true"
);
INSERT INTO metric_service_thread_pool__v3 (`metric_time`, `ts`, `service`, `service_id`, `service_instance`, `threadPoolName`, `activeCount`, `completedTaskCount`, `corePoolSize`, `largestPoolSize`, `maximumPoolSize`, `poolSize`, `queueRemainingCapacity`, `queueSize`, `taskCount`)
SELECT FROM_UNIXTIME(FLOOR(`ts` / 1000)) AS `metric_time`, `ts`, `service`, `service_id`, `service_instance`, `threadPoolName`, `activeCount`, `completedTaskCount`, `corePoolSize`, `largestPoolSize`, `maximumPoolSize`, `poolSize`, `queueRemainingCapacity`, `queueSize`, `taskCount` FROM metric_service_thread_pool WHERE FROM_UNIXTIME(FLOOR(`ts` / 1000)) >= DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY);
DROP TABLE metric_service_thread_pool;
ALTER TABLE metric_service_thread_pool__v3 RENAME metric_service_thread_pool;

-- metric_service_thread_pool_cost
CREATE TABLE metric_service_thread_pool_cost__v3 (

  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `rootResource` VARCHAR(512),
  `service` VARCHAR(512),
  `service_id` VARCHAR(512),
  `service_instance` VARCHAR(512),
  `threadPoolName` VARCHAR(512),
  `type` VARCHAR(512),
  `cnt` BIGINT SUM,
  `maxDuration` DOUBLE SUM,
  `minDuration` DOUBLE SUM,
  `sumDuration` DOUBLE SUM,
  INDEX idx_rootResource (`rootResource`) USING INVERTED COMMENT 'inverted index for tag rootResource',
  INDEX idx_service (`service`) USING INVERTED COMMENT 'inverted index for tag service',
  INDEX idx_service_id (`service_id`) USING INVERTED COMMENT 'inverted index for tag service_id',
  INDEX idx_service_instance (`service_instance`) USING INVERTED COMMENT 'inverted index for tag service_instance',
  INDEX idx_threadPoolName (`threadPoolName`) USING INVERTED COMMENT 'inverted index for tag threadPoolName',
  INDEX idx_type (`type`) USING INVERTED COMMENT 'inverted index for tag type'
) ENGINE=OLAP
AGGREGATE KEY(`metric_time`, `ts`, `rootResource`, `service`, `service_id`, `service_instance`, `threadPoolName`, `type`)
PARTITION BY RANGE(`metric_time`) ()
DISTRIBUTED BY HASH(`service_id`) BUCKETS 3
PROPERTIES (
  "replication_num" = "1",
  "dynamic_partition.enable" = "true",
  "dynamic_partition.time_unit" = "DAY",
  "dynamic_partition.start" = "-30",
  "dynamic_partition.end" = "3",
  "dynamic_partition.prefix" = "p",
  "dynamic_partition.create_history_partition" = "true"
);
INSERT INTO metric_service_thread_pool_cost__v3 (`metric_time`, `ts`, `rootResource`, `service`, `service_id`, `service_instance`, `threadPoolName`, `type`, `cnt`, `maxDuration`, `minDuration`, `sumDuration`)
SELECT FROM_UNIXTIME(FLOOR(`ts` / 1000)) AS `metric_time`, `ts`, `rootResource`, `service`, `service_id`, `service_instance`, `threadPoolName`, `type`, `cnt`, `maxDuration`, `minDuration`, `sumDuration` FROM metric_service_thread_pool_cost WHERE FROM_UNIXTIME(FLOOR(`ts` / 1000)) >= DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY);
DROP TABLE metric_service_thread_pool_cost;
ALTER TABLE metric_service_thread_pool_cost__v3 RENAME metric_service_thread_pool_cost;

-- metric_service_trace
CREATE TABLE metric_service_trace__v3 (

  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `errorType` VARCHAR(512),
  `hostName` VARCHAR(512),
  `httpMethod` VARCHAR(512),
  `httpStatusCode` VARCHAR(512),
  `resource` VARCHAR(512),
  `service` VARCHAR(512),
  `service_id` VARCHAR(512),
  `service_instance` VARCHAR(512),
  `cnt` BIGINT SUM,
  `error` BIGINT SUM,
  `histogramCount` BIGINT SUM,
  `histogramMax` DOUBLE MAX,
  `maxDuration` DOUBLE MAX,
  `minDuration` DOUBLE MIN,
  `sumDuration` DOUBLE SUM,
  INDEX idx_errorType (`errorType`) USING INVERTED COMMENT 'inverted index for tag errorType',
  INDEX idx_hostName (`hostName`) USING INVERTED COMMENT 'inverted index for tag hostName',
  INDEX idx_httpMethod (`httpMethod`) USING INVERTED COMMENT 'inverted index for tag httpMethod',
  INDEX idx_httpStatusCode (`httpStatusCode`) USING INVERTED COMMENT 'inverted index for tag httpStatusCode',
  INDEX idx_resource (`resource`) USING INVERTED COMMENT 'inverted index for tag resource',
  INDEX idx_service (`service`) USING INVERTED COMMENT 'inverted index for tag service',
  INDEX idx_service_id (`service_id`) USING INVERTED COMMENT 'inverted index for tag service_id',
  INDEX idx_service_instance (`service_instance`) USING INVERTED COMMENT 'inverted index for tag service_instance'
) ENGINE=OLAP
AGGREGATE KEY(`metric_time`, `ts`, `errorType`, `hostName`, `httpMethod`, `httpStatusCode`, `resource`, `service`, `service_id`, `service_instance`)
PARTITION BY RANGE(`metric_time`) ()
DISTRIBUTED BY HASH(`service_id`) BUCKETS 3
PROPERTIES (
  "replication_num" = "1",
  "dynamic_partition.enable" = "true",
  "dynamic_partition.time_unit" = "DAY",
  "dynamic_partition.start" = "-30",
  "dynamic_partition.end" = "3",
  "dynamic_partition.prefix" = "p",
  "dynamic_partition.create_history_partition" = "true"
);
INSERT INTO metric_service_trace__v3 (`metric_time`, `ts`, `errorType`, `hostName`, `httpMethod`, `httpStatusCode`, `resource`, `service`, `service_id`, `service_instance`, `cnt`, `error`, `histogramCount`, `histogramMax`, `maxDuration`, `minDuration`, `sumDuration`)
SELECT FROM_UNIXTIME(FLOOR(`ts` / 1000)) AS `metric_time`, `ts`, `errorType`, `hostName`, `httpMethod`, `httpStatusCode`, `resource`, `service`, `service_id`, `service_instance`, `cnt`, `error`, `histogramCount`, `histogramMax`, `maxDuration`, `minDuration`, `sumDuration` FROM metric_service_trace WHERE FROM_UNIXTIME(FLOOR(`ts` / 1000)) >= DATE_SUB(CURRENT_DATE(), INTERVAL 30 DAY);
DROP TABLE metric_service_trace;
ALTER TABLE metric_service_trace__v3 RENAME metric_service_trace;

