package com.databuff.apm.web.ai.platform.runtime;

import com.databuff.apm.web.ai.agent.AgentRuntimeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class SessionWorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(SessionWorkspaceService.class);
    private static final Pattern SAFE_SESSION_ID = Pattern.compile("^[a-zA-Z0-9_-]{8,128}$");
    private static final Pattern UNSAFE_FILENAME = Pattern.compile("[^a-zA-Z0-9._-]");
    private static final Pattern SAFE_SKILL_ID = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$");
    public static final String OUTPUTS_PREFIX = "outputs/";
    public static final String UPLOADS_PREFIX = "uploads/";
    /** Read-only mount of skill packages: resources/{skillId}/... */
    public static final String RESOURCES_PREFIX = "resources/";

    private final Path workspaceRoot;
    private final AgentRuntimeConfig agentRuntimeConfig;

    public SessionWorkspaceService(AgentRuntimeConfig agentRuntimeConfig) {
        this.agentRuntimeConfig = agentRuntimeConfig;
        this.workspaceRoot = agentRuntimeConfig.workspaceDirectory();
    }

    public Path sessionDir(String sessionId) {
        validateSessionId(sessionId);
        return workspaceRoot.resolve(sessionId).normalize();
    }

    public Path uploadsDir(String sessionId) {
        return sessionDir(sessionId).resolve("uploads").normalize();
    }

    public Path outputsDir(String sessionId) {
        return sessionDir(sessionId).resolve("outputs").normalize();
    }

    public void ensureOutputsDir(String sessionId) throws IOException {
        Files.createDirectories(outputsDir(sessionId));
    }

    /** Saves a complete oversized tool result and returns its session-relative path. */
    public String saveToolResult(
            String sessionId,
            String toolName,
            String toolCallId,
            String content) throws IOException {
        if (content == null) {
            throw new IllegalArgumentException("content is required");
        }
        Path toolResultsDir = sessionDir(sessionId).resolve("tool-results").normalize();
        Files.createDirectories(toolResultsDir);
        String safeToolName = shortenFilename(sanitizeFilename(toolName, "tool"), 80);
        String safeCallId = shortenFilename(sanitizeFilename(toolCallId, "call"), 80);
        String filename = safeToolName + "-" + safeCallId + ".txt";
        Path target = toolResultsDir.resolve(filename).normalize();
        if (!target.startsWith(toolResultsDir)) {
            throw new IllegalArgumentException("invalid tool result target");
        }
        Files.writeString(
                target,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        return sessionDir(sessionId).relativize(target).toString().replace('\\', '/');
    }

    public String resolveOutputWritePath(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName is required");
        }
        String normalized = normalizeRelativePath(fileName);
        if (normalized.startsWith(UPLOADS_PREFIX)) {
            throw new IllegalArgumentException("cannot write into uploads/: " + fileName);
        }
        if (normalized.startsWith(RESOURCES_PREFIX)) {
            throw new IllegalArgumentException("cannot write into resources/: " + fileName);
        }
        if (!normalized.startsWith(OUTPUTS_PREFIX)) {
            normalized = OUTPUTS_PREFIX + sanitizeFilename(normalized, "file");
        }
        return normalized;
    }

    public boolean isDownloadablePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return false;
        }
        String normalized = normalizeRelativePath(relativePath);
        return normalized.startsWith(UPLOADS_PREFIX) || normalized.startsWith(OUTPUTS_PREFIX);
    }

    public Set<String> snapshotOutputPaths(String sessionId) {
        return new HashSet<>(listOutputFiles(sessionId).stream()
                .map(WorkspaceFileInfo::relativePath)
                .toList());
    }

    public List<WorkspaceFileInfo> listOutputFiles(String sessionId) {
        validateSessionId(sessionId);
        Path outputs = outputsDir(sessionId);
        if (!Files.isDirectory(outputs)) {
            return List.of();
        }
        Path sessionBase = sessionDir(sessionId);
        List<WorkspaceFileInfo> files = new ArrayList<>();
        try {
            Files.walkFileTree(outputs, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path relative = sessionBase.relativize(file.normalize());
                    files.add(toWorkspaceFileInfo(relative, file));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Failed to list output files for session {}: {}", sessionId, e.getMessage());
        }
        return List.copyOf(files);
    }

    public List<WorkspaceFileInfo> listNewOutputFiles(String sessionId, Set<String> beforePaths) {
        Set<String> baseline = beforePaths == null ? Set.of() : beforePaths;
        return listOutputFiles(sessionId).stream()
                .filter(file -> !baseline.contains(file.relativePath()))
                .toList();
    }

    public Path resolveDownloadPath(String sessionId, String relativePath) {
        if (!isDownloadablePath(relativePath)) {
            throw new IllegalArgumentException("path is not downloadable: " + relativePath);
        }
        Path resolved = resolveRelativePath(sessionId, relativePath);
        if (!Files.isRegularFile(resolved)) {
            throw new IllegalArgumentException("file not found: " + relativePath);
        }
        return resolved;
    }

    private WorkspaceFileInfo toWorkspaceFileInfo(Path relative, Path file) {
        String relativePath = relative.toString().replace('\\', '/');
        String name = relative.getFileName() == null ? relativePath : relative.getFileName().toString();
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            size = 0L;
        }
        return new WorkspaceFileInfo(name, relativePath, probeMimeType(name), size);
    }

    private static String probeMimeType(String fileName) {
        try {
            String probed = Files.probeContentType(Path.of(fileName));
            if (probed != null && !probed.isBlank()) {
                return probed;
            }
        } catch (IOException ignored) {
            // fall through
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "text/html";
        }
        if (lower.endsWith(".csv")) {
            return "text/csv";
        }
        if (lower.endsWith(".json")) {
            return "application/json";
        }
        if (lower.endsWith(".md")) {
            return "text/markdown";
        }
        if (lower.endsWith(".txt") || lower.endsWith(".log")) {
            return "text/plain";
        }
        return "application/octet-stream";
    }

    public Path resolveRelativePath(String sessionId, String relativePath) {
        String normalized = normalizeRelativePath(relativePath);
        if (normalized.startsWith(RESOURCES_PREFIX)) {
            return resolveSkillResourcePath(normalized);
        }
        validateSessionId(sessionId);
        Path base = sessionDir(sessionId);
        Path resolved = base.resolve(normalized).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("path escapes session workspace: " + relativePath);
        }
        return resolved;
    }

    /**
     * Maps {@code resources/{skillId}/...} onto a skill package under custom or built-in skills dirs.
     * Read-only for agents; not a writable session path.
     */
    public Path resolveSkillResourcePath(String normalizedResourcesPath) {
        String remainder = normalizedResourcesPath.substring(RESOURCES_PREFIX.length());
        if (remainder.isBlank() || remainder.contains("..")) {
            throw new IllegalArgumentException("invalid skill resource path: " + normalizedResourcesPath);
        }
        int slash = remainder.indexOf('/');
        String skillId = slash < 0 ? remainder : remainder.substring(0, slash);
        String subPath = slash < 0 ? "" : remainder.substring(slash + 1);
        if (!SAFE_SKILL_ID.matcher(skillId).matches()) {
            throw new IllegalArgumentException("invalid skill id in resource path: " + skillId);
        }
        Path skillRoot = findSkillPackageDir(skillId);
        if (skillRoot == null) {
            throw new IllegalArgumentException("skill package not found: " + skillId);
        }
        Path resolved = subPath.isBlank()
                ? skillRoot
                : skillRoot.resolve(subPath).normalize();
        if (!resolved.startsWith(skillRoot)) {
            throw new IllegalArgumentException("path escapes skill package: " + normalizedResourcesPath);
        }
        return resolved;
    }

    private Path findSkillPackageDir(String skillId) {
        for (Path root : agentRuntimeConfig.skillSearchDirectories()) {
            Path candidate = root.resolve(skillId).normalize();
            if (Files.isDirectory(candidate) && Files.isRegularFile(candidate.resolve("SKILL.md"))) {
                return candidate;
            }
        }
        return null;
    }

    public boolean isResourcesPath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return false;
        }
        return normalizeRelativePath(relativePath).startsWith(RESOURCES_PREFIX);
    }

    public List<SavedAttachment> saveAttachments(String sessionId, Map<String, Object> context) {
        if (sessionId == null || sessionId.isBlank() || context == null || context.isEmpty()) {
            return List.of();
        }
        Object raw = context.get("attachments");
        if (!(raw instanceof List<?> items) || items.isEmpty()) {
            return List.of();
        }
        try {
            Path uploads = uploadsDir(sessionId);
            Files.createDirectories(uploads);
            List<SavedAttachment> saved = new ArrayList<>();
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                SavedAttachment attachment = saveOneAttachment(uploads, map);
                if (attachment != null) {
                    saved.add(attachment);
                }
            }
            return List.copyOf(saved);
        } catch (IOException e) {
            log.warn("Failed to save attachments for session {}: {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    public Map<String, Object> buildPersistedContext(
            Map<String, Object> original,
            List<SavedAttachment> saved) {
        Map<String, Object> merged = new LinkedHashMap<>(original == null ? Map.of() : original);
        if (saved.isEmpty()) {
            return Map.copyOf(merged);
        }
        merged.put("attachments", saved.stream().map(SavedAttachment::toMetadata).toList());
        return Map.copyOf(merged);
    }

    /**
     * Builds model-facing prompt text with workspace attachment hints.
     * Callers must persist the original user message separately for UI display.
     */
    public String enrichMessage(String message, List<SavedAttachment> saved) {
        if (saved.isEmpty()) {
            return message == null ? "" : message;
        }
        StringBuilder builder = new StringBuilder(message == null ? "" : message.trim());
        builder.append("\n\n[Session workspace attachments]\n");
        for (SavedAttachment attachment : saved) {
            builder.append("- ")
                    .append(attachment.relativePath())
                    .append(" (")
                    .append(attachment.name())
                    .append(")\n");
        }
        boolean hasImages = saved.stream().anyMatch(this::isImageAttachment);
        if (hasImages) {
            builder.append("""
                    Image attachments are included in this message for visual analysis.
                    For text files, use workspace tools to inspect them:
                    - listWorkspaceFiles: list uploaded files (relativePath=uploads)
                    - readWorkspaceFile: read a text or image file (default lines 1-9999; continue with lineRange if more content remains)
                    - writeWorkspaceFile: write deliverables to outputs/ for user download
                    - executeWorkspaceShell: run allowed shell commands in the session workspace
                    """);
        } else {
            builder.append("""
                    Use workspace tools to inspect them:
                    - listWorkspaceFiles: list uploaded files (relativePath=uploads)
                    - readWorkspaceFile: read a text or image file (default lines 1-9999; continue with lineRange if more content remains)
                    - writeWorkspaceFile: write deliverables to outputs/ for user download
                    - executeWorkspaceShell: run allowed shell commands in the session workspace
                    """);
        }
        return builder.toString().trim();
    }

    private boolean isImageAttachment(SavedAttachment attachment) {
        if (attachment == null) {
            return false;
        }
        if ("image".equalsIgnoreCase(attachment.type())) {
            return true;
        }
        String mimeType = attachment.mimeType();
        return mimeType != null && mimeType.toLowerCase(Locale.ROOT).startsWith("image/");
    }

    private SavedAttachment saveOneAttachment(Path uploadsDir, Map<?, ?> map) throws IOException {
        String name = stringValue(map.get("name"));
        String type = stringValue(map.get("type"));
        String mimeType = stringValue(map.get("mimeType"));
        String dataUrl = stringValue(map.get("dataUrl"));
        if (dataUrl == null || dataUrl.isBlank()) {
            return null;
        }
        byte[] bytes = decodeDataUrl(dataUrl);
        if (bytes.length == 0) {
            return null;
        }
        String safeName = uniqueFilename(uploadsDir, sanitizeFilename(name, type));
        Path target = uploadsDir.resolve(safeName).normalize();
        if (!target.startsWith(uploadsDir)) {
            throw new IllegalArgumentException("invalid attachment target: " + safeName);
        }
        Files.write(target, bytes);
        String relativePath = "uploads/" + safeName;
        long size = map.get("size") instanceof Number number ? number.longValue() : bytes.length;
        return new SavedAttachment(
                safeName,
                type == null || type.isBlank() ? "file" : type,
                mimeType,
                size,
                relativePath);
    }

    private static byte[] decodeDataUrl(String dataUrl) {
        int comma = dataUrl.indexOf(',');
        if (comma < 0) {
            return Base64.getDecoder().decode(dataUrl.trim());
        }
        return Base64.getDecoder().decode(dataUrl.substring(comma + 1).trim());
    }

    private static String sanitizeFilename(String name, String type) {
        String candidate = name == null ? "" : name.trim();
        if (candidate.isBlank()) {
            candidate = "image".equalsIgnoreCase(type) ? "image.png" : "attachment.bin";
        }
        candidate = candidate.replace('\\', '/');
        int slash = candidate.lastIndexOf('/');
        if (slash >= 0) {
            candidate = candidate.substring(slash + 1);
        }
        candidate = UNSAFE_FILENAME.matcher(candidate).replaceAll("_");
        if (candidate.isBlank()) {
            candidate = "attachment.bin";
        }
        return candidate;
    }

    private static String uniqueFilename(Path uploadsDir, String filename) {
        Path candidate = uploadsDir.resolve(filename);
        if (!Files.exists(candidate)) {
            return filename;
        }
        int dot = filename.lastIndexOf('.');
        String base = dot > 0 ? filename.substring(0, dot) : filename;
        String ext = dot > 0 ? filename.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            String next = base + "-" + i + ext;
            if (!Files.exists(uploadsDir.resolve(next))) {
                return next;
            }
        }
        return base + "-" + System.currentTimeMillis() + ext;
    }

    private static String shortenFilename(String filename, int maxLength) {
        return filename.length() <= maxLength ? filename : filename.substring(0, maxLength);
    }

    private static String normalizeRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return ".";
        }
        String normalized = relativePath.trim().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized.isBlank() ? "." : normalized;
    }

    private static void validateSessionId(String sessionId) {
        if (sessionId == null || !SAFE_SESSION_ID.matcher(sessionId).matches()) {
            throw new IllegalArgumentException("invalid session id");
        }
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public record SavedAttachment(
            String name,
            String type,
            String mimeType,
            long size,
            String relativePath) {

        Map<String, Object> toMetadata() {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("type", type);
            metadata.put("name", name);
            if (mimeType != null && !mimeType.isBlank()) {
                metadata.put("mimeType", mimeType);
            }
            metadata.put("size", size);
            metadata.put("filePath", relativePath);
            return Map.copyOf(metadata);
        }
    }

    public record WorkspaceFileInfo(
            String name,
            String relativePath,
            String mimeType,
            long size) {

        public Map<String, Object> toMetadata() {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("type", "file");
            metadata.put("name", name);
            metadata.put("filePath", relativePath);
            if (mimeType != null && !mimeType.isBlank()) {
                metadata.put("mimeType", mimeType);
            }
            metadata.put("size", size);
            return Map.copyOf(metadata);
        }
    }
}
