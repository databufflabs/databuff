package com.databuff.apm.web.ai.mcp.standard;

import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.web.ai.TestBeanSupport;
import com.databuff.apm.web.tools.local.DataTools;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class McpMetricContractIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ApmReadRepository repository = mock(ApmReadRepository.class);
    private final DataTools dataTools = TestBeanSupport.dataTools(null, null, null, repository, mapper);
    private final McpJsonRpcService service = new McpJsonRpcService(new McpToolCatalog(),
            new JavaBeanToolExecutor(null, dataTools, null, null, null, null, mapper));

    @Test
    void skillExamplesExecuteThroughMcpUsingRealArgumentConversionAndSqlBuilder() throws Exception {
        when(repository.queryRows(anyString(), eq(200))).thenReturn(List.of());
        for (String id : List.of("skill.data.metrics", "skill.inspection.health")) {
            String skill = Files.readString(Path.of("../deploy/common/skills", id, "SKILL.md"));
            var example = Pattern.compile("```json\\s*(.*?)```", Pattern.DOTALL).matcher(skill);
            assertThat(example.find()).isTrue();
            Map<String, Object> arguments = mapper.readValue(example.group(1), new TypeReference<>() {});
            Map<?, ?> result = call(arguments);
            assertThat(result.get("isError")).isEqualTo(false);
        }
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(repository, times(2)).queryRows(sql.capture(), eq(200));
        assertThat(sql.getAllValues()).allSatisfy(query -> assertThat(query)
                .contains("`metric_service`", "SUM(`cnt`)", "SUM(`error`)", "SUM(`sumDuration`)", "`service` = 'service-a'", "LIMIT 200"));
    }

    @Test
    void missingTimeFailsBeforeDatabaseAndMissingTableIsMcpFailure() throws Exception {
        assertThat(call(Map.of("queryRequests", List.of(Map.of("measurement", "reqCount")))).get("isError")).isEqualTo(true);
        verifyNoInteractions(repository);
        when(repository.queryRows(anyString(), eq(200))).thenThrow(new IllegalArgumentException("Table [reqCount] does not exist"));
        Map<?, ?> result = call(Map.of("queryRequests", List.of(Map.of("measurement", "reqCount",
                "start", "2026-09-07 16:00:00", "end", "2026-09-07 17:00:00"))));
        assertThat(result.get("isError")).isEqualTo(true);
        assertThat(result.get("content").toString()).contains("Table [reqCount] does not exist");
    }

    private Map<?, ?> call(Map<String, Object> arguments) {
        return (Map<?, ?>) service.handle(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", Map.of("name", "queryMetricData", "arguments", arguments))).get("result");
    }
}
