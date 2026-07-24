-- DataBuff APM · Doris full schema (DROP + CREATE; single init script)
DROP DATABASE IF EXISTS databuff;
CREATE DATABASE databuff;

USE databuff;

-- meta (aligned with legacy MySQL dc_databuff_service)
CREATE TABLE meta_service (
  `id`                    VARCHAR(255) NOT NULL COMMENT '服务id',
  `name`                  VARCHAR(255)           COMMENT '服务展示名称',
  `service`               VARCHAR(255)           COMMENT '采集的服务名称',
  `service_type`          VARCHAR(255)           COMMENT '服务大类 web db cache custom mq',
  `apikey`                VARCHAR(255)           COMMENT 'apikey',
  `custom_tags`           VARCHAR(5000)          COMMENT '自定义标签',
  `type`                  VARCHAR(255)           COMMENT '服务类别',
  `fqdn`                  VARCHAR(255)           COMMENT 'cmdb fqdn',
  `source`                VARCHAR(255)           COMMENT '服务来源如k8s或vm',
  `describe`              VARCHAR(5000)          COMMENT '服务描述',
  `container_service`     VARCHAR(255)           COMMENT 'K8s服务的容器名',
  `virtual_service`       TINYINT                COMMENT '虚拟服务',
  `processRuntimeVersion` VARCHAR(256)           COMMENT '运行时版本',
  `processRuntimeName`    VARCHAR(256)           COMMENT '运行时名称',
  `language`              VARCHAR(256)           COMMENT 'agent编程语言',
  `datasource`            VARCHAR(256)           COMMENT '数据来源',
  `technology`            VARCHAR(255)           COMMENT '服务使用到的技术',
  `update_time`           DATETIME               COMMENT '服务更新时间'
) ENGINE=OLAP
UNIQUE KEY(`id`)
DISTRIBUTED BY HASH(`id`) BUCKETS 8
PROPERTIES ("replication_num" = "1");

-- trace
CREATE TABLE trace_dc_span (
  `minutes`              BIGINT       NOT NULL,
  `serviceId`            VARCHAR(255),
  `resource`             VARCHAR(4096) NOT NULL,
  `error`                TINYINT      NOT NULL,
  `slow`                 TINYINT      NOT NULL,
  `hours`                BIGINT       NOT NULL,
  `span_id`              VARCHAR(64)  NOT NULL,
  `startTime`            DATETIME     NOT NULL,
  `is_parent`            TINYINT      NOT NULL,
  `trace_id`             VARCHAR(64)  NOT NULL,
  `parent_id`            VARCHAR(64)  NOT NULL,
  `service`              VARCHAR(255) NOT NULL,
  `serviceInstance`      VARCHAR(255),
  `srcService`           VARCHAR(255),
  `srcServiceId`         VARCHAR(255),
  `srcServiceInstance`   VARCHAR(255),
  `dstService`           VARCHAR(255),
  `dstServiceId`         VARCHAR(255),
  `dstServiceInstance`   VARCHAR(255),
  `end`                  BIGINT       NOT NULL,
  `hostName`             VARCHAR(255) NOT NULL,
  `type`                 VARCHAR(50)  NOT NULL,
  `isIn`                 TINYINT      NOT NULL,
  `duration`             BIGINT       NOT NULL,
  `start`                BIGINT       NOT NULL,
  `host_id`              VARCHAR(100) NOT NULL,
  `meta`                 VARCHAR(10000),
  `name`                 VARCHAR(255) NOT NULL,
  `isOut`                TINYINT      NOT NULL,
  `metrics`              VARCHAR(1000),
  `meta.http.status_code` SMALLINT,
  `meta.error.type`      VARCHAR(100),
  `meta.peer.hostname`   VARCHAR(255),
  `meta.http.method`     VARCHAR(30),
  `meta.http.url`        VARCHAR(4096)
) ENGINE=OLAP
DUPLICATE KEY(`minutes`, `serviceId`, `resource`)
PARTITION BY RANGE(`startTime`) ()
DISTRIBUTED BY HASH(`trace_id`) BUCKETS 3
PROPERTIES (
  "replication_num" = "1",
  "dynamic_partition.enable" = "true",
  "dynamic_partition.time_unit" = "DAY",
  "dynamic_partition.start" = "-30",
  "dynamic_partition.end" = "3",
  "dynamic_partition.prefix" = "p"
);

-- OTLP logs (v0.2.0+)
CREATE TABLE log_dc_record (
  `log_time`            DATETIME      NOT NULL COMMENT 'wall clock from time_unix_nano',
  `service_id`          VARCHAR(64)   NOT NULL COMMENT 'ServiceKeyUtil.of(service.name)',
  `service`             VARCHAR(255)  NOT NULL,
  `trace_id`            VARCHAR(64)   NOT NULL DEFAULT '' COMMENT 'OTel trace_id hex',
  `span_id`             VARCHAR(64)   NOT NULL DEFAULT '' COMMENT 'OTel span_id hex',
  `hostname`            VARCHAR(255)  NOT NULL DEFAULT '' COMMENT 'OTel host.name',
  `service_instance`    VARCHAR(512)  NOT NULL DEFAULT '' COMMENT 'OTel service.instance.id',
  `severity`            VARCHAR(32)   NOT NULL DEFAULT 'UNSPECIFIED',
  `severity_number`     INT           NOT NULL DEFAULT 0,
  `body`                STRING                 COMMENT 'log message text (STRING; ingest truncates by Java String.length)',
  `attributes_json`     VARCHAR(15000)         COMMENT 'LogRecord attributes JSON',
  `resource_json`       VARCHAR(15000)          COMMENT 'Resource attributes JSON',
  `time_ns`             BIGINT        NOT NULL COMMENT 'original time_unix_nano',
  `observed_time_ns`    BIGINT        NOT NULL DEFAULT 0
) ENGINE=OLAP
DUPLICATE KEY(`log_time`, `service_id`, `service`)
PARTITION BY RANGE(`log_time`) ()
DISTRIBUTED BY HASH(`trace_id`) BUCKETS 3
PROPERTIES (
  "replication_num" = "1",
  "dynamic_partition.enable" = "true",
  "dynamic_partition.time_unit" = "DAY",
  "dynamic_partition.start" = "-30",
  "dynamic_partition.end" = "3",
  "dynamic_partition.prefix" = "p"
);

-- metric (daily dynamic partition on metric_time; ts = epoch millis; default 30-day retention)
CREATE TABLE metric_jvm (
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
  "dynamic_partition.prefix" = "p"
);

CREATE TABLE metric_service (
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
  "dynamic_partition.prefix" = "p"
);

CREATE TABLE metric_service_config (
  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `config.type` VARCHAR(512),
  `durationRange` VARCHAR(512),
  `isIn` VARCHAR(512),
  `isOut` VARCHAR(512),
  `operation` VARCHAR(512),
  `resource` VARCHAR(1024),
  `rootComponentType` VARCHAR(512),
  `rootResource` VARCHAR(1024),
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
  "dynamic_partition.prefix" = "p"
);

CREATE TABLE metric_service_cpu (
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
  "dynamic_partition.prefix" = "p"
);

CREATE TABLE metric_service_db (
  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `dbType` VARCHAR(512),
  `durationRange` VARCHAR(512),
  `isIn` VARCHAR(512),
  `isOut` VARCHAR(512),
  `isSlow` VARCHAR(512),
  `resource` VARCHAR(1024),
  `rootComponentType` VARCHAR(512),
  `rootResource` VARCHAR(1024),
  `service` VARCHAR(512),
  `service_id` VARCHAR(512),
  `service_instance` VARCHAR(512),
  `sqlContent` VARCHAR(1024),
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
  "dynamic_partition.prefix" = "p"
);

CREATE TABLE metric_service_db_connection_pool (
  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `connectionPoolDbType` VARCHAR(512),
  `connectionPoolName` VARCHAR(512),
  `connectionPoolType` VARCHAR(512),
  `connectionPoolUrl` VARCHAR(4096),
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
  "dynamic_partition.prefix" = "p"
);

CREATE TABLE metric_service_db_connection_pool_get (
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
  "dynamic_partition.prefix" = "p"
);

CREATE TABLE metric_service_exception (
  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `componentService` VARCHAR(512),
  `componentServiceId` VARCHAR(512),
  `componentServiceInstance` VARCHAR(512),
  `exceptionCode` VARCHAR(512),
  `exceptionName` VARCHAR(512),
  `isIn` VARCHAR(512),
  `isOut` VARCHAR(512),
  `resource` VARCHAR(1024),
  `rootComponentType` VARCHAR(512),
  `rootResource` VARCHAR(1024),
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
  "dynamic_partition.prefix" = "p"
);

CREATE TABLE metric_service_flow (
  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `entryInterfacePathId` VARCHAR(512),
  `entryPathId` VARCHAR(512),
  `interfacePathId` VARCHAR(512),
  `isIn` VARCHAR(512),
  `parentInterfacePathId` VARCHAR(512),
  `parentPathId` VARCHAR(512),
  `parentResource` VARCHAR(1024),
  `parentService` VARCHAR(512),
  `parentServiceId` VARCHAR(512),
  `pathId` VARCHAR(512),
  `resource` VARCHAR(1024),
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
  "dynamic_partition.prefix" = "p"
);

CREATE TABLE metric_service_health_status (
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
  "dynamic_partition.prefix" = "p"
);

CREATE TABLE metric_service_http (
  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `durationRange` VARCHAR(512),
  `httpCode` VARCHAR(512),
  `httpMethod` VARCHAR(512),
  `isIn` VARCHAR(512),
  `isOut` VARCHAR(512),
  `resource` VARCHAR(1024),
  `rootComponentType` VARCHAR(512),
  `rootResource` VARCHAR(1024),
  `service` VARCHAR(512),
  `service_id` VARCHAR(512),
  `service_instance` VARCHAR(512),
  `srcService` VARCHAR(512),
  `srcServiceId` VARCHAR(512),
  `srcServiceInstance` VARCHAR(512),
  `url` VARCHAR(4096),
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
  "dynamic_partition.prefix" = "p"
);

