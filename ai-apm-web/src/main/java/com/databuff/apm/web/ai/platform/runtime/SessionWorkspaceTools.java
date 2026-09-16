package com.databuff.apm.web.ai.platform.runtime;

import com.databuff.apm.web.ai.agent.AgentRuntimeConfig;
import com.databuff.apm.web.ai.agent.ShellCommandPolicy;
import com.databuff.apm.web.ai.platform.task.ExpertMessageConstants;
import com.databuff.apm.web.ai.platform.task.ExpertSessionResolver;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Component
@Lazy
public class SessionWorkspaceTools {

    private static final String DEFAULT_LINE_RANGE = "1-9999";

    private final SessionWorkspaceService workspaceService;
    private final Set<String> allowedShellCommands;
    private final int shellTimeoutSeconds;
    private final int maxReadChunkBytes;
    private final ShellCommandPolicy shellPolicy;
    private final TaskGeneratedFileRegistry generatedFileRegistry;

    @Autowired
    public SessionWorkspaceTools(
            SessionWorkspaceService workspaceService,
            AgentRuntimeConfig agentRuntimeConfig,
            TaskGeneratedFileRegistry generatedFileRegistry) {
        this.workspaceService = workspaceService;
        this.allowedShellCommands = agentRuntimeConfig.workspaceShellCommands();
        this.shellTimeoutSeconds = agentRuntimeConfig.getWorkspaceShellTimeoutSeconds();
        this.maxReadChunkBytes = agentRuntimeConfig.resolvedMcpToolResultMaxBytes();
        this.shellPolicy = agentRuntimeConfig.shellCommandPolicy();
        this.generatedFileRegistry = generatedFileRegistry;
    }

    @Tool(description = "List files in the current chat session workspace (uploads, outputs) or skill resources (relativePath=resources/{skillId}/...)")
    public String listWorkspaceFiles(
            @ToolParam(name = "relativePath", description = "Relative path under session workspace, e.g. uploads, outputs, or resources/skill.summary.html/templates")
            String relativePath,
            RuntimeContext runtimeContext) {
        String sessionId = requireSessionId(runtimeContext);
        try {
            String listingPath = normalizeListingPath(relativePath);
            Path dir = workspaceService.resolveRelativePath(sessionId, listingPath);
            if (!Files.isDirectory(dir)) {
                return "not a directory: " + listingPath;
            }
            try (Stream<Path> stream = Files.list(dir)) {
                List<Path> entries = stream.sorted(Comparator.comparing(Path::getFileName)).toList();
                if (entries.isEmpty()) {
                    return "(empty)";
                }
                StringBuilder builder = new StringBuilder();
                boolean resources = workspaceService.isResourcesPath(listingPath);
                Path sessionRoot = resources ? null : workspaceService.sessionDir(sessionId);
                String resourcesPrefix = resources
                        ? (listingPath.endsWith("/") ? listingPath.substring(0, listingPath.length() - 1) : listingPath)
                        : null;
                for (Path entry : entries) {
                    builder.append(Files.isDirectory(entry) ? "[dir] " : "[file] ");
                    if (resources) {
                        builder.append(resourcesPrefix).append('/').append(entry.getFileName());
                    } else {
                        builder.append(sessionRoot.relativize(entry).toString().replace('\\', '/'));
                    }
                    builder.append('\n');
                }
                return builder.toString().trim();
            }
        } catch (Exception e) {
            return "listWorkspaceFiles failed: " + e.getMessage();
        }
    }

