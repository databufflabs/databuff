package com.databuff.apm.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Fill 后写入 {@code dc_span} 的 JSON 行（列名对齐 StarRocks/Doris 表）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class DcSpan {

    public long minutes;
    public String serviceId;
    public String resource;
    public int error;
    public int slow;
    public long hours;
    public String span_id;
    public String startTime;
    public int is_parent;
    public String trace_id;
    public String parent_id;
    public String service;
    public String serviceInstance;
    public String srcService;
    public String srcServiceId;
    public String srcServiceInstance;
    public String dstService;
    public String dstServiceId;
    public String dstServiceInstance;
    public long end;
    public String hostName;
    public String type;
    public int isIn;
    public long duration;
    public long start;
    public String host_id;
    public String meta;
    /** Ingest working copy of {@link #meta}; the single source of truth in the pipeline. */
    @JsonIgnore
    public transient Map<String, String> metaMap;
    /** Bumped on every metaMap mutation; used by SpanAnalysis cache invalidation. */
    @JsonIgnore
    public transient int metaRevision;
    @JsonIgnore
    public transient Object analysisCache;
    public String name;
    public int isOut;
    public String metrics;
    @JsonProperty("meta.http.status_code")
    public Integer metaHttpStatusCode;
    @JsonProperty("meta.error.type")
    public String metaErrorType;
    @JsonProperty("meta.peer.hostname")
    public String metaPeerHostname;
    @JsonProperty("meta.http.method")
    public String metaHttpMethod;
    @JsonProperty("meta.http.url")
    public String metaHttpUrl;
}