CREATE TABLE metric_service_http_connection_pool (
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
  "dynamic_partition.prefix" = "p"
);

CREATE TABLE metric_service_http_connection_pool_get (
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
  "dynamic_partition.prefix" = "p"
);

CREATE TABLE metric_service_instance (
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
  "dynamic_partition.prefix" = "p"
);

CREATE TABLE metric_service_io (
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
  "dynamic_partition.prefix" = "p"
);

CREATE TABLE metric_service_mem (
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
  "dynamic_partition.prefix" = "p"
);

CREATE TABLE metric_service_mq (
  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `broker` VARCHAR(512),
  `durationRange` VARCHAR(512),
  `group` VARCHAR(512),
  `isConsume` VARCHAR(512),
  `isIn` VARCHAR(512),
  `isOut` VARCHAR(512),
  `partition` VARCHAR(512),
  `resource` VARCHAR(1024),
  `rootComponentType` VARCHAR(512),
  `rootResource` VARCHAR(1024),
  `service` VARCHAR(512),
  `service_id` VARCHAR(512),
  `service_instance` VARCHAR(512),
  `srcService` VARCHAR(512),
  `srcServiceId` VARCHAR(512),
  `srcServiceInstance` VARCHAR(512),
  `topic` VARCHAR(1024),
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
  "dynamic_partition.prefix" = "p"
);

CREATE TABLE metric_service_net (
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
  "dynamic_partition.prefix" = "p"
);

CREATE TABLE metric_service_object_pool (
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
  "dynamic_partition.prefix" = "p"
);

CREATE TABLE metric_service_object_pool_get (
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
  "dynamic_partition.prefix" = "p"
);

CREATE TABLE metric_service_redis (
  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `command` VARCHAR(1024),
  `durationRange` VARCHAR(512),
  `isIn` VARCHAR(512),
  `isOut` VARCHAR(512),
  `resource` VARCHAR(1024),
  `rootComponentType` VARCHAR(512),
  `rootResource` VARCHAR(1024),
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
  "dynamic_partition.prefix" = "p"
);

CREATE TABLE metric_service_rpc (
  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `durationRange` VARCHAR(512),
  `isIn` VARCHAR(512),
  `isOut` VARCHAR(512),
  `resource` VARCHAR(1024),
  `rootComponentType` VARCHAR(512),
  `rootResource` VARCHAR(1024),
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
  "dynamic_partition.prefix" = "p"
);

CREATE TABLE metric_service_remote (
  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `durationRange` VARCHAR(512),
  `isIn` VARCHAR(512),
  `isOut` VARCHAR(512),
  `resource` VARCHAR(1024),
  `rootComponentType` VARCHAR(512),
  `rootResource` VARCHAR(1024),
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
  "dynamic_partition.prefix" = "p"
);

CREATE TABLE metric_service_tcp (
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
  "dynamic_partition.prefix" = "p"
);

CREATE TABLE metric_service_thread_pool (
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
  "dynamic_partition.prefix" = "p"
);

CREATE TABLE metric_service_thread_pool_cost (
  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `rootResource` VARCHAR(1024),
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
  "dynamic_partition.prefix" = "p"
);

CREATE TABLE metric_service_trace (
  `metric_time`            DATETIME     NOT NULL COMMENT 'wall clock from ts (Asia/Shanghai)',
  `ts` BIGINT NOT NULL,
  `errorType` VARCHAR(512),
  `hostName` VARCHAR(512),
  `httpMethod` VARCHAR(512),
  `httpStatusCode` VARCHAR(512),
  `resource` VARCHAR(1024),
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
  "dynamic_partition.prefix" = "p"
);

-- config
CREATE TABLE config_metric_core (
  `id`              BIGINT       NOT NULL,
  `type1`           VARCHAR(128) NOT NULL,
  `type2`           VARCHAR(128) NOT NULL,
  `type3`           VARCHAR(128) NOT NULL,
  `app`             VARCHAR(64)  NOT NULL,
  `database_name`   VARCHAR(128) NOT NULL,
  `measurement`     VARCHAR(256) NOT NULL,
  `doris_table`     VARCHAR(256) NOT NULL,
  `description`     VARCHAR(512),
  `tag_key_json`    STRING,
  `tag_value_json`  STRING,
  `fields_json`     STRING       NOT NULL,
  `enabled`         TINYINT      NOT NULL,
  `builtin`         TINYINT      NOT NULL,
  `updated_at`      DATETIME     NOT NULL
) ENGINE=OLAP
UNIQUE KEY(`id`)
DISTRIBUTED BY HASH(`id`) BUCKETS 8
PROPERTIES ("replication_num" = "1");

-- config_metric_core seed (应用性能; taxonomy aligned with alarm ruleSetting)
INSERT INTO config_metric_core
  (id, type1, type2, type3, app, database_name, measurement, doris_table, description,
   tag_key_json, tag_value_json, fields_json, enabled, builtin, updated_at)