    @Tool(description = "Read a text or image file from the current chat session workspace or skill resources. "
            + "Omit lineRange to read lines 1-9999 by default; if the file has more content, continue with subsequent ranges. "
            + "Do not read the same line range of the same file more than once.")
    public ToolResultBlock readWorkspaceFile(
            @ToolParam(name = "filePath", description = "Relative file path, e.g. uploads/report.csv or resources/skill.summary.html/templates/inspection-report.html")
            String filePath,
            @ToolParam(name = "lineRange", required = false, description = "Optional line range, e.g. 10000-19998; default 1-9999")
            String lineRange,
            RuntimeContext runtimeContext) {
        if (isToolResultPath(filePath)) {
            return ToolResultBlock.text(readWorkspaceFileChunk(filePath, 0L, null, runtimeContext));
        }
        String sessionId = requireSessionId(runtimeContext);
        try {
            Path file = workspaceService.resolveRelativePath(sessionId, filePath);
            if (!Files.isRegularFile(file)) {
                return ToolResultBlock.text("not a file: " + filePath);
            }
            if (WorkspaceImageSupport.isImageFile(file)) {
                return readWorkspaceImage(sessionId, filePath);
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int[] range = parseLineRange(lineRange, lines.size());
            StringBuilder builder = new StringBuilder();
            for (int i = range[0]; i <= range[1]; i++) {
                builder.append(i + 1).append(": ").append(lines.get(i)).append('\n');
            }
            return ToolResultBlock.text(builder.toString().trim());
        } catch (Exception e) {
            return ToolResultBlock.text("readWorkspaceFile failed: " + e.getMessage());
        }
    }

    @Tool(description = "Read a bounded UTF-8 byte chunk from a text file in the current chat session workspace. "
            + "Use the returned nextOffsetBytes to continue only when more content is needed.")
    public String readWorkspaceFileChunk(
            @ToolParam(name = "filePath", description = "Session-relative file path, usually tool-results/...")
            String filePath,
            @ToolParam(name = "offsetBytes", required = false, description = "Byte offset, default 0; use nextOffsetBytes from the previous chunk")
            Long offsetBytes,
            @ToolParam(name = "maxBytes", required = false, description = "Requested chunk size; capped by server configuration")
            Integer maxBytes,
            RuntimeContext runtimeContext) {
        String sessionId = requireSessionId(runtimeContext);
        long offset = offsetBytes == null ? 0L : offsetBytes;
        int requested = maxBytes == null || maxBytes <= 0 ? maxReadChunkBytes : maxBytes;
        int limit = Math.min(Math.max(4, requested), maxReadChunkBytes);
        try {
            Path file = workspaceService.resolveRelativePath(sessionId, filePath);
            if (!Files.isRegularFile(file)) {
                return "not a file: " + filePath;
            }
            long totalBytes = Files.size(file);
            if (offset < 0 || offset > totalBytes) {
                return "readWorkspaceFileChunk failed: offsetBytes must be between 0 and " + totalBytes;
            }
            if (offset == totalBytes) {
                return chunkHeader(filePath, offset, offset, totalBytes, false);
            }
            byte[] bytes;
            try (InputStream input = Files.newInputStream(file)) {
                input.skipNBytes(offset);
                bytes = input.readNBytes(limit);
            }
            int decodedLength = utf8Boundary(bytes);
            if (decodedLength < 0) {
                return "readWorkspaceFileChunk failed: offsetBytes is not on a UTF-8 boundary; "
                        + "use 0 or nextOffsetBytes from the previous chunk";
            }
            String content = StandardCharsets.UTF_8.decode(
                    ByteBuffer.wrap(bytes, 0, decodedLength)).toString();
            long nextOffset = offset + decodedLength;
            return chunkHeader(filePath, offset, nextOffset, totalBytes, nextOffset < totalBytes)
                    + "\n\n"
                    + content;
        } catch (Exception e) {
            return "readWorkspaceFileChunk failed: " + e.getMessage();
        }
    }

    private ToolResultBlock readWorkspaceImage(String sessionId, String filePath) {
        return WorkspaceImageSupport.readImageBlock(workspaceService, sessionId, filePath)
                .map(imageBlock -> ToolResultBlock.of(List.of(
                        TextBlock.builder().text("Image file: " + filePath).build(),
                        imageBlock)))
                .orElse(ToolResultBlock.text("readWorkspaceFile failed: unsupported or unreadable image: " + filePath));
    }

    @Tool(description = "Write a text file to outputs/ in the current session workspace for user download/preview (any text suffix: .html, .md, .csv, .json, ...)")
    public String writeWorkspaceFile(
            @ToolParam(name = "fileName", description = "File name or outputs/ path, e.g. summary.html or report.csv")
            String fileName,
            @ToolParam(name = "content", description = "Text content to write")
            String content,
            @ToolParam(name = "mode", description = "overwrite or append, default overwrite")
            String mode,
            RuntimeContext runtimeContext) {
        String sessionId = requireSessionId(runtimeContext);
        if (content == null) {
            return "content is required";
        }
        try {
            workspaceService.ensureOutputsDir(sessionId);
            String relativePath = workspaceService.resolveOutputWritePath(fileName);
            Path target = workspaceService.resolveRelativePath(sessionId, relativePath);
            Files.createDirectories(target.getParent());
            String writeMode = mode == null || mode.isBlank() ? "overwrite" : mode.trim();
            if ("append".equalsIgnoreCase(writeMode)) {
                Files.writeString(target, content, StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } else {
                Files.writeString(target, content, StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            }
            recordTaskGeneratedFile(sessionId, relativePath, runtimeContext);
            return "Wrote " + relativePath;
        } catch (Exception e) {
            return "writeWorkspaceFile failed: " + e.getMessage();
        }
    }

    @Tool(description = "Execute an allowed shell command in the current chat session workspace")
    public String executeWorkspaceShell(
            @ToolParam(name = "command", description = "Shell command, e.g. head -n 20 uploads/log.txt")
            String command,
            @ToolParam(name = "timeoutSeconds", description = "Optional timeout in seconds")
            Integer timeoutSeconds,
            RuntimeContext runtimeContext) {
        String sessionId = requireSessionId(runtimeContext);
        if (command == null || command.isBlank()) {
            return "command is required";
        }
        String normalized = command.trim();
        String rootCommand = normalized.split("\\s+", 2)[0];
        if (!allowedShellCommands.contains(rootCommand)) {
            return "command is not allowed: " + rootCommand;
        }
        String denied = shellPolicy.check(normalized);
        if (denied != null) {
            return "command is not allowed: " + denied;
        }
        int timeout = timeoutSeconds == null || timeoutSeconds <= 0
                ? shellTimeoutSeconds
                : Math.min(timeoutSeconds, shellTimeoutSeconds);
        try {
            Process process = new ProcessBuilder("/bin/sh", "-lc", normalized)
                    .directory(workspaceService.sessionDir(sessionId).toFile())
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "command timed out after " + timeout + "s";
            }
            String output = readProcessOutput(process);
            if (output.isBlank()) {
                return "exit " + process.exitValue();
            }
            return output;
        } catch (Exception e) {
            return "executeWorkspaceShell failed: " + e.getMessage();
        }
    }

    private static String requireSessionId(RuntimeContext runtimeContext) {
        // Resolve from the per-call RuntimeContext first (carries the chat's sessionId even when
        // multiple chats run concurrently on the shared streamExecutor), then fall back to the
        // global chat scope registry / task context. Without RuntimeContext, concurrent parent
        // brain scopes make ExpertChatScopeRegistry.soleSessionId() ambiguous and tools fail with
        // "session workspace is unavailable outside chat context".
        try {
            return ExpertSessionResolver.resolveSessionIdOrThrow(null, runtimeContext);
        } catch (Exception e) {
            throw new IllegalStateException("session workspace is unavailable outside chat context", e);
        }
    }

    private void recordTaskGeneratedFile(
            String sessionId,
            String relativePath,
            RuntimeContext runtimeContext) {
        if (runtimeContext == null) {
            return;
        }
        String runtimeSessionId = taskScopedSessionId(runtimeContext);
        if (!ExpertChatScopeRegistry.isTaskScopedSessionId(runtimeSessionId)) {
            return;
        }
        int suffix = runtimeSessionId.indexOf(ExpertChatScopeRegistry.TASK_SESSION_SUFFIX);
        String taskId = runtimeSessionId.substring(
                suffix + ExpertChatScopeRegistry.TASK_SESSION_SUFFIX.length()).trim();
        generatedFileRegistry.record(sessionId, taskId, relativePath);
    }

    private static String taskScopedSessionId(RuntimeContext runtimeContext) {
        Object scoped = runtimeContext.get(ExpertMessageConstants.META_RUNTIME_SESSION_ID);
        if (scoped != null && !String.valueOf(scoped).isBlank()) {
            return String.valueOf(scoped).trim();
        }
        String sessionId = runtimeContext.getSessionId();
        return sessionId == null ? null : sessionId.trim();
    }

    private static String normalizeListingPath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return ".";
        }
        return relativePath.trim();
    }

