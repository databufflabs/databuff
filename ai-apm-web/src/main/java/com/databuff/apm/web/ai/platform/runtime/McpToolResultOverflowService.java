package com.databuff.apm.web.ai.platform.runtime;

import com.databuff.apm.web.ai.agent.AgentRuntimeConfig;
import com.databuff.apm.web.ai.platform.task.ExpertSessionResolver;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class McpToolResultOverflowService {

    static final String TRUNCATED_MARKER = "[MCP_TOOL_RESULT_TRUNCATED]";

    private static final Logger log = LoggerFactory.getLogger(McpToolResultOverflowService.class);

    private final SessionWorkspaceService workspaceService;
    private final int maxBytes;

    public McpToolResultOverflowService(
            SessionWorkspaceService workspaceService,
            AgentRuntimeConfig agentRuntimeConfig) {
        this.workspaceService = workspaceService;
        this.maxBytes = agentRuntimeConfig.resolvedMcpToolResultMaxBytes();
    }

    public ToolResultBlock limit(
            Toolkit toolkit,
            ToolCallParam param,
            ToolResultBlock result) {
        if (!isMcpTextResult(toolkit, param, result)) {
            return result;
        }
        String content = joinText(result.getOutput());
        int originalBytes = utf8Length(content);
        if (originalBytes <= maxBytes) {
            return result;
        }

        String toolName = param.getToolUseBlock().getName();
        String toolCallId = param.getToolUseBlock().getId();
        String workspaceFile = null;
        try {
            String sessionId = ExpertSessionResolver.sessionIdFromRuntimeContext(param.getRuntimeContext())
                    .orElseThrow(() -> new IllegalStateException("session id unavailable"));
            workspaceFile = workspaceService.saveToolResult(
                    sessionId, toolName, toolCallId, content);
        } catch (Exception e) {
            log.warn(
                    "Failed to save oversized MCP result (tool={}, bytes={}): {}",
                    toolName,
                    originalBytes,
                    e.getMessage());
        }

        String header = overflowHeader(toolName, originalBytes, workspaceFile);
        int previewBudget = Math.max(0, maxBytes - utf8Length(header));
        String preview = truncateUtf8(content, previewBudget);
        String modelContent = truncateUtf8(header + preview, maxBytes);

        Map<String, Object> metadata = new LinkedHashMap<>(result.getMetadata());
        metadata.put("truncated", true);
        metadata.put("originalUtf8Bytes", originalBytes);
        if (workspaceFile != null) {
            metadata.put("workspaceFile", workspaceFile);
        }
        return new ToolResultBlock(
                result.getId(),
                result.getName(),
                List.of(TextBlock.builder().text(modelContent).build()),
                metadata,
                result.getState());
    }

    private static boolean isMcpTextResult(
            Toolkit toolkit,
            ToolCallParam param,
            ToolResultBlock result) {
        if (toolkit == null || param == null || param.getToolUseBlock() == null || result == null) {
            return false;
        }
        if (!(toolkit.getTool(param.getToolUseBlock().getName()) instanceof McpTool)) {
            return false;
        }
        return !result.getOutput().isEmpty()
                && result.getOutput().stream().allMatch(TextBlock.class::isInstance);
    }

    private static String joinText(List<ContentBlock> blocks) {
        return blocks.stream()
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .collect(Collectors.joining("\n"));
    }

    private static String overflowHeader(
            String toolName,
            int originalBytes,
            String workspaceFile) {
        StringBuilder header = new StringBuilder()
                .append(TRUNCATED_MARKER).append('\n')
                .append("tool=").append(truncateUtf8(toolName, 120)).append('\n')
                .append("originalUtf8Bytes=").append(originalBytes).append('\n');
        if (workspaceFile == null) {
            header.append("workspace_file=unavailable\n")
                    .append("The result preview follows; the complete result could not be saved.\n\n");
        } else {
            header.append("workspace_file=").append(workspaceFile).append('\n')
                    .append("The complete result is saved there. Decide whether more is needed. ")
                    .append("If needed, call readWorkspaceFileChunk with this filePath and offsetBytes=0, ")
                    .append("then continue with nextOffsetBytes.\n")
                    .append("Preview:\n");
        }
        return header.toString();
    }

    static String truncateUtf8(String value, int maxBytes) {
        if (value == null || value.isEmpty() || maxBytes <= 0) {
            return "";
        }
        int used = 0;
        int end = 0;
        while (end < value.length()) {
            int codePoint = value.codePointAt(end);
            int bytes = utf8Bytes(codePoint);
            if (used + bytes > maxBytes) {
                break;
            }
            used += bytes;
            end += Character.charCount(codePoint);
        }
        return value.substring(0, end);
    }

    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static int utf8Bytes(int codePoint) {
        if (codePoint <= 0x7F) {
            return 1;
        }
        if (codePoint <= 0x7FF) {
            return 2;
        }
        if (codePoint <= 0xFFFF) {
            return 3;
        }
        return 4;
    }
}