VALUES
  (1, '应用性能', '业务观测', '业务事件', 'apm', 'databuff', 'biz.event', 'metric_biz_event', '【业务可观测指标】事件/接口的总耗时', '{"resource":"请求名","service":"服务名称","bizError":"业务错误","bizEventId":"业务事件ID","systemError":"系统错误","serviceId":"服务Id","bizEventName":"业务事件名称"}', '{}', '{"sumDuration":{"metric_cn":"总耗时","describe":"【业务可观测指标】事件/接口的总耗时","aggregatorType":"sum","metric_model":"SUM"},"slowCnt":{"metric_cn":"慢调用次数","describe":"【业务可观测指标】事件/接口的慢调用次数 (非错误且慢)","aggregatorType":"sum","metric_model":"SUM"},"error":{"metric_cn":"错误次数","describe":"【业务可观测指标】事件/接口的错误次数 (系统错误或业务异常)","aggregatorType":"sum","metric_model":"SUM"},"cnt":{"metric_cn":"调用次数","describe":"【业务可观测指标】事件/接口的调用次数","aggregatorType":"sum","metric_model":"SUM"}}', 1, 1, NOW()),
  (2, '应用性能', '业务观测', '场景KPI', 'apm', 'databuff', 'biz.event_kpi', 'metric_biz_event_kpi', '【业务可观测指标】标记本次调用在当前场景是否错误 (SUM=场景错误数)', '{"bizScenarioId":"业务场景ID","resource":"请求名","bizScenarioName":"业务场景名称","service":"服务名称","bizEventId":"业务事件ID","serviceId":"服务ID","bizEventName":"业务事件名称"}', '{}', '{"error":{"metric_cn":"场景错误标识","describe":"【业务可观测指标】标记本次调用在当前场景是否错误 (SUM=场景错误数)","aggregatorType":"sum","metric_model":"SUM"},"kpiAttributeValue":{"metric_cn":"KPI属性值","describe":"【业务可观测指标】场景自定义KPI属性值 (SUM=总KPI值)","aggregatorType":"sum","metric_model":"SUM"},"cnt":{"metric_cn":"转化标识","describe":"【业务可观测指标】场景中事件/资源调用标识 (SUM=转化量)","aggregatorType":"sum","metric_model":"SUM"}}', 1, 1, NOW()),
  (3, '应用性能', '业务系统', '外部请求', 'apm', 'databuff', 'business.service', 'metric_business_service', '平均耗时', '{"serviceType":"服务类型","dst_biz_id":"业务id","is_internal_call":"内部调用","srcServiceId":"请求来源的服务Id","busName":"业务系统","srcServiceType":"请求来源服务类型","src_biz_pid":"请求来源顶层业务id","src_biz_id":"请求来源业务id","service":"服务名称","srcService":"请求来源的服务名","isOut":"是否是出口","serviceId":"服务Id","dst_biz_pid":"顶层业务id","isIn":"是否是入口"}', '{}', '{"error":{"metric_cn":"业务系统服务的错误次数","describe":"【业务系统服务指标】业务系统服务的错误次数","aggregatorType":"sum","metric_model":"SUM"},"sumDuration":{"metric_cn":"业务系统服务的总耗时","describe":"【业务系统服务指标】业务系统服务的总耗时","aggregatorType":"sum","metric_model":"SUM"},"maxDuration":{"metric_cn":"业务系统服务的最大耗时","describe":"【业务系统服务指标】业务系统服务的最大耗时","aggregatorType":"sum","metric_model":"SUM"},"cnt":{"metric_cn":"业务系统服务的请求次数","describe":"【业务系统服务指标】业务系统服务的请求次数","aggregatorType":"sum","metric_model":"SUM"}}', 1, 1, NOW()),
  (4, '应用性能', 'JVM指标', '线程', 'jvm', 'databuff', 'jvm', 'metric_jvm', '【JVM 系统指标】JVM 进程自身占用的 CPU 资源比例', '{"instance":"实例","service":"服务名称","serviceInstance":"服务实例","serviceId":"服务Id","tag_host":"节点host"}', '{}', '{"cpu_load_process":{"metric_cn":"JVM 进程 CPU 负载","describe":"【JVM 系统指标】JVM 进程自身占用的 CPU 资源比例","aggregatorType":"avg","metric_model":"GAUGE"},"cpu_load_system":{"metric_cn":"JVM 系统 CPU 负载","describe":"【JVM 系统指标】系统级 CPU 负载，反映 JVM 进程所在系统的 CPU 使用压力","aggregatorType":"avg","metric_model":"GAUGE"},"thread_count":{"metric_cn":"JVM 线程数","describe":"【JVM 线程指标】JVM 当前活跃的线程数量","aggregatorType":"avg","metric_model":"GAUGE"}}', 1, 1, NOW()),
  (5, '应用性能', 'JVM指标', '堆外内存', 'jvm', 'databuff', 'jvm.buffer_pool', 'metric_jvm', '【JVM池指标】JVM 直接缓冲池已使用容量', '{"instance":"实例","service":"服务名称","serviceInstance":"服务实例","serviceId":"服务Id","tag_host":"节点host"}', '{}', '{"direct.used":{"metric_cn":"JVM直接缓冲池已使用容量","describe":"【JVM池指标】JVM 直接缓冲池已使用容量","aggregatorType":"avg","metric_model":"GAUGE"},"mapped.count":{"metric_cn":"JVM映射缓冲池数量","describe":"【JVM池指标】JVM 映射缓冲池数量","aggregatorType":"sum","metric_model":"SUM"},"mapped.used":{"metric_cn":"JVM映射缓冲池已使用容量","describe":"【JVM池指标】JVM 映射缓冲池已使用容量","aggregatorType":"avg","metric_model":"GAUGE"},"direct.capacity":{"metric_cn":"JVM直接缓冲池总容量","describe":"【JVM池指标】JVM 直接缓冲池总容量","aggregatorType":"avg","metric_model":"GAUGE"},"direct.count":{"metric_cn":"JVM直接缓冲池数量","describe":"【JVM池指标】JVM 直接缓冲池数量","aggregatorType":"sum","metric_model":"SUM"},"mapped.capacity":{"metric_cn":"JVM映射缓冲池总容量","describe":"【JVM池指标】JVM 映射缓冲池总容量","aggregatorType":"avg","metric_model":"GAUGE"}}', 1, 1, NOW()),
  (6, '应用性能', 'JVM指标', 'GC', 'jvm', 'databuff', 'jvm.gc', 'metric_jvm', '【JVM GC指标】JVM 进行主要（major）垃圾回收时的时间', '{"instance":"实例","service":"服务名称","serviceInstance":"服务实例","serviceId":"服务Id","tag_host":"节点host"}', '{}', '{"major_collection_time":{"metric_cn":"JVM 主要垃圾回收时间","describe":"【JVM GC指标】JVM 进行主要（major）垃圾回收时的时间","aggregatorType":"sum","metric_model":"SUM"},"eden_size":{"metric_cn":"JVM Eden 区大小","describe":"【JVM GC指标】JVM堆中的 Eden 区域的大小","aggregatorType":"avg","metric_model":"GAUGE"},"major_collection_count":{"metric_cn":"JVM 主要垃圾回收次数","describe":"【JVM GC指标】JVM 进行主要（major）垃圾回收时的次数","aggregatorType":"sum","metric_model":"SUM"},"minor_collection_count":{"metric_cn":"JVM 次要垃圾回收次数","describe":"【JVM GC指标】JVM 进行次要（minor）垃圾回收时的次数","aggregatorType":"sum","metric_model":"SUM"},"metaspace_size":{"metric_cn":"JVM 元空间大小","describe":"【JVM GC指标】JVM 中元空间的大小","aggregatorType":"avg","metric_model":"GAUGE"},"minor_collection_time":{"metric_cn":"JVM 次要垃圾回收时间","describe":"【JVM GC指标】JVM 进行次要（minor）垃圾回收时的时间","aggregatorType":"sum","metric_model":"SUM"},"old_gen_size":{"metric_cn":"JVM 老年代大小","describe":"【JVM GC指标】JVM 中老年代区域大小","aggregatorType":"avg","metric_model":"GAUGE"},"survivor_size":{"metric_cn":"JVM Survivor 区大小","describe":"【JVM GC指标】JVM 新生代中 Survivor 区的大小","aggregatorType":"avg","metric_model":"GAUGE"}}', 1, 1, NOW()),
  (7, '应用性能', 'JVM指标', '类加载', 'jvm', 'databuff', 'jvm.loaded_classes', 'metric_jvm', '【JVM类指标】JVM 已加载类的数量', '{"instance":"实例","service":"服务名称","serviceInstance":"服务实例","serviceId":"服务Id","tag_host":"节点host"}', '{}', '{"count":{"metric_cn":"JVM 已加载类数量","describe":"【JVM类指标】JVM 已加载类的数量","aggregatorType":"avg","metric_model":"GAUGE"}}', 1, 1, NOW()),
  (8, '应用性能', 'JVM指标', '堆内存', 'jvm', 'databuff', 'jvm.memory.heap', 'metric_jvm', '【JVM堆内存指标】JVM 堆内存的当前使用量', '{"instance":"实例","service":"服务名称","serviceInstance":"服务实例","serviceId":"服务Id","tag_host":"节点host"}', '{}', '{"used":{"metric_cn":"已使用堆内存","describe":"【JVM堆内存指标】JVM 堆内存的当前使用量","aggregatorType":"avg","metric_model":"GAUGE"},"committed":{"metric_cn":"已分配堆内存","describe":"【JVM堆内存指标】JVM 堆内存已分配的大小","aggregatorType":"avg","metric_model":"GAUGE"},"free":{"metric_cn":"JVM可使用堆内存","describe":"JVM可使用堆内存","aggregatorType":"sum","metric_model":"SUM"},"pct":{"metric_cn":"堆内存使用率","describe":"堆内存使用率","aggregatorType":"sum","metric_model":"SUM"},"max":{"metric_cn":"最大堆内存","describe":"【JVM堆内存指标】JVM 堆内存最大可分配空间大小","aggregatorType":"avg","metric_model":"GAUGE"},"init":{"metric_cn":"初始堆内存","describe":"【JVM堆内存指标】JVM 堆内存初始分配大小","aggregatorType":"avg","metric_model":"GAUGE"}}', 1, 1, NOW()),
  (9, '应用性能', 'JVM指标', '非堆内存', 'jvm', 'databuff', 'jvm.memory.noheap', 'metric_jvm', '【JVM非堆内存指标】JVM 非堆内存最大可分配空间大小', '{"instance":"实例","service":"服务名称","serviceInstance":"服务实例","serviceId":"服务Id","tag_host":"节点host"}', '{}', '{"max":{"metric_cn":"最大非堆内存","describe":"【JVM非堆内存指标】JVM 非堆内存最大可分配空间大小","aggregatorType":"avg","metric_model":"GAUGE"},"init":{"metric_cn":"初始非堆内存","describe":"【JVM非堆内存指标】JVM 非堆内存初始分配大小","aggregatorType":"avg","metric_model":"GAUGE"},"committed":{"metric_cn":"已分配非堆内存","describe":"【JVM非堆内存指标】JVM 非堆内存已分配的大小","aggregatorType":"avg","metric_model":"GAUGE"},"used":{"metric_cn":"已使用非堆内存","describe":"【JVM非堆内存指标】JVM 非堆内存的当前使用量","aggregatorType":"avg","metric_model":"GAUGE"}}', 1, 1, NOW()),
  (10, '应用性能', '入口请求', '请求总览', 'apm', 'databuff', 'service', 'metric_service', '【服务入口指标】服务请求次数', '{"serviceType":"服务类型","service":"服务名称","errorType":"错误类型","serviceInstance":"服务实例","serviceId":"服务Id"}', '{}', '{"cnt":{"metric_cn":"服务请求次数","describe":"【服务入口指标】服务请求次数","aggregatorType":"sum","metric_model":"SUM"},"cnt.pm":{"metric_cn":"服务每分钟请求数","describe":"服务每分钟请求数","aggregatorType":"sum","metric_model":"SUM"},"cpuTime.pct":{"metric_cn":"服务CPU平均耗时百分比","describe":"CPU平均耗时百分比","aggregatorType":"sum","metric_model":"SUM"},"apdex":{"metric_cn":"服务apdex","describe":"【服务入口指标】服务的 apdex","aggregatorType":"sum","metric_model":"SUM"},"error":{"metric_cn":"服务错误次数","describe":"【服务入口指标】服务的错误次数","aggregatorType":"sum","metric_model":"SUM"},"healthStatus":{"metric_cn":"服务健康状态","describe":"【服务入口指标】服务的服务健康状态","aggregatorType":"last","metric_model":"LAST"},"minDuration":{"metric_cn":"服务最小耗时","describe":"【服务入口指标】服务最小耗时","aggregatorType":"sum","metric_model":"SUM"},"maxDuration":{"metric_cn":"服务最大耗时","describe":"【服务入口指标】服务最大耗时","aggregatorType":"sum","metric_model":"SUM"},"i6000.apdex":{"metric_cn":"服务i6000应用性能指数","describe":"i6000应用性能指数","aggregatorType":"sum","metric_model":"SUM"},"slowCnt":{"metric_cn":"服务慢调用次数","describe":"【服务入口指标】服务的慢调用次数","aggregatorType":"sum","metric_model":"SUM"},"sumDuration":{"metric_cn":"服务总耗时","describe":"【服务入口指标】服务总耗时","aggregatorType":"sum","metric_model":"SUM"},"sumCpuTime":{"metric_cn":"服务 CPU 总耗时","describe":"【服务入口指标】服务 CPU 总耗时","aggregatorType":"sum","metric_model":"SUM"},"verySlowCnt":{"metric_cn":"服务非常慢调用次数","describe":"【服务入口指标】服务的非常慢调用次数","aggregatorType":"sum","metric_model":"SUM"}}', 1, 1, NOW()),
  (11, '应用性能', '系统指标', 'CPU', 'cpu', 'databuff', 'service.cpu', 'metric_service_cpu', '【服务系统指标】服务 CPU 使用率', '{"service":"服务名称","serviceInstance":"服务实例","serviceId":"服务Id"}', '{}', '{"usage_pct":{"metric_cn":"服务 CPU 使用率","describe":"【服务系统指标】服务 CPU 使用率","aggregatorType":"avg","metric_model":"GAUGE"}}', 1, 1, NOW()),
  (12, '应用性能', '出口请求', '访问DB', 'apm', 'databuff', 'service.db', 'metric_service_db', '平均耗时', '{"sqlDatabase":"数据库实例名称","isSlow":"是否慢sql","srcServiceId":"请求来源的服务Id","dbType":"db类型","sqlContent":"sql","durationRange":"耗时区间","sqlOperation":"sql操作","rootResource":"入口接口","service":"服务名称","srcService":"请求来源的服务名","isOut":"是否是出口","serviceInstance":"服务实例","serviceId":"服务Id","srcServiceInstance":"请求来源的服务实例","isIn":"是否是入口"}', '{}', '{"cnt":{"metric_cn":"数据库服务请求次数","describe":"【DB性能指标】数据库服务的请求次数","aggregatorType":"sum","metric_model":"SUM"},"error":{"metric_cn":"数据库服务错误次数","describe":"【DB性能指标】数据库服务的错误次数","aggregatorType":"sum","metric_model":"SUM"},"readRows":{"metric_cn":"数据库读取行数","describe":"【DB性能指标】数据库服务的读取行数","aggregatorType":"sum","metric_model":"SUM"},"sumDuration":{"metric_cn":"数据库服务总耗时","describe":"【DB性能指标】数据库服务的总耗时","aggregatorType":"sum","metric_model":"SUM"},"maxDuration":{"metric_cn":"数据库服务最大耗时","describe":"【DB性能指标】数据库服务的最大耗时","aggregatorType":"sum","metric_model":"SUM"},"slow":{"metric_cn":"慢DB 请求次数","describe":"【服务入口DB指标】慢请求次数","aggregatorType":"sum","metric_model":"SUM"},"updateRows":{"metric_cn":"数据库更新行数","describe":"【DB性能指标】数据库服务的更新行数","aggregatorType":"sum","metric_model":"SUM"},"minDuration":{"metric_cn":"数据库服务最小耗时","describe":"【DB性能指标】数据库服务的最小耗时","aggregatorType":"sum","metric_model":"SUM"},"cnt.pm":{"metric_cn":"数据库每分钟请求数","describe":"数据库每分钟请求数","aggregatorType":"sum","metric_model":"SUM"}}', 1, 1, NOW()),
  (13, '应用性能', '内部指标', '数据库连接池', 'jvm', 'databuff', 'service.db.connection.pool', 'metric_service_db_connection_pool', '【服务内部指标】服务连接池活跃数量', '{"connectionPoolType":"连接池类型","connectionPoolName":"连接池名称","connectionPoolUrl":"连接池URL","service":"服务名称","driverClassName":"驱动类名","connectionPoolDbType":"连接池DB类型","serviceInstance":"服务实例","serviceId":"服务Id","connectionPoolUsername":"连接池用户名"}', '{}', '{"activeSize":{"metric_cn":" 服务连接池活跃数量","describe":"【服务内部指标】服务连接池活跃数量","aggregatorType":"avg","metric_model":"GAUGE"},"waiterNum":{"metric_cn":" 服务连接池等待线程数量","describe":"【服务内部指标】服务连接池等待线程数量","aggregatorType":"avg","metric_model":"GAUGE"},"maxSize":{"metric_cn":" 服务连接池最大数量","describe":"【服务内部指标】服务连接池最大数量","aggregatorType":"max","metric_model":"MAX"},"idleSize":{"metric_cn":" 服务连接池空闲连接数量","describe":"【服务内部指标】服务连接池空闲连接数量","aggregatorType":"avg","metric_model":"GAUGE"}}', 1, 1, NOW()),
  (14, '应用性能', '内部指标', '数据库连接池连接', 'jvm', 'databuff', 'service.db.connection.pool.get', 'metric_service_db_connection_pool_get', '成功率', '{"service":"服务名称","srcService":"请求来源的服务名","srcServiceId":"请求来源的服务Id","dbType":"db类型","serviceInstance":"服务实例","serviceId":"服务Id","srcServiceInstance":"请求来源的服务实例","poolName":"连接池名称"}', '{}', '{"cnt":{"metric_cn":"获取数据库连接池连接次数","describe":"【服务内部指标】服务获取数据库连接池连接次数","aggregatorType":"sum","metric_model":"SUM"},"sumDuration":{"metric_cn":"获取数据库连接池连接总耗时","describe":"【服务内部指标】服务获取数据库连接池连接总耗时","aggregatorType":"sum","metric_model":"SUM"},"avgDuration":{"metric_cn":"单次获取数据库连接池连接平均耗时","describe":"单次获取对象池连接平均耗时","aggregatorType":"sum","metric_model":"SUM"},"minDuration":{"metric_cn":"获取数据库连接池连接最小耗时","describe":"【服务内部指标】服务获取数据库连接池连接最小耗时","aggregatorType":"sum","metric_model":"SUM"},"error":{"metric_cn":"获取数据库连接池连接错误次数","describe":"【服务内部指标】服务获取数据库连接池连接错误次数","aggregatorType":"sum","metric_model":"SUM"},"maxDuration":{"metric_cn":"获取数据库连接池连接最大耗时","describe":"【服务内部指标】服务获取数据库连接池连接最大耗时","aggregatorType":"sum","metric_model":"SUM"}}', 1, 1, NOW()),
  (15, '应用性能', '系统指标', '探针', 'agent', 'databuff', 'service.degrade', 'metric_service_degrade', '【采样率降级检测】服务cpu使用率', '{"serviceCode":"服务编码","service":"服务名称","serviceInstance":"服务实例","serviceId":"服务Id"}', '{}', '{"resource.cpu":{"metric_cn":"cpu使用率","describe":"【采样率降级检测】服务cpu使用率","aggregatorType":"mean","metric_model":"MEAN"},"resource.load":{"metric_cn":"cpu使用率","describe":"【采样率降级检测】服务单核负载","aggregatorType":"mean","metric_model":"MEAN"},"resource.heap":{"metric_cn":"堆内存老年代使用率","describe":"【采样率降级检测】服务堆内存老年代使用率","aggregatorType":"mean","metric_model":"MEAN"},"status":{"metric_cn":"是否降级状态","describe":"【主机指标】是否降级状态","aggregatorType":"max","metric_model":"MAX"}}', 1, 1, NOW()),
  (17, '应用性能', '内部指标', '异常', 'apm', 'databuff', 'service.exception', 'metric_service_exception', '【服务内部指标】服务异常次数', '{"resource":"请求名","componentService":"组件服务","rootComponentType":"根组件类型","componentServiceInstance":"组件服务实例","rootResource":"根请求名","service":"服务名称","componentServiceId":"组件服务Id","isOut":"是否是出口","serviceInstance":"服务实例","serviceId":"服务Id","exceptionCode":"异常码","isIn":"是否是入口","exceptionName":"异常名称"}', '{}', '{"cnt":{"metric_cn":"服务异常次数","describe":"【服务内部指标】服务异常次数","aggregatorType":"sum","metric_model":"SUM"},"error":{"metric_cn":"服务错误数","describe":"【服务内部指标】服务异错误数","aggregatorType":"sum","metric_model":"SUM"}}', 1, 1, NOW()),
  (18, '应用性能', '入口请求', 'HTTP接口', 'apm', 'databuff', 'service.http', 'metric_service_http', '【服务入口HTTP指标】服务请求次数', '{"resource":"请求名","srcServiceId":"请求来源的服务Id","durationRange":"耗时区间","httpMethod":"HTTP请求方法","url":"URL","urlType":"url的分类","rootResource":"入口接口","service":"服务名称","srcService":"请求来源的服务名","isOut":"是否是出口","serviceInstance":"服务实例","serviceId":"服务Id","httpCode":"状态码","srcServiceInstance":"请求来源的服务实例","isIn":"是否是入口"}', '{}', '{"cnt":{"metric_cn":"HTTP 服务请求次数","describe":"【服务入口HTTP指标】服务请求次数","aggregatorType":"sum","metric_model":"SUM"},"avgCpuTime":{"metric_cn":"HTTP接口 单个请求cpu平均时间","describe":"单个请求cpu平均时间","aggregatorType":"sum","metric_model":"SUM"},"cnt.pm":{"metric_cn":"HTTP接口 端点每分钟请求数","describe":"端点每分钟请求数","aggregatorType":"sum","metric_model":"SUM"},"slow":{"metric_cn":"慢HTTP 请求次数","describe":"【服务入口HTTP指标】慢请求次数","aggregatorType":"sum","metric_model":"SUM"},"sumDuration":{"metric_cn":"HTTP 服务总耗时","describe":"【服务入口HTTP指标】服务总耗时","aggregatorType":"sum","metric_model":"SUM"},"i6000.apdex":{"metric_cn":"i6000 Http端点性能指数","describe":"i6000Http端点性能指数","aggregatorType":"sum","metric_model":"SUM"},"minDuration":{"metric_cn":"HTTP 服务最小耗时","describe":"【服务入口HTTP指标】服务最小耗时","aggregatorType":"sum","metric_model":"SUM"},"cpuTime":{"metric_cn":"HTTP 服务 CPU 总耗时","describe":"【服务入口HTTP指标】服务 CPU 总耗时","aggregatorType":"sum","metric_model":"SUM"},"error":{"metric_cn":"HTTP 服务错误次数","describe":"【服务入口HTTP指标】服务错误次数","aggregatorType":"sum","metric_model":"SUM"},"maxDuration":{"metric_cn":"HTTP 服务最大耗时","describe":"【服务入口HTTP指标】服务最大耗时","aggregatorType":"sum","metric_model":"SUM"}}', 1, 1, NOW()),
  (19, '应用性能', '内部指标', 'Http连接池', 'jvm', 'databuff', 'service.http.connection.pool', 'metric_service_http_connection_pool', '【服务内部指标】服务 http 连接池当前连接数量', '{"service":"服务名称","httpConnectionPoolType":"http连接池类型","httpConnectionPoolName":"http连接池名称","serviceInstance":"服务实例","serviceId":"服务Id"}', '{}', '{"currentSize":{"metric_cn":"http 连接池当前连接数量","describe":"【服务内部指标】服务 http 连接池当前连接数量","aggregatorType":"max","metric_model":"MAX"},"activeSize":{"metric_cn":"http 连接池活跃数量","describe":"【服务内部指标】服务 http 连接池活跃数量","aggregatorType":"avg","metric_model":"GAUGE"},"maxSize":{"metric_cn":"http 连接池最大数量","describe":"【服务内部指标】服务 http 连接池最大数量","aggregatorType":"max","metric_model":"MAX"},"queueSize":{"metric_cn":"http 连接池等待队列数量","describe":"【服务内部指标】服务 http 连接池队列数量","aggregatorType":"max","metric_model":"MAX"}}', 1, 1, NOW()),
  (20, '应用性能', '内部指标', 'Http连接池连接', 'jvm', 'databuff', 'service.http.connection.pool.get', 'metric_service_http_connection_pool_get', '【服务内部指标】服务获取 HTTP 连接池连接最小耗时', '{"service":"服务名称","srcService":"请求来源的服务名","srcServiceId":"请求来源的服务Id","serviceInstance":"服务实例","serviceId":"服务Id","srcServiceInstance":"请求来源的服务实例","poolType":"Http连接池类型","poolName":"连接池名称"}', '{}', '{"minDuration":{"metric_cn":"获取 HTTP 连接池连接最小耗时","describe":"【服务内部指标】服务获取 HTTP 连接池连接最小耗时","aggregatorType":"sum","metric_model":"SUM"},"error":{"metric_cn":"获取 HTTP 连接池连接错误次数","describe":"【服务内部指标】服务获取 HTTP 连接池连接错误次数","aggregatorType":"sum","metric_model":"SUM"},"maxDuration":{"metric_cn":"获取 HTTP 连接池连接最大耗时","describe":"【服务内部指标】服务获取 HTTP 连接池连接最大耗时","aggregatorType":"sum","metric_model":"SUM"},"avgDuration":{"metric_cn":"单次获取Http连接池连接平均耗时","describe":"单次获取对象池连接平均耗时","aggregatorType":"sum","metric_model":"SUM"},"cnt":{"metric_cn":"获取 HTTP 连接池连接次数","describe":"【服务内部指标】服务获取 HTTP 连接池连接次数","aggregatorType":"sum","metric_model":"SUM"},"sumDuration":{"metric_cn":"获取 HTTP 连接池连接总耗时","describe":"【服务内部指标】服务获取 HTTP 连接池连接总耗时","aggregatorType":"sum","metric_model":"SUM"}}', 1, 1, NOW()),
  (21, '应用性能', '内部指标', '心跳', 'apm', 'databuff', 'service.instance', 'metric_service_instance', '【服务内部指标】服务实例个数', '{"k8sContainerId":"k8s containerId","hostIp":"主机IP","virtualService":"是否是中间件服务","pgId":"进程组Id","javaVersion":"java版本","pid":"进程ID","javaVendor":"java供应商","pgName":"进程组名称","k8sNamespace":"k8s namespace","hostname":"主机名称","pName":"进程名称","service":"服务名称","containerName":"containerName","k8sClusterId":"k8s集群Id","serviceInstance":"服务实例","serviceId":"服务Id","containerId":"容器ID","k8sPodName":"k8s pod名称"}', '{}', '{"metricsVal":{"metric_cn":"服务实例数","describe":"【服务内部指标】服务实例个数","aggregatorType":"avg","metric_model":"GAUGE"}}', 1, 1, NOW()),
  (22, '应用性能', '系统指标', '磁盘', 'disk', 'databuff', 'service.io', 'metric_service_io', '【服务系统指标】磁盘 I/O 读流量', '{"service":"服务名称","serviceInstance":"服务实例","serviceId":"服务Id"}', '{}', '{"read.rate":{"metric_cn":"服务磁盘 I/O 读流量","describe":"【服务系统指标】磁盘 I/O 读流量","aggregatorType":"avg","metric_model":"GAUGE"},"write.rate":{"metric_cn":"服务磁盘 I/O 写流量","describe":"【服务系统指标】磁盘 I/O 写流量","aggregatorType":"avg","metric_model":"GAUGE"}}', 1, 1, NOW()),
  (23, '应用性能', '系统指标', '内存', 'memory', 'databuff', 'service.mem', 'metric_service_mem', '【服务系统指标】服务已使用的内存容量', '{"service":"服务名称","serviceInstance":"服务实例","serviceId":"服务Id"}', '{}', '{"used":{"metric_cn":"服务已使用内存大小","describe":"【服务系统指标】服务已使用的内存容量","aggregatorType":"avg","metric_model":"GAUGE"},"size":{"metric_cn":"服务分配内存大小","describe":"【服务系统指标】服务分配的内存容量","aggregatorType":"avg","metric_model":"GAUGE"},"usage_pct":{"metric_cn":"服务内存使用率","describe":"【服务系统指标】服务的内存使用率","aggregatorType":"avg","metric_model":"GAUGE"}}', 1, 1, NOW()),
  (24, '应用性能', '入口请求', 'MQ消费', 'apm', 'databuff', 'service.mq', 'metric_service_mq', '【服务入口MQ指标】服务错误次数', '{"isConsumer":"是否是消费者","resource":"请求名","srcServiceId":"请求来源的服务Id","durationRange":"耗时区间","broker":"broker","type":"类型","partition":"分区号","rootResource":"入口接口","service":"服务名称","srcService":"请求来源的服务名","topic":"主题名称","isOut":"是否是出口","serviceInstance":"服务实例","serviceId":"服务Id","srcServiceInstance":"请求来源的服务实例","isIn":"是否是入口","group":"group"}', '{}', '{"error":{"metric_cn":"MQ 服务错误次数","describe":"【服务入口MQ指标】服务错误次数","aggregatorType":"sum","metric_model":"SUM"},"cpuTime":{"metric_cn":"MQ 服务 CPU 总耗时","describe":"【服务入口MQ指标】服务 CPU 总耗时","aggregatorType":"sum","metric_model":"SUM"},"cnt":{"metric_cn":"MQ 服务请求次数","describe":"【服务入口MQ指标】服务请求次数","aggregatorType":"sum","metric_model":"SUM"},"slow":{"metric_cn":"慢MQ 请求次数","describe":"【服务入口MQ指标】慢请求次数","aggregatorType":"sum","metric_model":"SUM"},"avgCpuTime":{"metric_cn":"MQ消费 单个请求cpu平均时间","describe":"单个请求cpu平均时间","aggregatorType":"sum","metric_model":"SUM"},"minDuration":{"metric_cn":"MQ 服务最小耗时","describe":"【服务入口MQ指标】服务最小耗时","aggregatorType":"sum","metric_model":"SUM"},"maxDuration":{"metric_cn":"MQ 服务最大耗时","describe":"【服务入口MQ指标】服务最大耗时","aggregatorType":"sum","metric_model":"SUM"},"sumDuration":{"metric_cn":"MQ 服务总耗时","describe":"【服务入口MQ指标】服务总耗时","aggregatorType":"sum","metric_model":"SUM"}}', 1, 1, NOW()),
  (25, '应用性能', '系统指标', '网络', 'network', 'databuff', 'service.net', 'metric_service_net', '【服务系统指标】服务接收的流量', '{"service":"服务名称","serviceInstance":"服务实例","serviceId":"服务Id"}', '{}', '{"bytes_rcvd":{"metric_cn":"服务接收流量","describe":"【服务系统指标】服务接收的流量","aggregatorType":"avg","metric_model":"GAUGE"},"bytes_sent":{"metric_cn":"服务发送流量","describe":"【服务系统指标】服务发送的流量","aggregatorType":"avg","metric_model":"GAUGE"}}', 1, 1, NOW()),
  (26, '应用性能', '内部指标', '对象池', 'jvm', 'databuff', 'service.object.pool', 'metric_service_object_pool', '【服务内部指标】服务服务对象池空闲数量', '{"objectPoolName":"对象池名称","service":"服务名称","objectPoolFairness":"对象池是否公平","serviceInstance":"服务实例","objectPoolObjectClass":"对象池池化对象类型","serviceId":"服务Id"}', '{}', '{"idleSize":{"metric_cn":"服务对象池空闲数量","describe":"【服务内部指标】服务服务对象池空闲数量","aggregatorType":"avg","metric_model":"GAUGE"},"maxSize":{"metric_cn":"服务对象池最大数量","describe":"【服务内部指标】服务池最大数量","aggregatorType":"max","metric_model":"MAX"},"activeSize":{"metric_cn":"服务对象池活跃数量","describe":"【服务内部指标】服务池活跃数量","aggregatorType":"avg","metric_model":"GAUGE"}}', 1, 1, NOW()),
  (27, '应用性能', '内部指标', '对象池连接', 'jvm', 'databuff', 'service.object.pool.get', 'metric_service_object_pool_get', '【服务内部指标】服务获取对象池连接错误次数', '{"service":"服务名称","srcService":"请求来源的服务名","srcServiceId":"请求来源的服务Id","serviceInstance":"服务实例","serviceId":"服务Id","srcServiceInstance":"请求来源的服务实例","objType":"对象池类型","poolName":"连接池名称"}', '{}', '{"error":{"metric_cn":"获取对象池连接错误次数","describe":"【服务内部指标】服务获取对象池连接错误次数","aggregatorType":"sum","metric_model":"SUM"},"maxDuration":{"metric_cn":"获取对象池连接最大耗时","describe":"【服务内部指标】服务获取对象池连接最大耗时","aggregatorType":"sum","metric_model":"SUM"},"minDuration":{"metric_cn":"获取对象池连接最小耗时","describe":"【服务内部指标】服务获取对象池连接最小耗时","aggregatorType":"sum","metric_model":"SUM"},"sumDuration":{"metric_cn":"获取对象池连接总耗时","describe":"【服务内部指标】服务获取对象池连接总耗时","aggregatorType":"sum","metric_model":"SUM"},"cnt":{"metric_cn":"获取对象池连接次数","describe":"【服务内部指标】服务获取对象池连接次数","aggregatorType":"sum","metric_model":"SUM"},"avgDuration":{"metric_cn":"单次获取对象池连接平均耗时","describe":"单次获取对象池连接平均耗时","aggregatorType":"sum","metric_model":"SUM"}}', 1, 1, NOW()),
  (28, '应用性能', '出口请求', '访问Redis', 'apm', 'databuff', 'service.redis', 'metric_service_redis', '平均耗时', '{"srcServiceId":"请求来源的服务Id","durationRange":"耗时区间","command":"命令","rootResource":"入口接口","service":"服务名称","srcService":"请求来源的服务名","isOut":"是否是出口","serviceInstance":"服务实例","serviceId":"服务Id","srcServiceInstance":"请求来源的服务实例","isIn":"是否是入口"}', '{}', '{"sumDuration":{"metric_cn":"Redis 服务总耗时","describe":"【Redis性能指标】Redis 服务的总耗时","aggregatorType":"sum","metric_model":"SUM"},"minDuration":{"metric_cn":"Redis 服务最小耗时","describe":"【Redis性能指标】Redis 服务的最小耗时","aggregatorType":"sum","metric_model":"SUM"},"slow":{"metric_cn":"慢Redis 请求次数","describe":"【服务入口Redis指标】慢请求次数","aggregatorType":"sum","metric_model":"SUM"},"error":{"metric_cn":"Redis 服务错误次数","describe":"【Redis性能指标】Redis 服务的错误次数","aggregatorType":"sum","metric_model":"SUM"},"maxDuration":{"metric_cn":"Redis 服务最大耗时","describe":"【Redis性能指标】Redis 服务的最大耗时","aggregatorType":"sum","metric_model":"SUM"},"cnt":{"metric_cn":"Redis 服务请求次数","describe":"【Redis性能指标】Redis 服务的请求次数","aggregatorType":"sum","metric_model":"SUM"}}', 1, 1, NOW()),
  (29, '应用性能', '出口请求', '外部调用', 'apm', 'databuff', 'service.remote', 'metric_service_remote', '【出口请求外部调用指标】服务 CPU 总耗时', '{"resource":"请求名","srcServiceId":"请求来源的服务Id","durationRange":"耗时区间","remoteType":"调用类型","rootResource":"入口接口","service":"服务名称","srcService":"请求来源的服务名","isOut":"是否是出口","serviceInstance":"服务实例","serviceId":"服务Id","srcServiceInstance":"请求来源的服务实例","isIn":"是否是入口"}', '{}', '{"cpuTime":{"metric_cn":"外部调用 服务 CPU 总耗时","describe":"【出口请求外部调用指标】服务 CPU 总耗时","aggregatorType":"sum","metric_model":"SUM"},"error":{"metric_cn":"外部调用 服务错误次数","describe":"【出口请求外部调用指标】服务错误次数","aggregatorType":"sum","metric_model":"SUM"},"cnt":{"metric_cn":"外部调用 服务请求次数","describe":"【出口请求外部调用指标】服务请求次数","aggregatorType":"sum","metric_model":"SUM"},"respBodyLength":{"metric_cn":"外部调用 响应体总大小","describe":"【出口请求外部调用指标】响应体总大小","aggregatorType":"sum","metric_model":"SUM"},"reqBodyLength":{"metric_cn":"外部调用 请求体总大小","describe":"【出口请求外部调用指标】请求体总大小","aggregatorType":"sum","metric_model":"SUM"},"minDuration":{"metric_cn":"外部调用 服务最小耗时","describe":"【出口请求外部调用指标】服务最小耗时","aggregatorType":"sum","metric_model":"SUM"},"sumDuration":{"metric_cn":"外部调用 服务总耗时","describe":"【出口请求外部调用指标】服务总耗时","aggregatorType":"sum","metric_model":"SUM"},"maxDuration":{"metric_cn":"外部调用 服务最大耗时","describe":"【出口请求外部调用指标】服务最大耗时","aggregatorType":"sum","metric_model":"SUM"}}', 1, 1, NOW()),
  (30, '应用性能', '入口请求', 'RPC接口', 'apm', 'databuff', 'service.rpc', 'metric_service_rpc', '平均耗时', '{"method":"HTTP请求方法","resource":"请求名","srcServiceId":"请求来源的服务Id","durationRange":"耗时区间","rootResource":"入口接口","service":"服务名称","srcService":"请求来源的服务名","isOut":"是否是出口","serviceInstance":"服务实例","serviceId":"服务Id","srcServiceInstance":"请求来源的服务实例","isIn":"是否是入口"}', '{}', '{"slow":{"metric_cn":"慢RPC 请求次数","describe":"【服务入口RPC指标】慢请求次数","aggregatorType":"sum","metric_model":"SUM"},"cnt":{"metric_cn":"RPC 服务请求次数","describe":"【服务入口RPC指标】服务请求次数","aggregatorType":"sum","metric_model":"SUM"},"i6000.apdex":{"metric_cn":"i6000 Rpc端点性能指数","describe":"i6000Rpc端点性能指数","aggregatorType":"sum","metric_model":"SUM"},"cpuTime":{"metric_cn":"RPC 服务 CPU 总耗时","describe":"【服务入口RPC指标】服务 CPU 总耗时","aggregatorType":"sum","metric_model":"SUM"},"maxDuration":{"metric_cn":"RPC 服务最大耗时","describe":"【服务入口RPC指标】服务最大耗时","aggregatorType":"sum","metric_model":"SUM"},"sumDuration":{"metric_cn":"RPC 服务总耗时","describe":"【服务入口RPC指标】服务总耗时","aggregatorType":"sum","metric_model":"SUM"},"minDuration":{"metric_cn":"RPC 服务最小耗时","describe":"【服务入口RPC指标】服务最小耗时","aggregatorType":"sum","metric_model":"SUM"},"error":{"metric_cn":"RPC 服务错误次数","describe":"【服务入口RPC指标】服务错误次数","aggregatorType":"sum","metric_model":"SUM"},"avgCpuTime":{"metric_cn":"RPC接口 单个请求cpu平均时间","describe":"单个请求cpu平均时间","aggregatorType":"sum","metric_model":"SUM"}}', 1, 1, NOW()),
  (31, '应用性能', '内部指标', '状态', 'apm', 'databuff', 'service.status', 'metric_service_status', '【服务内部指标】服务状态', '{"service":"服务名称","serviceInstance":"服务实例","serviceId":"服务Id"}', '{}', '{"metricsVal":{"metric_cn":"服务状态","describe":"【服务内部指标】服务状态","aggregatorType":"avg","metric_model":"GAUGE"}}', 1, 1, NOW()),
  (32, '应用性能', '系统指标', 'TCP', 'network', 'databuff', 'service.tcp', 'metric_service_tcp', '【服务TCP指标】服务的 TCP 建立连接个数', '{"service":"服务名称","serviceInstance":"服务实例","serviceId":"服务Id"}', '{}', '{"conns_established":{"metric_cn":"服务 TCP 建立连接数","describe":"【服务TCP指标】服务的 TCP 建立连接个数","aggregatorType":"avg","metric_model":"GAUGE"},"retransmit":{"metric_cn":"服务 TCP 重传次数","describe":"【服务TCP指标】服务的 TCP 重传次数","aggregatorType":"avg","metric_model":"GAUGE"}}', 1, 1, NOW()),
  (33, '应用性能', '内部指标', '线程池', 'jvm', 'databuff', 'service.thread.pool', 'metric_service_thread_pool', '【服务内部指标】服务线程池已完成的任务数量', '{"threadPoolName":"线程池名称","service":"服务名称","serviceInstance":"服务实例","serviceId":"服务Id"}', '{}', '{"completedTaskCount":{"metric_cn":"线程池已完成任务数","describe":"【服务内部指标】服务线程池已完成的任务数量","aggregatorType":"sum","metric_model":"SUM"},"corePoolSize":{"metric_cn":"线程池核心线程数","describe":"【服务内部指标】服务线程池的核心线程数","aggregatorType":"avg","metric_model":"GAUGE"},"activeCount":{"metric_cn":"线程池活跃线程数","describe":"【服务内部指标】服务线程池中正在执行任务的线程数量","aggregatorType":"avg","metric_model":"GAUGE"},"queueRemainingCapacity":{"metric_cn":"线程池队列剩余容量","describe":"【服务内部指标】服务线程池当前队列的剩余容量","aggregatorType":"avg","metric_model":"GAUGE"},"queueSize":{"metric_cn":"线程池队列任务数","describe":"【服务内部指标】服务线程池当前队列中的任务个数","aggregatorType":"avg","metric_model":"GAUGE"},"poolSize":{"metric_cn":"线程池当前线程数","describe":"【服务内部指标】服务线程池当前的线程数量","aggregatorType":"avg","metric_model":"GAUGE"},"largestPoolSize":{"metric_cn":"线程池历史最大线程数","describe":"【服务内部指标】服务线程池曾经创建过的最大线程数量","aggregatorType":"avg","metric_model":"GAUGE"},"maximumPoolSize":{"metric_cn":"线程池最大线程数","describe":"【服务内部指标】服务线程池的最大线程数","aggregatorType":"avg","metric_model":"GAUGE"},"taskCount":{"metric_cn":"线程池总任务数","describe":"【服务内部指标】服务线程池已经完成和未执行的任务总数","aggregatorType":"sum","metric_model":"SUM"}}', 1, 1, NOW()),
  (34, '应用性能', '内部指标', '线程池', 'jvm', 'databuff', 'service.thread.pool.cost', 'metric_service_thread_pool_cost', '平均耗时', '{"threadPoolName":"线程池名称","rootResource":"入口接口","service":"服务名称","serviceInstance":"服务实例","serviceId":"服务Id","type":"类型"}', '{}', '{"sumDuration":{"metric_cn":"总耗时","describe":"总耗时","aggregatorType":"sum","metric_model":"SUM"},"maxDuration":{"metric_cn":"最大耗时","describe":"最大耗时","aggregatorType":"max","metric_model":"MAX"},"minDuration":{"metric_cn":"最小耗时","describe":"最小耗时","aggregatorType":"min","metric_model":"MIN"},"cnt":{"metric_cn":"次数","describe":"次数","aggregatorType":"sum","metric_model":"SUM"}}', 1, 1, NOW());

