package com.databuff.apm.common.meta;

import com.databuff.apm.common.model.DcSpan;
import com.databuff.apm.common.query.ApmQueryModels;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Service catalog row aligned with legacy MySQL {@code dc_databuff_service}. */
public final class MetaServiceInfo {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_FIELD = 255;
    private static final int MAX_TEXT = 5000;

    private final String id;
    private final String name;
    private final String service;
    private final String serviceType;
    private final String apikey;
    private final String customTags;
    private final String type;
    private final String fqdn;
    private final String source;
    private final String describe;
    private final String containerService;
    private final int virtualService;
    private final String processRuntimeName;
    private final String processRuntimeVersion;
    private final String language;
    private final String datasource;
    private final String technology;

    private MetaServiceInfo(Builder builder) {
        this.id = builder.id;
        this.name = builder.name;
        this.service = builder.service;
        this.serviceType = builder.serviceType;
        this.apikey = builder.apikey;
        this.customTags = builder.customTags;
        this.type = builder.type;
        this.fqdn = builder.fqdn;
        this.source = builder.source;
        this.describe = builder.describe;
        this.containerService = builder.containerService;
        this.virtualService = builder.virtualService;
        this.processRuntimeName = builder.processRuntimeName;
        this.processRuntimeVersion = builder.processRuntimeVersion;
        this.language = builder.language;
        this.datasource = builder.datasource;
        this.technology = builder.technology;
    }

    public static MetaServiceInfo minimal(String id, String serviceName) {
        String trimmedId = truncate(id.trim(), MAX_FIELD);
        String name = truncate(firstNonBlank(serviceName, trimmedId), MAX_FIELD);
        ServiceTypeClassifier.Classification classified = ServiceTypeClassifier.classify(trimmedId);
        return new Builder()
                .id(trimmedId)
                .name(name)
                .service(name)
                .serviceType(classified.serviceType())
                .type(classified.type())
                .technology(classified.technology())
                .datasource("OTLP")
                .build();
    }

    public static MetaServiceInfo fromDcSpan(DcSpan span) {
        if (span == null || span.serviceId == null || span.serviceId.isBlank()) {
            return null;
        }
        Map<String, String> attributes = OtelAttributeMaps.parse(span);
        return fromNames(span.serviceId, span.service, span.service, attributes, false);
    }

    public static MetaServiceInfo fromPoint(ApmQueryModels.MetaServicePoint point) {
        if (point == null || point.id() == null || point.id().isBlank()) {
            return null;
        }
        return new Builder()
                .id(point.id())
                .name(point.name())
                .service(point.service())
                .serviceType(point.serviceType())
                .apikey(point.apikey())
                .type(point.type())
                .technology(point.technology())
                .language(point.language())
                .datasource(point.datasource())
                .source(point.source())
                .fqdn(point.fqdn())
                .containerService(point.containerService())
                .virtualService(Boolean.TRUE.equals(point.virtualService()) ? 1 : 0)
                .describe(point.describe())
                .customTags(point.customTags())
                .processRuntimeName(point.processRuntimeName())
                .processRuntimeVersion(point.processRuntimeVersion())
                .build();
    }

    public static MetaServiceInfo fromMetric(String serviceId, String serviceName, String resourceMeta) {
        if (serviceId == null || serviceId.isBlank()) {
            return null;
        }
        return fromNames(serviceId, serviceName, serviceName, OtelAttributeMaps.parse(resourceMeta), false);
    }

    /** Virtual service catalog row from {@link VirtualServiceResolver}. */
    public static MetaServiceInfo fromVirtualService(
            String id,
            String service,
            String serviceType,
            String typeIcon) {
        if (id == null || id.isBlank() || service == null || service.isBlank()) {
            return null;
        }
        String trimmedId = truncate(id.trim(), MAX_FIELD);
        String display = truncate(service.trim(), MAX_FIELD);
        String resolvedType = serviceType != null && !serviceType.isBlank() ? serviceType : "web";
        String resolvedIcon = typeIcon != null && !typeIcon.isBlank() ? typeIcon : resolvedType;
        return new Builder()
                .id(trimmedId)
                .name(display)
                .service(display)
                .serviceType(resolvedType)
                .type(resolvedIcon)
                .technology(resolvedIcon)
                .datasource("OTLP")
                .virtualService(1)
                .build();
    }

