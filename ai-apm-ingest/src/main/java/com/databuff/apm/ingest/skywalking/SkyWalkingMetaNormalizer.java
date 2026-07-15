package com.databuff.apm.ingest.skywalking;

import com.databuff.apm.common.meta.OtelAttributeMaps;
import com.databuff.apm.common.trace.HttpSqlStandardizer;
import org.apache.skywalking.apm.network.language.agent.v3.SpanLayer;
import org.apache.skywalking.apm.network.language.agent.v3.SpanObject;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Normalize SkyWalking span tags to OTel-compatible semantics before downstream fill/virtual-service extraction.
 * <p>
 * Legacy SkyWalking JDBC plugins often report {@code db.type=sql}; this maps them to concrete {@code db.system}
 * values (e.g. {@code mysql}) using component id and peer hints so virtual services become {@code [mysql]host:port}.
 * MQ plugins report {@code mq.topic}/{@code mq.broker}; this maps them to {@code messaging.*} so virtual services
 * become {@code [kafka]topic} the same way OTel producers do.
 */
public final class SkyWalkingMetaNormalizer {

    /** Align with portal global default {@code sql_normalized_type=1} (values containing digits → ?). */
    public static final int DEFAULT_SQL_NORMALIZED_TYPE = 1;

    private static final Set<String> GENERIC_DB_TYPES = Set.of(
            "sql", "jdbc", "database", "db", "unknown", "");

    private SkyWalkingMetaNormalizer() {
    }

    public static void normalize(SpanObject span, Map<String, String> meta) {
        if (span == null || meta == null) {
            return;
        }
        normalizeDatabaseTags(span, meta);
        normalizeMessagingTags(span, meta);
    }

    private static void normalizeMessagingTags(SpanObject span, Map<String, String> meta) {
        if (!isMessagingSpan(span, meta)) {
            return;
        }
        String messagingSystem = OtelAttributeMaps.firstNonBlank(meta, "messaging.system");
        if (messagingSystem == null || messagingSystem.isBlank()) {
            String resolved = mqSystemFromSkyWalkingComponentId(span.getComponentId());
            if (resolved == null) {
                resolved = mqSystemFromOperationName(span.getOperationName());
            }
            if (resolved != null && !resolved.isBlank()) {
                meta.put("messaging.system", resolved);
            }
        }
        // SkyWalking RabbitMQ producers often set mq.queue with an empty mq.topic.
        String topic = OtelAttributeMaps.firstNonBlank(
                meta,
                "messaging.destination.name",
                "messaging.kafka.destination",
                "mq.topic",
                "mq.queue");
        if (topic != null && !topic.isBlank()
                && OtelAttributeMaps.firstNonBlank(meta, "messaging.destination.name") == null) {
            meta.put("messaging.destination.name", topic.trim());
        }
        String broker = OtelAttributeMaps.firstNonBlank(
                meta, "mq.broker", "net.peer.name", "server.address", "messaging.kafka.broker");
        if (broker != null && !broker.isBlank()) {
            if (OtelAttributeMaps.firstNonBlank(meta, "net.peer.name") == null) {
                meta.put("net.peer.name", broker.trim());
            }
            if (OtelAttributeMaps.firstNonBlank(meta, "server.address") == null) {
                String host = broker.trim();
                int colon = host.lastIndexOf(':');
                if (colon > 0 && colon < host.length() - 1
                        && host.substring(colon + 1).chars().allMatch(Character::isDigit)) {
                    meta.put("server.address", host.substring(0, colon));
                    if (OtelAttributeMaps.firstNonBlank(meta, "server.port") == null) {
                        meta.put("server.port", host.substring(colon + 1));
                    }
                } else {
                    meta.put("server.address", host);
                }
            }
        }
        if (OtelAttributeMaps.firstNonBlank(meta, "messaging.operation") == null) {
            String operation = mqOperationFromOperationName(span.getOperationName());
            if (operation != null) {
                meta.put("messaging.operation", operation);
            }
        }
    }

    private static boolean isMessagingSpan(SpanObject span, Map<String, String> meta) {
        if (span.getSpanLayer() == SpanLayer.MQ) {
            return true;
        }
        if (OtelAttributeMaps.firstNonBlank(
                meta, "messaging.system", "mq.topic", "mq.broker", "messaging.destination.name") != null) {
            return true;
        }
        return mqSystemFromSkyWalkingComponentId(span.getComponentId()) != null;
    }

