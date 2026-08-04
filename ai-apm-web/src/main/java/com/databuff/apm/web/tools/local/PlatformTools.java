package com.databuff.apm.web.tools.local;

import com.databuff.apm.web.storage.DorisReadQueryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Platform-facing tools for digital experts (Doris read queries, etc.).
 */
@Component
@Lazy
public class PlatformTools {

    private final DorisReadQueryService dorisReadQueryService;
    private final ObjectMapper objectMapper;

    public PlatformTools(DorisReadQueryService dorisReadQueryService, ObjectMapper objectMapper) {
        this.dorisReadQueryService = dorisReadQueryService;
        this.objectMapper = objectMapper;
    }

    @Tool(converter = PlainTextToolResultConverter.class, description = """
            Execute a read-only SQL query against DataBuff Doris (config, metrics, trace, log, alarm, and other databuff tables).
            Only SELECT, SHOW, DESCRIBE/DESC, EXPLAIN, WITH are allowed. Prefer this over inventing JDBC/mysql/FE-HTTP
            connections. maxRows defaults to 200 (hard cap 1000).
            """)
    public String queryDoris(
            @ToolParam(name = "sql", description = "Read-only SQL, single statement")
            String sql,
            @ToolParam(name = "maxRows", required = false, description = "Max rows to return, default 200, max 1000")
            Integer maxRows) {
        try {
            return objectMapper.writeValueAsString(dorisReadQueryService.query(sql, maxRows).toMap());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize Doris query result", e);
        }
    }
}