CREATE TABLE config_event_rule (
  `id`           BIGINT       NOT NULL,
  `rule_name`    VARCHAR(256) NOT NULL,
  `classify`     VARCHAR(32)  NOT NULL,
  `detection_way` VARCHAR(32) NOT NULL,
  `service`      VARCHAR(256),
  `metric`       VARCHAR(64)  NOT NULL,
  `threshold`    DOUBLE       NOT NULL,
  `comparator`   VARCHAR(8)   NOT NULL,
  `enabled`      TINYINT      NOT NULL,
  `query_json`   STRING,
  `updated_at`   DATETIME     NOT NULL
) ENGINE=OLAP
UNIQUE KEY(`id`)
DISTRIBUTED BY HASH(`id`) BUCKETS 8
PROPERTIES ("replication_num" = "1");

-- Built-in detection rules (all services entry overview, enabled by default).
INSERT INTO config_event_rule
  (id, rule_name, classify, detection_way, service, metric, threshold, comparator, enabled, query_json, updated_at)
VALUES
  (1, '服务入口平均耗时过高', 'singleMetric', 'threshold', '*', 'service.avgDuration', 1000, 'gt', 1,
   '{"1":{"way":"threshold","period":60,"unit":"ms","view_unit":"ms","_scale":1,"time_aggregator":"avg","comparison":">","thresholds":{"critical":1000,"warning":null},"A":{"metric":"service.avgDuration","aggs":"avg","by":["service"],"from":[]}}}',
   NOW()),
  (2, '服务入口错误率过高', 'singleMetric', 'threshold', '*', 'service.error.pct', 10, 'gt', 1,
   '{"1":{"way":"threshold","period":60,"unit":"%","view_unit":"%","_scale":1,"time_aggregator":"avg","comparison":">","thresholds":{"critical":10,"warning":null},"A":{"metric":"service.error.pct","aggs":"avg","by":["service"],"from":[]}}}',
   NOW()),
  (3, '服务异常数过高', 'singleMetric', 'threshold', '*', 'service.exception', 10, 'gt', 1,
   '{"1":{"way":"threshold","period":60,"unit":"","view_unit":"","_scale":1,"time_aggregator":"sum","comparison":">","thresholds":{"critical":10,"warning":null},"A":{"metric":"service.exception.cnt","aggs":"sum","by":["service"],"from":[]}}}',
   NOW());