    static String mqSystemFromSkyWalkingComponentId(int componentId) {
        return switch (componentId) {
            case 27, 40, 41 -> "kafka";
            case 25, 38, 39 -> "rocketmq";
            case 51, 52, 53 -> "rabbitmq";
            case 45, 46 -> "activemq";
            case 73, 74 -> "pulsar";
            default -> null;
        };
    }

    private static String mqSystemFromOperationName(String operationName) {
        if (operationName == null || operationName.isBlank()) {
            return null;
        }
        String lower = operationName.toLowerCase(Locale.ROOT);
        if (lower.startsWith("kafka/")) {
            return "kafka";
        }
        if (lower.startsWith("rocketmq/")) {
            return "rocketmq";
        }
        if (lower.startsWith("rabbitmq/")) {
            return "rabbitmq";
        }
        if (lower.startsWith("activemq/")) {
            return "activemq";
        }
        if (lower.startsWith("pulsar/")) {
            return "pulsar";
        }
        return null;
    }

    /**
     * Map SkyWalking MQ operation names such as {@code Kafka/topic/Producer} to OTel
     * {@code messaging.operation}. Callbacks are left unset so they are not treated as publish/consume.
     */
    static String mqOperationFromOperationName(String operationName) {
        if (operationName == null || operationName.isBlank()) {
            return null;
        }
        String lower = operationName.toLowerCase(Locale.ROOT);
        if (lower.contains("callback")) {
            return null;
        }
        if (lower.contains("producer") || lower.endsWith("/publish") || lower.contains("/publish")) {
            return "publish";
        }
        if (lower.contains("consumer") || lower.contains("/process") || lower.contains("receive")) {
            return "process";
        }
        return null;
    }

    private static void normalizeDatabaseTags(SpanObject span, Map<String, String> meta) {
        if (!isDatabaseSpan(span, meta)) {
            return;
        }
        String dbInstance = OtelAttributeMaps.firstNonBlank(meta, "db.instance");
        if (dbInstance != null && OtelAttributeMaps.firstNonBlank(meta, "db.name") == null) {
            meta.put("db.name", dbInstance);
        }
        String current = OtelAttributeMaps.firstNonBlank(meta, "db.system", "db.type");
        String resolved = resolveDbSystem(span, meta, current);
        if (resolved == null || resolved.isBlank()) {
            return;
        }
        meta.put("db.system", resolved);
        meta.put("db.type", resolved);
        normalizeSqlStatement(meta, DEFAULT_SQL_NORMALIZED_TYPE);
    }

    static void normalizeSqlStatement(Map<String, String> meta, int mode) {
        if (meta == null || mode == -1) {
            return;
        }
        for (String key : List.of("db.statement", "db.sql")) {
            String statement = meta.get(key);
            if (statement == null || statement.isBlank()) {
                continue;
            }
            String normalized = HttpSqlStandardizer.standardizeSql(statement, mode);
            meta.put(key, normalized);
            meta.put("normalized.resource", normalized);
            return;
        }
    }

    private static boolean isDatabaseSpan(SpanObject span, Map<String, String> meta) {
        if (span.getSpanLayer() == SpanLayer.Database) {
            return true;
        }
        if (OtelAttributeMaps.firstNonBlank(meta, "db.statement", "db.sql", "db.operation") != null) {
            return true;
        }
        return OtelAttributeMaps.firstNonBlank(meta, "db.system", "db.type", "db.instance") != null;
    }

    private static String resolveDbSystem(SpanObject span, Map<String, String> meta, String current) {
        String canonical = canonicalDbSystem(current);
        if (canonical != null && !isGenericDbType(canonical)) {
            return canonical;
        }
        String fromComponent = dbSystemFromSkyWalkingComponentId(span.getComponentId());
        if (fromComponent != null) {
            return fromComponent;
        }
        String fromPeer = inferDbSystemFromPeer(meta);
        if (fromPeer != null) {
            return fromPeer;
        }
        return canonical;
    }

