package com.databuff.apm.ingest.skywalking;

import com.databuff.apm.common.meta.OtelAttributeMaps;
import com.databuff.apm.common.trace.HttpSqlStandardizer;
import org.apache.skywalking.apm.network.language.agent.v3.SpanLayer;
import org.apache.skywalking.apm.network.language.agent.v3.SpanObject;
import org.apache.skywalking.apm.network.language.agent.v3.SpanType;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Normalize SkyWalking span tags to OTel-compatible semantics before downstream fill/virtual-service extraction.
 * <p>
 * Legacy SkyWalking JDBC plugins often report {@code db.type=sql}; this maps them to concrete {@code db.system}
 * values (e.g. {@code mysql}) from {@link SpanObject#getComponentId()} so virtual services become
 * {@code [mysql]host:port}. Peer host/port and free-form operation names are never used to guess the system.
 * MQ plugins report {@code mq.topic}/{@code mq.broker}; {@code messaging.system} comes from component id,
 * and {@code messaging.operation} from {@link SpanType} (Exit=publish, Entry=process).
 * <p>
 * SQL literal standardization applies only here: SkyWalking agents send raw SQL, while OTLP / DataBuff agents
 * already normalize on the client. When enabled ({@code mode != -1}), replaced literals are also written to
 * OTel {@code db.query.parameter.<index>} (0-based). Default mode {@code 1} matches open-source before v0.1.7.
 * Override via {@code ingest.skywalking.sql-normalized-type} / env {@code INGEST_SQL_NORMALIZED_TYPE}.
 */
public final class SkyWalkingMetaNormalizer {

    /** Same default as pre-v0.1.7 hardcode (values containing digits → ?). */
    public static final int DEFAULT_SQL_NORMALIZED_TYPE = 1;

    private static final Set<String> GENERIC_DB_TYPES = Set.of(
            "sql", "jdbc", "database", "db", "unknown", "");

    private static volatile int sqlNormalizedType = DEFAULT_SQL_NORMALIZED_TYPE;

    private SkyWalkingMetaNormalizer() {
    }

    public static int sqlNormalizedType() {
        return sqlNormalizedType;
    }

    /**
     * Apply SQL normalize mode from env/YAML. Out-of-range values fall back to
     * {@link #DEFAULT_SQL_NORMALIZED_TYPE}.
     *
     * @return the mode actually applied
     */
    public static int setSqlNormalizedType(int mode) {
        int applied = (mode < -1 || mode > 1) ? DEFAULT_SQL_NORMALIZED_TYPE : mode;
        sqlNormalizedType = applied;
        return applied;
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
            String operation = mqOperationFromSpanType(span.getSpanType());
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

    /**
     * Map SkyWalking {@link SpanType} to OTel {@code messaging.operation}.
     * Exit ≈ producer publish, Entry ≈ consumer process; Local (e.g. producer callback) left unset.
     */
    static String mqOperationFromSpanType(SpanType spanType) {
        if (spanType == null) {
            return null;
        }
        return switch (spanType) {
            case Exit -> "publish";
            case Entry -> "process";
            default -> null;
        };
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
        String resolved = resolveDbSystem(span, current);
        if (resolved == null || resolved.isBlank()) {
            return;
        }
        meta.put("db.system", resolved);
        meta.put("db.type", resolved);
        normalizeSqlStatement(meta, sqlNormalizedType);
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
            HttpSqlStandardizer.SqlNormalizeResult result =
                    HttpSqlStandardizer.standardizeSqlWithParameters(statement, mode);
            meta.put(key, result.sql());
            meta.put("normalized.resource", result.sql());
            List<String> parameters = result.parameters();
            for (int i = 0; i < parameters.size(); i++) {
                meta.put("db.query.parameter." + i, parameters.get(i));
            }
            return;
        }
    }

    private static boolean isDatabaseSpan(SpanObject span, Map<String, String> meta) {
        if (span.getSpanLayer() == SpanLayer.Database || span.getSpanLayer() == SpanLayer.Cache) {
            return true;
        }
        if (dbSystemFromSkyWalkingComponentId(span.getComponentId()) != null) {
            return true;
        }
        if (OtelAttributeMaps.firstNonBlank(meta, "db.statement", "db.sql", "db.operation") != null) {
            return true;
        }
        return OtelAttributeMaps.firstNonBlank(meta, "db.system", "db.type", "db.instance") != null;
    }

    /**
     * Resolve concrete {@code db.system} from an explicit tag, else SkyWalking {@code componentId}.
     * Never infer from peer hostname / port.
     */
    private static String resolveDbSystem(SpanObject span, String current) {
        String canonical = canonicalDbSystem(current);
        if (canonical != null && !isGenericDbType(canonical)) {
            return canonical;
        }
        String fromComponent = dbSystemFromSkyWalkingComponentId(span.getComponentId());
        if (fromComponent != null) {
            return fromComponent;
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