    public static MetaServiceInfo fromNames(
            String id,
            String collectedService,
            String displayName,
            Map<String, String> attributes,
            boolean virtualService) {
        String trimmedId = truncate(id.trim(), MAX_FIELD);
        String collected = truncate(firstNonBlank(collectedService, trimmedId), MAX_FIELD);
        String display = truncate(firstNonBlank(displayName, collected), MAX_FIELD);
        ServiceTypeClassifier.Classification classified = classifyFromAttributes(trimmedId, display, attributes, virtualService);
        String language = truncate(OtelAttributeMaps.firstNonBlank(attributes,
                "telemetry.sdk.language", "language"), MAX_FIELD);
        String runtimeName = truncate(OtelAttributeMaps.firstNonBlank(attributes,
                "process.runtime.name", "process.executable.name"), 256);
        String runtimeVersion = truncate(OtelAttributeMaps.firstNonBlank(attributes,
                "process.runtime.version"), 256);
        String fqdn = truncate(OtelAttributeMaps.firstNonBlank(attributes, "host.name"), MAX_FIELD);
        String containerService = truncate(OtelAttributeMaps.firstNonBlank(attributes,
                "k8s.pod.name", "k8s.deployment.name", "container.id"), MAX_FIELD);
        String source = resolveSource(attributes);
        String technology = resolveTechnology(classified, language, runtimeName, attributes);
        return new Builder()
                .id(trimmedId)
                .name(display)
                .service(collected)
                .serviceType(classified.serviceType())
                .type(classified.type())
                .technology(technology)
                .language(language)
                .processRuntimeName(runtimeName)
                .processRuntimeVersion(runtimeVersion)
                .fqdn(fqdn)
                .source(source)
                .containerService(containerService)
                .customTags(buildCustomTags(attributes))
                .datasource("OTLP")
                .virtualService(virtualService ? 1 : 0)
                .build();
    }

    public MetaServiceInfo merge(MetaServiceInfo other) {
        if (other == null) {
            return this;
        }
        String mergedServiceType = preferLatestClassification(serviceType, other.serviceType);
        String mergedType = preferLatestClassification(type, other.type);
        boolean classificationChanged = !Objects.equals(serviceType, mergedServiceType)
                || !Objects.equals(type, mergedType);
        return new Builder()
                .id(id)
                .name(pick(other.name, name))
                .service(pick(other.service, service))
                .serviceType(mergedServiceType)
                .type(mergedType)
                // When service_type/type is corrected, take the incoming technology too.
                .technology(classificationChanged
                        ? pick(other.technology, technology)
                        : pick(technology, other.technology))
                .language(pick(language, other.language))
                .processRuntimeName(pick(processRuntimeName, other.processRuntimeName))
                .processRuntimeVersion(pick(processRuntimeVersion, other.processRuntimeVersion))
                .fqdn(pick(fqdn, other.fqdn))
                .source(pick(source, other.source))
                .containerService(pick(containerService, other.containerService))
                .customTags(pick(customTags, other.customTags))
                .apikey(pick(apikey, other.apikey))
                .describe(pick(describe, other.describe))
                .datasource(pick(datasource, other.datasource))
                .virtualService(Math.max(virtualService, other.virtualService))
                .build();
    }