CREATE TABLE config_event (
  `id`            VARCHAR(64)  NOT NULL,
  `rule_id`       BIGINT       NOT NULL,
  `rule_name`     VARCHAR(256) NOT NULL,
  `service`       VARCHAR(256),
  `detection_way` VARCHAR(32)  NOT NULL,
  `level`         VARCHAR(16)  NOT NULL,
  `status`        VARCHAR(16)  NOT NULL,
  `message`       VARCHAR(2000),
  `group_key`     VARCHAR(256),
  `silenced`      TINYINT      NOT NULL,
  `triggered_at`  DATETIME     NOT NULL,
  INDEX idx_triggered_at (`triggered_at`) USING INVERTED COMMENT 'time range and ORDER BY triggered_at',
  INDEX idx_rule_id (`rule_id`) USING INVERTED COMMENT 'filter by rule',
  INDEX idx_service (`service`) USING INVERTED COMMENT 'filter by service',
  INDEX idx_status (`status`) USING INVERTED COMMENT 'filter by status on join and rule queries'
) ENGINE=OLAP
UNIQUE KEY(`id`)
DISTRIBUTED BY HASH(`id`) BUCKETS 8
PROPERTIES (
  "replication_num" = "1",
  "bloom_filter_columns" = "rule_id,service,status"
);