    static String dbSystemFromSkyWalkingComponentId(int componentId) {
        return switch (componentId) {
            case 4, 32 -> "h2";
            case 5, 33, 3008, 3012, 5012, 7003, 7013, 8004 -> "mysql";
            case 6, 34 -> "oracle";
            case 7, 30, 35, 36, 56, 57, 3005, 3018, 5014, 7017, 8006 -> "redis";
            case 9, 42, 4006 -> "mongodb";
            case 20 -> "memcached";
            case 22, 37, 3007, 3013, 7010, 7016 -> "postgresql";
            case 31, 154, 3011 -> "sqlite";
            case 47, 48, 77 -> "elasticsearch";
            case 58 -> "zookeeper";
            case 63, 64 -> "solr";
            case 69, 70 -> "cassandra";
            case 86, 87 -> "mariadb";
            case 89, 90 -> "influxdb";
            case 104, 3003, 3006, 3010 -> "mssql";
            case 119, 120 -> "clickhouse";
            case 121, 122 -> "kylin";
            case 133, 134 -> "impala";
            case 153 -> "derby";
            case 155 -> "db2";
            case 163 -> "dmdb";
            default -> null;
        };
    }

    private static String inferDbSystemFromPeer(Map<String, String> meta) {
        String host = OtelAttributeMaps.firstNonBlank(
                meta, "server.address", "net.peer.name", "db.connection_string");
        String port = OtelAttributeMaps.firstNonBlank(meta, "server.port", "net.peer.port");
        String fromHost = inferDbSystemFromHost(host);
        if (fromHost != null) {
            return fromHost;
        }
        return inferDbSystemFromPort(port);
    }

    private static String inferDbSystemFromHost(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        String lower = host.trim().toLowerCase(Locale.ROOT);
        if (lower.contains("mysql") || lower.contains("mariadb")) {
            return lower.contains("mariadb") ? "mariadb" : "mysql";
        }
        if (lower.contains("postgres") || lower.contains("pgsql")) {
            return "postgresql";
        }
        if (lower.contains("mongo")) {
            return "mongodb";
        }
        if (lower.contains("redis")) {
            return "redis";
        }
        if (lower.contains("oracle")) {
            return "oracle";
        }
        if (lower.contains("clickhouse")) {
            return "clickhouse";
        }
        if (lower.contains("elastic") || lower.equals("es")) {
            return "elasticsearch";
        }
        if (lower.contains("sqlserver") || lower.contains("mssql")) {
            return "mssql";
        }
        if (lower.contains("cassandra")) {
            return "cassandra";
        }
        if (lower.contains("influx")) {
            return "influxdb";
        }
        return null;
    }

    private static String inferDbSystemFromPort(String port) {
        if (port == null || port.isBlank()) {
            return null;
        }
        return switch (port.trim()) {
            case "3306" -> "mysql";
            case "5432" -> "postgresql";
            case "6379" -> "redis";
            case "9200", "9300" -> "elasticsearch";
            case "27017" -> "mongodb";
            case "1433" -> "mssql";
            case "1521" -> "oracle";
            case "8123" -> "clickhouse";
            default -> null;
        };
    }

    private static String canonicalDbSystem(String dbType) {
        if (dbType == null || dbType.isBlank()) {
            return null;
        }
        String lower = dbType.trim().toLowerCase(Locale.ROOT);
        if (isGenericDbType(lower)) {
            return lower;
        }
        if (lower.contains("mysql")) {
            return "mysql";
        }
        if (lower.contains("mariadb")) {
            return "mariadb";
        }
        if (lower.contains("postgres")) {
            return "postgresql";
        }
        if (lower.contains("mongo")) {
            return "mongodb";
        }
        if (lower.contains("redis")) {
            return "redis";
        }
        if (lower.contains("elastic")) {
            return "elasticsearch";
        }
        if (lower.contains("oracle")) {
            return "oracle";
        }
        if (lower.contains("sqlserver") || lower.equals("mssql")) {
            return "mssql";
        }
        if (lower.contains("clickhouse")) {
            return "clickhouse";
        }
        if (lower.contains("cassandra")) {
            return "cassandra";
        }
        if (lower.contains("influx")) {
            return "influxdb";
        }
        if (lower.contains("h2")) {
            return "h2";
        }
        if (lower.contains("sqlite")) {
            return "sqlite";
        }
        if (lower.contains("memcached")) {
            return "memcached";
        }
        if (lower.contains("zookeeper")) {
            return "zookeeper";
        }
        if (lower.contains("solr")) {
            return "solr";
        }
        return lower;
    }

    private static boolean isGenericDbType(String dbType) {
        return dbType == null || GENERIC_DB_TYPES.contains(dbType);
    }
}
