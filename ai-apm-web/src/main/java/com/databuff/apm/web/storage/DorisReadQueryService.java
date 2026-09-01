package com.databuff.apm.web.storage;

import com.databuff.apm.web.config.ApmStorageProperties;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Read-only SQL against Doris via the web JDBC pool (MySQL protocol to FE query port).
 */
@Service
public class DorisReadQueryService {

    public static final int DEFAULT_MAX_ROWS = 200;
    public static final int HARD_MAX_ROWS = 1000;
    public static final int QUERY_TIMEOUT_SECONDS = 30;

    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("(?m)--.*?$");
    private static final Pattern MULTI_STATEMENT = Pattern.compile(";\\s*\\S");
    private static final Pattern STRING_LITERAL = Pattern.compile("'([^']|'')*'|\"([^\"]|\"\")*\"");
    private static final Pattern SENSITIVE_LLM_CREDENTIAL_IDENTIFIER = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_$])`?(config_llm_provider|api_key_cipher)`?(?![A-Za-z0-9_$])");
    /** Applied only to SELECT/WITH after stripping string literals. */
    private static final Pattern FORBIDDEN_IN_SELECT = Pattern.compile(
            "(?i)\\b(INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|TRUNCATE|REPLACE|GRANT|REVOKE|"
                    + "LOAD|EXPORT|CALL|EXECUTE|KILL|ADMIN|INSTALL|UNINSTALL|RESTORE|BACKUP|"
                    + "CANCEL|PAUSE|RESUME|CLEAN|RECOVER|SYNC|OUTFILE|DUMPFILE)\\b"
                    + "|(?i)\\bINTO\\s+OUTFILE\\b|(?i)\\bINTO\\s+DUMPFILE\\b");

    private static final Set<String> ALLOWED_LEADING = Set.of(
            "SELECT", "SHOW", "DESCRIBE", "DESC", "EXPLAIN", "WITH");
    /** Inherently metadata/read statements — skip keyword denylist (e.g. SHOW CREATE). */
    private static final Set<String> METADATA_LEADING = Set.of("SHOW", "DESCRIBE", "DESC", "EXPLAIN");

    private final DataSource dorisDataSource;
    private final DorisAvailability dorisAvailability;
    private final String defaultDatabase;

    public DorisReadQueryService(
            DataSource dorisDataSource,
            DorisAvailability dorisAvailability,
            ApmStorageProperties storageProperties) {
        this.dorisDataSource = dorisDataSource;
        this.dorisAvailability = dorisAvailability;
        this.defaultDatabase = storageProperties.configDatabase();
    }

    public QueryResult query(String sql, Integer maxRows) {
        String normalized = normalizeSql(sql);
        validateReadOnly(normalized);
        int limit = normalizeMaxRows(maxRows);
        try {
            dorisAvailability.beforeConnection();
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        try (Connection connection = dorisDataSource.getConnection();
             Statement statement = connection.createStatement()) {
            // Web JDBC URL has no default database; pin to config DB like repository SQL does via qualification.
            if (defaultDatabase != null && !defaultDatabase.isBlank()) {
                connection.setCatalog(defaultDatabase);
            }
            statement.setMaxRows(limit);
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            boolean hasResult = statement.execute(normalized);
            if (!hasResult) {
                return new QueryResult(List.of(), List.of(), 0, normalized, defaultDatabase);
            }
            try (ResultSet rs = statement.getResultSet()) {
                return toResult(rs, limit, normalized, defaultDatabase);
            }
        } catch (SQLException e) {
            throw new IllegalArgumentException("Doris query failed: " + e.getMessage(), e);
        }
    }

    static String normalizeSql(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("sql is required");
        }
        String cleaned = BLOCK_COMMENT.matcher(sql).replaceAll(" ");
        cleaned = LINE_COMMENT.matcher(cleaned).replaceAll(" ");
        cleaned = cleaned.trim();
        if (cleaned.endsWith(";")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("sql is required");
        }
        if (MULTI_STATEMENT.matcher(cleaned).find()) {
            throw new IllegalArgumentException("only a single SQL statement is allowed");
        }
        return cleaned;
    }

    static void validateReadOnly(String sql) {
        String leading = firstKeyword(sql);
        if (!ALLOWED_LEADING.contains(leading)) {
            throw new IllegalArgumentException(
                    "only read-only SQL is allowed (SELECT/SHOW/DESCRIBE/DESC/EXPLAIN/WITH), got: " + leading);
        }
        String withoutLiterals = STRING_LITERAL.matcher(sql).replaceAll("''");
        if (SENSITIVE_LLM_CREDENTIAL_IDENTIFIER.matcher(withoutLiterals).find()) {
            throw new IllegalArgumentException("access to LLM provider credential storage is forbidden");
        }
        if (METADATA_LEADING.contains(leading)) {
            return;
        }
        if (FORBIDDEN_IN_SELECT.matcher(withoutLiterals).find()) {
            throw new IllegalArgumentException("SQL contains forbidden write/admin keywords");
        }
    }

    static int normalizeMaxRows(Integer maxRows) {
        if (maxRows == null || maxRows < 1) {
            return DEFAULT_MAX_ROWS;
        }
        return Math.min(maxRows, HARD_MAX_ROWS);
    }

    private static String firstKeyword(String sql) {
        int i = 0;
        while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < sql.length() && Character.isLetter(sql.charAt(i))) {
            i++;
        }
        if (start == i) {
            return "";
        }
        return sql.substring(start, i).toUpperCase(Locale.ROOT);
    }

    private static QueryResult toResult(ResultSet rs, int limit, String sql, String database) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        List<String> columns = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            String label = meta.getColumnLabel(i);
            columns.add(label == null || label.isBlank() ? meta.getColumnName(i) : label);
        }
        List<List<Object>> rows = new ArrayList<>();
        while (rs.next() && rows.size() < limit) {
            List<Object> row = new ArrayList<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                row.add(rs.getObject(i));
            }
            rows.add(row);
        }
        return new QueryResult(columns, rows, rows.size(), sql, database);
    }

    public record QueryResult(
            List<String> columns, List<List<Object>> rows, int rowCount, String sql, String database) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("database", database);
            map.put("columns", columns);
            map.put("rows", rows);
            map.put("rowCount", rowCount);
            map.put("sql", sql);
            return map;
        }
    }
}
