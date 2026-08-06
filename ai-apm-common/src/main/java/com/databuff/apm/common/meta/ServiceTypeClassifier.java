package com.databuff.apm.common.meta;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Infers {@code service_type}/{@code type}/{@code technology} like legacy {@code dc_databuff_service}. */
public final class ServiceTypeClassifier {

    private static final Pattern DB_PATTERN = Pattern.compile(
            "mysql|mariadb|postgres|oracle|mongo|sqlserver|clickhouse|doris|jdbc|database|elasticsearch|elastic|\\bdb\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MQ_PATTERN = Pattern.compile(
            "kafka|rabbit|rocket|pulsar|activemq|ons|\\bmq\\b|nats|topic",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CACHE_PATTERN = Pattern.compile(
            "redis|memcached|cache|ehcache|caffeine|hazelcast",
            Pattern.CASE_INSENSITIVE);
    private static final int MAX_CACHE_SIZE = 10_000;
    private static final Map<String, Classification> CACHE = new ConcurrentHashMap<>();

    private ServiceTypeClassifier() {
    }

    public record Classification(String serviceType, String type, String technology) {
    }

    public static Classification classify(String serviceId) {
        if (serviceId == null || serviceId.isBlank()) {
            return new Classification("web", "web", "web");
        }
        Classification cached = CACHE.get(serviceId);
        if (cached != null) {
            return cached;
        }
        String serviceType = inferServiceType(serviceId);
        String type = inferTypeIcon(serviceId, serviceType);
        String technology = inferTechnology(serviceId, serviceType, type);
        Classification classification = new Classification(serviceType, type, technology);
        if (CACHE.size() < MAX_CACHE_SIZE) {
            CACHE.putIfAbsent(serviceId, classification);
        }
        return classification;
    }

    private static String inferServiceType(String serviceId) {
        String component = bracketComponent(serviceId);
        if (component != null && "remote".equalsIgnoreCase(component)) {
            return "remote";
        }
        if (DB_PATTERN.matcher(serviceId).find()) {
            return "db";
        }
        if (MQ_PATTERN.matcher(serviceId).find()) {
            return "mq";
        }
        if (CACHE_PATTERN.matcher(serviceId).find()) {
            return "cache";
        }
        return "web";
    }

    private static String inferTypeIcon(String serviceId, String serviceType) {
        return switch (serviceType) {
            case "db" -> inferDbTypeIcon(serviceId);
            case "mq" -> "kafka";
            case "cache" -> "redis";
            case "remote" -> "remote";
            default -> "web";
        };
    }

    private static String inferDbTypeIcon(String serviceId) {
        String component = bracketComponent(serviceId);
        if (component != null) {
            return normalizeDbTypeIcon(component);
        }
        String lower = serviceId.toLowerCase(Locale.ROOT);
        if (lower.contains("elasticsearch") || lower.contains("elastic")) {
            return "elasticsearch";
        }
        if (DB_PATTERN.matcher(serviceId).find()) {
            return "mysql";
        }
        return "db";
    }

    private static String bracketComponent(String serviceId) {
        if (serviceId == null || !serviceId.startsWith("[")) {
            return null;
        }
        int end = serviceId.indexOf(']');
        if (end <= 1) {
            return null;
        }
        return serviceId.substring(1, end);
    }

    private static String normalizeDbTypeIcon(String component) {
        String lower = component.toLowerCase(Locale.ROOT);
        if (lower.contains("elastic")) {
            return "elasticsearch";
        }
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
        return lower;
    }

    private static String inferTechnology(String serviceId, String serviceType, String type) {
        if (!"web".equals(serviceType)) {
            return type;
        }
        String lower = serviceId.toLowerCase(Locale.ROOT);
        if (lower.contains("java") || lower.endsWith("-service") || lower.contains("spring")) {
            return "java";
        }
        if (lower.contains("go") || lower.contains("golang")) {
            return "go";
        }
        if (lower.contains("node") || lower.contains("js")) {
            return "nodejs";
        }
        if (lower.contains("python") || lower.contains("py")) {
            return "python";
        }
        return "web";
    }
}