CREATE TABLE config_alarm_policy (
  `policy_type`  VARCHAR(32)  NOT NULL,
  `policy_id`    BIGINT       NOT NULL,
  `policy_name`  VARCHAR(256) NOT NULL,
  `enabled`      TINYINT      NOT NULL,
  `body_json`    STRING,
  `updated_at`   DATETIME     NOT NULL
) ENGINE=OLAP
UNIQUE KEY(`policy_type`, `policy_id`)
DISTRIBUTED BY HASH(`policy_id`) BUCKETS 8
PROPERTIES ("replication_num" = "1");

CREATE TABLE config_alarm (
  `id`             VARCHAR(64)  NOT NULL,
  `policy_id`      BIGINT       NOT NULL,
  `service`        VARCHAR(256),
  `detection_way`  VARCHAR(32)  NOT NULL,
  `level`          VARCHAR(16)  NOT NULL,
  `message`        VARCHAR(2000),
  `status`         VARCHAR(16)  NOT NULL,
  `triggered_at`   DATETIME     NOT NULL,
  `resolved_at`    DATETIME,
  INDEX idx_status (`status`) USING INVERTED COMMENT 'filter by alarm status',
  INDEX idx_service (`service`) USING INVERTED COMMENT 'filter by service',
  INDEX idx_level (`level`) USING INVERTED COMMENT 'filter by alarm level'
) ENGINE=OLAP
UNIQUE KEY(`id`)
DISTRIBUTED BY HASH(`id`) BUCKETS 8
PROPERTIES (
  "replication_num" = "1",
  "bloom_filter_columns" = "id,service,status,level"
);