    public Map<String, Object> toRow(String updateTime) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        putIfPresent(row, "name", name);
        putIfPresent(row, "service", service);
        putIfPresent(row, "service_type", serviceType);
        putIfPresent(row, "apikey", apikey);
        putIfPresent(row, "custom_tags", customTags);
        putIfPresent(row, "type", type);
        putIfPresent(row, "fqdn", fqdn);
        putIfPresent(row, "source", source);
        putIfPresent(row, "describe", describe);
        putIfPresent(row, "container_service", containerService);
        row.put("virtual_service", virtualService);
        putIfPresent(row, "processRuntimeName", processRuntimeName);
        putIfPresent(row, "processRuntimeVersion", processRuntimeVersion);
        putIfPresent(row, "language", language);
        putIfPresent(row, "datasource", datasource);
        putIfPresent(row, "technology", technology);
        row.put("update_time", updateTime);
        return row;
    }

    private static void putIfPresent(Map<String, Object> row, String key, String value) {
        if (value != null && !value.isBlank()) {
            row.put(key, value);
        }
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String service() {
        return service;
    }

    /** Whether enrichment fields differ from another catalog row. */
    public boolean enrichmentDiffers(MetaServiceInfo other) {
        if (other == null) {
            return true;
        }
        return !Objects.equals(name, other.name)
                || !Objects.equals(service, other.service)
                || !Objects.equals(serviceType, other.serviceType)
                || !Objects.equals(type, other.type)
                || !Objects.equals(technology, other.technology)
                || !Objects.equals(language, other.language)
                || !Objects.equals(processRuntimeName, other.processRuntimeName)
                || !Objects.equals(processRuntimeVersion, other.processRuntimeVersion)
                || !Objects.equals(fqdn, other.fqdn)
                || !Objects.equals(source, other.source)
                || !Objects.equals(containerService, other.containerService)
                || !Objects.equals(customTags, other.customTags)
                || !Objects.equals(describe, other.describe)
                || !Objects.equals(datasource, other.datasource)
                || virtualService != other.virtualService;
    }

  private static ServiceTypeClassifier.Classification classifyFromAttributes(
            String serviceId,
            String serviceName,
            Map<String, String> attributes,
            boolean virtualService) {
        String nameForPattern = firstNonBlank(serviceName, serviceId);
        if (virtualService) {
            String dbSystem = OtelAttributeMaps.firstNonBlank(attributes, "db.system", "db.type");
            if (dbSystem != null) {
                String type = normalizeDbType(dbSystem);
                return new ServiceTypeClassifier.Classification("db", type, type);
            }
            String messagingSystem = OtelAttributeMaps.firstNonBlank(attributes, "messaging.system");
            if (messagingSystem != null) {
                String type = messagingSystem.toLowerCase();
                return new ServiceTypeClassifier.Classification("mq", type.contains("kafka") ? "kafka" : type, type);
            }
            String rpcSystem = OtelAttributeMaps.firstNonBlank(attributes, "rpc.system");
            if (rpcSystem != null && !rpcSystem.isBlank()) {
                return new ServiceTypeClassifier.Classification("remote", rpcSystem.toLowerCase(), rpcSystem.toLowerCase());
            }
        }
        return ServiceTypeClassifier.classify(nameForPattern);
    }

    private static String resolveTechnology(
            ServiceTypeClassifier.Classification classified,
            String language,
            String runtimeName,
            Map<String, String> attributes) {
        if (language != null) {
            if ("java".equalsIgnoreCase(language) || "jvm".equalsIgnoreCase(language)) {
                return "jvm";
            }
            return truncate(language.toLowerCase(), MAX_FIELD);
        }
        if (runtimeName != null && !runtimeName.isBlank()) {
            return truncate(runtimeName.toLowerCase(), MAX_FIELD);
        }
        if ("db".equals(classified.serviceType())) {
            String dbSystem = OtelAttributeMaps.firstNonBlank(attributes, "db.system", "db.type");
            if (dbSystem != null) {
                return normalizeDbType(dbSystem);
            }
        }
        return classified.technology();
    }

    private static String resolveSource(Map<String, String> attributes) {
        if (OtelAttributeMaps.firstNonBlank(attributes, "k8s.namespace.name", "k8s.pod.name", "k8s.cluster.name") != null) {
            return "k8s";
        }
        String cloudProvider = OtelAttributeMaps.firstNonBlank(attributes, "cloud.provider");
        if (cloudProvider != null) {
            return truncate(cloudProvider, MAX_FIELD);
        }
        if (OtelAttributeMaps.firstNonBlank(attributes, "host.name") != null) {
            return "vm";
        }
        return "";
    }

    private static String buildCustomTags(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return "";
        }
        Map<String, String> tags = new LinkedHashMap<>();
        copyIfPresent(tags, attributes, "deployment.environment");
        copyIfPresent(tags, attributes, "service.namespace");
        copyIfPresent(tags, attributes, "k8s.namespace.name");
        copyIfPresent(tags, attributes, "k8s.cluster.name");
        copyIfPresent(tags, attributes, "service.version");
        copyIfPresent(tags, attributes, "service.instance.id");
        if (tags.isEmpty()) {
            return "";
        }
        try {
            String json = JSON.writeValueAsString(tags);
            return json.length() <= MAX_TEXT ? json : json.substring(0, MAX_TEXT);
        } catch (JsonProcessingException e) {
            return "";
        }
    }

    private static void copyIfPresent(Map<String, String> target, Map<String, String> source, String key) {
        String value = source.get(key);
        if (value != null && !value.isBlank()) {
            target.put(key, value.trim());
        }
    }

    private static String normalizeDbType(String dbSystem) {
        String lower = dbSystem.toLowerCase();
        if (lower.contains("mysql") || lower.contains("mariadb")) {
            return "mysql";
        }
        if (lower.contains("postgres")) {
            return "postgres";
        }
        if (lower.contains("mongo")) {
            return "mongo";
        }
        if (lower.contains("redis")) {
            return "redis";
        }
        if (lower.contains("elastic")) {
            return "elasticsearch";
        }
        return truncate(lower, MAX_FIELD);
    }

    private static String pick(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    /**
     * Prefer the incoming classification when present so catalog rows can be corrected
     * (e.g. legacy name-keyword {@code custom} → current {@code web}).
     */
    private static String preferLatestClassification(String existing, String incoming) {
        if (incoming != null && !incoming.isBlank()) {
            return incoming;
        }
        return existing;
    }

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        return fallback == null ? "" : fallback.trim();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static final class Builder {
        private String id;
        private String name;
        private String service;
        private String serviceType;
        private String apikey = "";
        private String customTags = "";
        private String type;
        private String fqdn = "";
        private String source = "";
        private String describe = "";
        private String containerService = "";
        private int virtualService;
        private String processRuntimeName = "";
        private String processRuntimeVersion = "";
        private String language = "";
        private String datasource = "OTLP";
        private String technology;

        Builder id(String value) {
            this.id = value;
            return this;
        }

        Builder name(String value) {
            this.name = value;
            return this;
        }

        Builder service(String value) {
            this.service = value;
            return this;
        }

        Builder serviceType(String value) {
            this.serviceType = value;
            return this;
        }

        Builder apikey(String value) {
            this.apikey = value;
            return this;
        }

        Builder customTags(String value) {
            this.customTags = value;
            return this;
        }

        Builder type(String value) {
            this.type = value;
            return this;
        }

        Builder fqdn(String value) {
            this.fqdn = value;
            return this;
        }

        Builder source(String value) {
            this.source = value;
            return this;
        }

        Builder describe(String value) {
            this.describe = value;
            return this;
        }

        Builder containerService(String value) {
            this.containerService = value;
            return this;
        }

        Builder virtualService(int value) {
            this.virtualService = value;
            return this;
        }

        Builder processRuntimeName(String value) {
            this.processRuntimeName = value;
            return this;
        }

        Builder processRuntimeVersion(String value) {
            this.processRuntimeVersion = value;
            return this;
        }

        Builder language(String value) {
            this.language = value;
            return this;
        }

        Builder datasource(String value) {
            this.datasource = value;
            return this;
        }

        Builder technology(String value) {
            this.technology = value;
            return this;
        }

        MetaServiceInfo build() {
            return new MetaServiceInfo(this);
        }
    }
}