    private static boolean isToolResultPath(String filePath) {
        if (filePath == null) {
            return false;
        }
        String normalized = filePath.trim().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized.startsWith("tool-results/");
    }

    private static int[] parseLineRange(String lineRange, int totalLines) {
        if (totalLines <= 0) {
            return new int[]{0, -1};
        }
        if (lineRange == null || lineRange.isBlank()) {
            lineRange = DEFAULT_LINE_RANGE;
        }
        String[] parts = lineRange.trim().split("-", 2);
        int start = parsePositiveInt(parts[0], 1);
        int end = parts.length > 1 ? parsePositiveInt(parts[1], totalLines) : start;
        start = Math.max(1, Math.min(start, totalLines));
        end = Math.max(start, Math.min(end, totalLines));
        return new int[]{start - 1, end - 1};
    }

    private static int parsePositiveInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String readProcessOutput(Process process) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return String.join("\n", lines).trim();
    }

    private static String chunkHeader(
            String filePath,
            long offset,
            long nextOffset,
            long totalBytes,
            boolean hasMore) {
        return "workspace_file=" + filePath
                + "\noffsetBytes=" + offset
                + "\nnextOffsetBytes=" + nextOffset
                + "\ntotalBytes=" + totalBytes
                + "\nhasMore=" + hasMore;
    }

    private static int utf8Boundary(byte[] bytes) {
        for (int length = bytes.length; length >= Math.max(0, bytes.length - 3); length--) {
            try {
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes, 0, length));
                return length;
            } catch (CharacterCodingException ignored) {
                // The byte limit can split one multibyte character; trim only that partial suffix.
            }
        }
        return -1;
    }
}