CREATE TABLE config_alarm_event (
  `alarm_id`     VARCHAR(64)  NOT NULL,
  `event_id`     VARCHAR(64)  NOT NULL,
  `linked_at`    DATETIME     NOT NULL
) ENGINE=OLAP
UNIQUE KEY(`alarm_id`, `event_id`)
DISTRIBUTED BY HASH(`alarm_id`) BUCKETS 8
PROPERTIES ("replication_num" = "1");

CREATE TABLE config_notify_channel (
  `id`           BIGINT       NOT NULL,
  `channel_type` VARCHAR(32)  NOT NULL,
  `webhook_url`  VARCHAR(1024),
  `enabled`      TINYINT      NOT NULL,
  `updated_at`   DATETIME     NOT NULL
) ENGINE=OLAP
UNIQUE KEY(`id`)
DISTRIBUTED BY HASH(`id`) BUCKETS 4
PROPERTIES ("replication_num" = "1");

INSERT INTO config_notify_channel (id, channel_type, webhook_url, enabled, updated_at)
VALUES (1, 'webhook', '', 0, NOW());

CREATE TABLE config_llm_provider (
  `provider_code` VARCHAR(64)  NOT NULL,
  `display_name`  VARCHAR(128) NOT NULL,
  `base_url`      VARCHAR(512) NOT NULL,
  `enabled`       TINYINT      NOT NULL,
  `api_key_cipher` VARCHAR(1024),
  `default_model` VARCHAR(128),
  `api_type`      VARCHAR(64),
  `updated_at`    DATETIME     NOT NULL
) ENGINE=OLAP
UNIQUE KEY(`provider_code`)
DISTRIBUTED BY HASH(`provider_code`) BUCKETS 4
PROPERTIES ("replication_num" = "1");

CREATE TABLE config_llm_model (
  `provider_code`      VARCHAR(64)  NOT NULL,
  `model_id`           VARCHAR(128) NOT NULL,
  `display_name`       VARCHAR(128),
  `context_window`     INT,
  `max_output_tokens`  INT,
  `env_vars_json`      VARCHAR(4096),
  `is_default`         TINYINT      NOT NULL,
  `enabled`            TINYINT      NOT NULL,
  `updated_at`         DATETIME     NOT NULL
) ENGINE=OLAP
UNIQUE KEY(`provider_code`, `model_id`)
DISTRIBUTED BY HASH(`provider_code`) BUCKETS 4
PROPERTIES ("replication_num" = "1");

CREATE TABLE config_ai_message (
  `session_id`       VARCHAR(128) NOT NULL,
  `message_id`       VARCHAR(64)  NOT NULL,
  `session_type`     VARCHAR(64)  NOT NULL DEFAULT 'USER',
  `user_id`          VARCHAR(128),
  `user_name`        VARCHAR(256),
  `agent`            VARCHAR(128),
  `agent_type`       VARCHAR(32)  NOT NULL DEFAULT 'AGENT',
  `round_index`      INT          NOT NULL DEFAULT '1',
  `message_index`    INT          NOT NULL DEFAULT '1',
  `message_type`     VARCHAR(32)  NOT NULL,
  `message_status`   VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
  `model_name`       VARCHAR(128),
  `call_id`          VARCHAR(64),
  `tool_name`        VARCHAR(128),
  `content`          STRING,
  `attachments_json` STRING,
  `error`            STRING,
  `metadata_json`    STRING,
  `trigger_source`   VARCHAR(32),
  `created_at`       DATETIME     NOT NULL,
  `updated_at`       DATETIME     NOT NULL
) ENGINE=OLAP
UNIQUE KEY(`session_id`, `message_id`)
DISTRIBUTED BY HASH(`session_id`) BUCKETS 4
PROPERTIES ("replication_num" = "1");

CREATE TABLE config_ai_tool (
  `tool_id`        VARCHAR(128) NOT NULL,
  `name`           VARCHAR(256) NOT NULL,
  `category`       VARCHAR(128) NOT NULL DEFAULT "默认分类",
  `description`    STRING,
  `type`           VARCHAR(32)  NOT NULL,
  `implementation` STRING,
  `schema_json`    STRING,
  `config_json`    STRING,
  `enabled`        TINYINT      NOT NULL,
  `built_in`       TINYINT      NOT NULL,
  `version`        BIGINT       NOT NULL,
  `created_at`     DATETIME     NOT NULL,
  `updated_at`     DATETIME     NOT NULL
) ENGINE=OLAP
UNIQUE KEY(`tool_id`)
DISTRIBUTED BY HASH(`tool_id`) BUCKETS 4
PROPERTIES ("replication_num" = "1");

