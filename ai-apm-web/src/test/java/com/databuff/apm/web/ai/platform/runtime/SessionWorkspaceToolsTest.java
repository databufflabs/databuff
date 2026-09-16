package com.databuff.apm.web.ai.platform.runtime;

import com.databuff.apm.web.ai.agent.AgentRuntimeConfig;
import com.databuff.apm.web.ai.platform.task.ExpertMessageConstants;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class SessionWorkspaceToolsTest {

    private static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @TempDir
    Path tempDir;

    private SessionWorkspaceService workspaceService;
    private SessionWorkspaceTools tools;
    private TaskGeneratedFileRegistry generatedFileRegistry;
    private String sessionId;

    @BeforeEach
    void setUp() throws Exception {
        AgentRuntimeConfig config = new AgentRuntimeConfig();
        config.setWorkspaceDir(tempDir.toString());
        workspaceService = new SessionWorkspaceService(config);
        generatedFileRegistry = new TaskGeneratedFileRegistry();
        tools = new SessionWorkspaceTools(workspaceService, config, generatedFileRegistry);
        sessionId = "session-test-tools-001";
        Files.createDirectories(workspaceService.uploadsDir(sessionId));
        ExpertChatScopeRegistry.register(new ExpertChatContext.State(
                sessionId, "admin", "inspection", null, false, null));
    }

    @AfterEach
    void tearDown() {
        ExpertChatScopeRegistry.clearForTests();
    }

    @Test
    void readWorkspaceFileReturnsImageBlockForPng() throws Exception {
        Files.write(workspaceService.uploadsDir(sessionId).resolve("chart.png"), TINY_PNG);

        ToolResultBlock result = tools.readWorkspaceFile(
                "uploads/chart.png", null, RuntimeContext.builder().sessionId(sessionId).build());

        assertThat(result.getOutput()).hasSize(2);
        assertThat(result.getOutput().get(0)).isInstanceOf(TextBlock.class);
        assertThat(((TextBlock) result.getOutput().get(0)).getText()).contains("uploads/chart.png");
        assertThat(result.getOutput().get(1)).isInstanceOf(ImageBlock.class);
    }

    @Test
    void readWorkspaceFileReturnsTextForCsv() throws Exception {
        Files.writeString(workspaceService.uploadsDir(sessionId).resolve("metrics.csv"), "a,b\n1,2");

        ToolResultBlock result = tools.readWorkspaceFile(
                "uploads/metrics.csv", null, RuntimeContext.builder().sessionId(sessionId).build());

        assertThat(result.getOutput()).hasSize(1);
        assertThat(result.getOutput().get(0)).isInstanceOf(TextBlock.class);
        assertThat(((TextBlock) result.getOutput().get(0)).getText()).contains("1: a,b");
    }

    @Test
    void readWorkspaceFileChunkReturnsBoundedUtf8ContentAndContinuationOffset() throws Exception {
        String content = "ab中cd文efgh";
        Path file = workspaceService.sessionDir(sessionId).resolve("tool-results/result.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);

        RuntimeContext runtimeContext = RuntimeContext.builder().sessionId(sessionId).build();
        String first = tools.readWorkspaceFileChunk(
                "tool-results/result.txt", 0L, 6, runtimeContext);
        String second = tools.readWorkspaceFileChunk(
                "tool-results/result.txt", 6L, 6, runtimeContext);

        assertThat(first)
                .contains("nextOffsetBytes=6")
                .contains("hasMore=true")
                .endsWith("ab中c");
        assertThat(second)
                .contains("offsetBytes=6")
                .contains("nextOffsetBytes=12")
                .endsWith("d文ef");
    }

    @Test
    void ordinaryReadAlsoBoundsInternalToolResultFiles() throws Exception {
        Path file = workspaceService.sessionDir(sessionId).resolve("tool-results/large.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x".repeat(140_000));

        ToolResultBlock result = tools.readWorkspaceFile(
                "tool-results/large.txt", null, RuntimeContext.builder().sessionId(sessionId).build());

        String text = ((TextBlock) result.getOutput().get(0)).getText();
        assertThat(text)
                .contains("hasMore=true")
                .contains("nextOffsetBytes=131072");
        assertThat(text.length()).isLessThan(132_000);
    }

    @Test
    void readWorkspaceFileResolvesSessionIdFromRuntimeContextWhenScopesAreAmbiguous() throws Exception {
        // Simulate a second concurrent chat: register another parent brain scope so the global
        // registry's soleSessionId() becomes ambiguous. Without RuntimeContext this used to throw
        // "session workspace is unavailable outside chat context".
        String otherSession = "session-test-tools-other";
        Files.createDirectories(workspaceService.uploadsDir(otherSession));
        ExpertChatScopeRegistry.register(new ExpertChatContext.State(
                otherSession, "admin", "inspection", null, false, null));
        Files.writeString(workspaceService.uploadsDir(sessionId).resolve("metrics.csv"), "a,b\n1,2");

        RuntimeContext runtimeContext = RuntimeContext.builder().sessionId(sessionId).build();
        ToolResultBlock result = tools.readWorkspaceFile("uploads/metrics.csv", null, runtimeContext);

        assertThat(result.getOutput()).hasSize(1);
        assertThat(result.getOutput().get(0)).isInstanceOf(TextBlock.class);
        assertThat(((TextBlock) result.getOutput().get(0)).getText()).contains("1: a,b");
    }

    @Test
    void writeWorkspaceFileRecordsOwningTask() {
        String taskId = "task-report-1";
        String runtimeSessionId = ExpertChatScopeRegistry.taskScopedSessionId(sessionId, taskId);
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .sessionId(runtimeSessionId)
                .build();

        String result = tools.writeWorkspaceFile(
                "inspection.html", "<html>report</html>", "overwrite", runtimeContext);

        assertThat(result).isEqualTo("Wrote outputs/inspection.html");
        assertThat(generatedFileRegistry.paths(sessionId, taskId))
                .containsExactly("outputs/inspection.html");
    }

    @Test
    void brainContinuationWriteIsNotAttributedToCompletedTask() {
        String completedTaskId = "task-completed";
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .sessionId(sessionId)
                .put(ExpertMessageConstants.META_TASK_ID, completedTaskId)
                .build();

        String result = tools.writeWorkspaceFile(
                "brain-summary.html", "<html>summary</html>", "overwrite", runtimeContext);

        assertThat(result).isEqualTo("Wrote outputs/brain-summary.html");
        assertThat(generatedFileRegistry.paths(sessionId, completedTaskId)).isEmpty();
    }
}
