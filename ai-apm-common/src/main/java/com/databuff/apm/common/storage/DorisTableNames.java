package com.databuff.apm.common.storage;

import com.databuff.apm.common.metric.MetricSchemaRegistry;

/** Doris table names in {@code databuff} (see deploy/common/sql/databuff.sql). */
public final class DorisTableNames {

    public static final String TRACE_DC_SPAN = "trace_dc_span";
    public static final String LOG_DC_RECORD = "log_dc_record";

    public static final String METRIC_SERVICE = "metric_service";
    public static final String METRIC_SERVICE_INSTANCE = "metric_service_instance";
    public static final String METRIC_SERVICE_HTTP = "metric_service_http";
    public static final String METRIC_SERVICE_RPC = "metric_service_rpc";
    public static final String METRIC_SERVICE_REMOTE = "metric_service_remote";
    public static final String METRIC_SERVICE_DB = "metric_service_db";
    public static final String METRIC_SERVICE_MQ = "metric_service_mq";
    public static final String METRIC_SERVICE_REDIS = "metric_service_redis";
    public static final String METRIC_SERVICE_CONFIG = "metric_service_config";
    public static final String METRIC_SERVICE_TRACE = "metric_service_trace";
    public static final String METRIC_SERVICE_FLOW = "metric_service_flow";
    public static final String METRIC_SERVICE_EXCEPTION = "metric_service_exception";
    public static final String METRIC_SERVICE_OBJECT_POOL = "metric_service_object_pool";
    public static final String METRIC_SERVICE_OBJECT_POOL_GET = "metric_service_object_pool_get";
    public static final String METRIC_SERVICE_HTTP_CONNECTION_POOL = "metric_service_http_connection_pool";
    public static final String METRIC_SERVICE_HTTP_CONNECTION_POOL_GET = "metric_service_http_connection_pool_get";
    public static final String METRIC_SERVICE_DB_CONNECTION_POOL = "metric_service_db_connection_pool";
    public static final String METRIC_SERVICE_DB_CONNECTION_POOL_GET = "metric_service_db_connection_pool_get";
    public static final String METRIC_SERVICE_THREAD_POOL = "metric_service_thread_pool";
    public static final String METRIC_JVM = "metric_jvm";
    public static final String METRIC_JVM_GC = "metric_jvm_gc";

    public static final String META_SERVICE = "meta_service";

    public static final String CONFIG_EVENT_RULE = "config_event_rule";
    public static final String CONFIG_EVENT = "config_event";
    public static final String CONFIG_ALARM_POLICY = "config_alarm_policy";
    public static final String CONFIG_ALARM = "config_alarm";
    public static final String CONFIG_ALARM_EVENT = "config_alarm_event";
    public static final String CONFIG_NOTIFY_CHANNEL = "config_notify_channel";
    public static final String CONFIG_LLM_PROVIDER = "config_llm_provider";
    public static final String CONFIG_LLM_MODEL = "config_llm_model";
    public static final String CONFIG_AI_MESSAGE = "config_ai_message";
    public static final String CONFIG_AI_TOOL = "config_ai_tool";
    public static final String CONFIG_AI_SKILL = "config_ai_skill";
    public static final String CONFIG_AI_EXPERT = "config_ai_expert";
    public static final String CONFIG_AI_EXPERT_TASK = "config_ai_expert_task";
    public static final String CONFIG_ALARM_SILENCE = "config_alarm_silence";
    public static final String CONFIG_COCKPIT = "config_cockpit";
    public static final String CONFIG_METRIC_CORE = "config_metric_core";

    private DorisTableNames() {
    }

    public static String metricTable(String measurement) {
        return MetricSchemaRegistry.metricTable(measurement);
    }
}