CREATE TABLE config_ai_skill (
  `skill_id`         VARCHAR(128) NOT NULL,
  `name`             VARCHAR(256) NOT NULL,
  `category`         VARCHAR(128) NOT NULL DEFAULT "默认分类",
  `description`      STRING,
  `content_uri`      STRING,
  `file_path`        STRING,
  `enabled`          TINYINT      NOT NULL,
  `built_in`         TINYINT      NOT NULL,
  `version`          BIGINT       NOT NULL,
  `checksum`         VARCHAR(128),
  `created_at`       DATETIME     NOT NULL,
  `updated_at`       DATETIME     NOT NULL
) ENGINE=OLAP
UNIQUE KEY(`skill_id`)
DISTRIBUTED BY HASH(`skill_id`) BUCKETS 4
PROPERTIES ("replication_num" = "1");

CREATE TABLE config_ai_expert (
  `expert_id`           VARCHAR(128) NOT NULL,
  `name`                VARCHAR(256) NOT NULL,
  `category`            VARCHAR(128) NOT NULL DEFAULT "默认分类",
  `description`         STRING,
  `type`                VARCHAR(32)  NOT NULL,
  `model_provider_code` VARCHAR(128),
  `model_name`          VARCHAR(256),
  `system_prompt`       STRING,
  `tool_ids_json`       STRING,
  `skill_ids_json`      STRING,
  `options_json`        STRING,
  `enabled`             TINYINT      NOT NULL,
  `built_in`            TINYINT      NOT NULL,
  `version`             BIGINT       NOT NULL,
  `created_at`          DATETIME     NOT NULL,
  `updated_at`          DATETIME     NOT NULL
) ENGINE=OLAP
UNIQUE KEY(`expert_id`)
DISTRIBUTED BY HASH(`expert_id`) BUCKETS 4
PROPERTIES ("replication_num" = "1");

CREATE TABLE config_ai_expert_task (
  `task_id`           VARCHAR(64)  NOT NULL,
  `parent_task_id`    VARCHAR(64),
  `session_id`        VARCHAR(64)  NOT NULL,
  `source_expert_id`  VARCHAR(128) NOT NULL,
  `target_expert_id`  VARCHAR(128) NOT NULL,
  `status`            VARCHAR(32)  NOT NULL,
  `input_text`        STRING,
  `output_text`       STRING,
  `error_text`        STRING,
  `metadata_json`     STRING,
  `created_at`        DATETIME     NOT NULL,
  `updated_at`        DATETIME     NOT NULL,
  `completed_at`      DATETIME
) ENGINE=OLAP
UNIQUE KEY(`task_id`)
DISTRIBUTED BY HASH(`task_id`) BUCKETS 4
PROPERTIES ("replication_num" = "1");

CREATE TABLE config_ai_capability (
  `capability_id`         VARCHAR(64)  NOT NULL,
  `name`                  VARCHAR(256) NOT NULL,
  `tagline`               STRING,
  `expert_id`             VARCHAR(128) NOT NULL,
  `prompts_json`          STRING,
  `enabled`               TINYINT      NOT NULL DEFAULT "1",
  `built_in`              TINYINT      NOT NULL DEFAULT "1",
  `version`               BIGINT       NOT NULL DEFAULT "1",
  `created_at`            DATETIME     NOT NULL,
  `updated_at`            DATETIME     NOT NULL,
  `default_name`          VARCHAR(256) NOT NULL,
  `default_tagline`       STRING,
  `default_expert_id`     VARCHAR(128) NOT NULL,
  `default_prompts_json`  STRING
) ENGINE=OLAP
UNIQUE KEY(`capability_id`)
DISTRIBUTED BY HASH(`capability_id`) BUCKETS 1
PROPERTIES ("replication_num" = "1");

-- 7 个 AI 能力种子数据（名称/文案/专家/4 条体验 prompt），默认值与当前值一致；
-- 配置页编辑只改当前列，"恢复默认" 把 default_* 列回写到当前列。
INSERT INTO config_ai_capability
  (capability_id, name, tagline, expert_id, prompts_json, enabled, built_in, version, created_at, updated_at,
   default_name, default_tagline, default_expert_id, default_prompts_json)
VALUES
  ('1',        '看得见',   '自然语言问系统',    '',
   '["查询最近1小时的服务列表","查询service-b服务的上下游拓扑","查询每个服务最近1小时的请求量趋势图","查询每个服务最近1小时的异常量趋势图"]',
   1, 1, 1, NOW(), NOW(),
   '看得见', '自然语言问系统', '',
   '["查询最近1小时的服务列表","查询service-b服务的上下游拓扑","查询每个服务最近1小时的请求量趋势图","查询每个服务最近1小时的异常量趋势图"]'),
  ('2',        '军团协同',  '多 Agent 协同',    '',
   '["找出最近 1 小时平均响应时间最高的服务，对它做一次巡检，并生成巡检报告","找出最近 1 小时平均响应时间最高的服务，对它进行故障定位","找出最近 1 小时日志ERROR最多的服务，对它做一次巡检，并生成巡检报告","找出最近 1 小时日志ERROR最多的服务，对它进行故障定位"]',
   1, 1, 1, NOW(), NOW(),
   '军团协同', '多 Agent 协同', '',
   '["找出最近 1 小时平均响应时间最高的服务，对它做一次巡检，并生成巡检报告","找出最近 1 小时平均响应时间最高的服务，对它进行故障定位","找出最近 1 小时日志ERROR最多的服务，对它做一次巡检，并生成巡检报告","找出最近 1 小时日志ERROR最多的服务，对它进行故障定位"]'),
  ('3',        '会巡检',   '服务巡检 + 报告',   'inspection',
   '["对service-a进行巡检","对service-b进行巡检","对所有服务进行巡检，找出健康度最差的几个汇总生成报告","对service-a以及上下游服务都进行巡检"]',
   1, 1, 1, NOW(), NOW(),
   '会巡检', '服务巡检 + 报告', 'inspection',
   '["对service-a进行巡检","对service-b进行巡检","对所有服务进行巡检，找出健康度最差的几个汇总生成报告","对service-a以及上下游服务都进行巡检"]'),
  ('4',        '会诊断',   '根因分析',         '',
   '["service-a 最近 1 小时瓶颈在哪里","service-b 最近 1 小时瓶颈在哪里","给我定位下最近5min内的服务报错的根因","给我定位下最近5min内的告警的根因"]',
   1, 1, 1, NOW(), NOW(),
   '会诊断', '根因分析', '',
   '["service-a 最近 1 小时瓶颈在哪里","service-b 最近 1 小时瓶颈在哪里","给我定位下最近5min内的服务报错的根因","给我定位下最近5min内的告警的根因"]'),
  ('5',        '会修复',   '运维专家自动解决',  'ops',
   '["列出当前所有的进程","找出当前内存占用高的进程","对当前服务打下火焰图，并输出火焰图html","容器 ai-apm-demo 一直重启，帮我 SSH 上去排查并修好"]',
   1, 1, 1, NOW(), NOW(),
   '会修复', '运维专家自动解决', 'ops',
   '["列出当前所有的进程","找出当前内存占用高的进程","对当前服务打下火焰图，并输出火焰图html","容器 ai-apm-demo 一直重启，帮我 SSH 上去排查并修好"]'),
  ('6',        '会预测',   '容量预测',         '',
   '["预测下service-a未来一周的请求量趋势图","预测下service-b未来一周的请求量趋势图","预测下mysql未来一周的请求量趋势图","预测下redis未来一周的请求量趋势图"]',
   1, 1, 1, NOW(), NOW(),
   '会预测', '容量预测', '',
   '["预测下service-a未来一周的请求量趋势图","预测下service-b未来一周的请求量趋势图","预测下mysql未来一周的请求量趋势图","预测下redis未来一周的请求量趋势图"]'),
  ('7',        '会答疑',   '答疑专家',         'qa',
   '["DataBuff 怎么用一条命令部署起来？需要哪些前置依赖？","Databuff 的整体架构是什么样的？","如何配置告警？","如何修改 DataBuff 7 大 AI 能力中的推荐？"]',
   1, 1, 1, NOW(), NOW(),
   '会答疑', '答疑专家', 'qa',
   '["DataBuff 怎么用一条命令部署起来？需要哪些前置依赖？","Databuff 的整体架构是什么样的？","如何配置告警？","如何修改 DataBuff 7 大 AI 能力中的推荐？"]');


CREATE TABLE config_alarm_silence (
  `service`        VARCHAR(256) NOT NULL,
  `silenced_until` DATETIME     NOT NULL,
  `updated_at`     DATETIME     NOT NULL
) ENGINE=OLAP
UNIQUE KEY(`service`)
DISTRIBUTED BY HASH(`service`) BUCKETS 4
PROPERTIES ("replication_num" = "1");

CREATE TABLE config_cockpit (
  `config_key`   VARCHAR(64)  NOT NULL,
  `config_value` VARCHAR(1024) NOT NULL,
  `updated_at`   DATETIME     NOT NULL
) ENGINE=OLAP
UNIQUE KEY(`config_key`)
DISTRIBUTED BY HASH(`config_key`) BUCKETS 4
PROPERTIES ("replication_num" = "1");

INSERT INTO config_cockpit (config_key, config_value, updated_at)
VALUES
  ('errorRateThreshold', '0.05', NOW()),
  ('minRequestCount', '10', NOW());

CREATE TABLE schema_version (
  `id`         INT      NOT NULL COMMENT 'singleton row id',
  `version`    INT      NOT NULL COMMENT 'applied migration version',
  `applied_at` DATETIME NOT NULL COMMENT 'last migration time'
) ENGINE=OLAP
UNIQUE KEY(`id`)
DISTRIBUTED BY HASH(`id`) BUCKETS 1
PROPERTIES ("replication_num" = "1");

INSERT INTO schema_version (id, version, applied_at)
VALUES (1, 4, NOW());
