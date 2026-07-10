package com.databuff.apm.web.ai.platform.api;

import com.databuff.apm.web.ai.platform.expert.AiExpertDefinition;
import com.databuff.apm.web.ai.TestAiSupport;
import com.databuff.apm.web.ai.TestBeanSupport;
import com.databuff.apm.web.ai.platform.AiPlatformApiException;
import com.databuff.apm.web.ai.platform.AiPlatformExceptionHandler;
import com.databuff.apm.web.ai.platform.expert.ExpertType;
import com.databuff.apm.web.ai.platform.runtime.SkillFileSyncService;
import com.databuff.apm.web.ai.platform.skill.SkillPackageService;
import com.databuff.apm.web.ai.platform.tool.AiToolDefinition;
import com.databuff.apm.web.ai.platform.tool.ToolType;
import com.databuff.apm.web.ai.tool.ApmToolkit;
import com.databuff.apm.web.tools.local.DataTools;
import com.databuff.apm.web.tools.local.TimeTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiPlatformControllerTest {

    @TempDir
    java.nio.file.Path tempDir;

    private TestAiSupport.PlatformRuntimeFixture fixture;
    private AiToolController toolController;
    private AiSkillController skillController;
    private AiExpertController expertController;
    private AiPlatformExceptionHandler exceptionHandler;

    @BeforeEach
    void setUp() {
        ApmToolkit toolkit = mock(ApmToolkit.class);
        when(toolkit.countRecentSpans(anyLong())).thenReturn(42);
        fixture = TestAiSupport.aiFixture().buildPlatformRuntime(toolkit);
        TestAiSupport.aiFixture().agentRuntimeConfig().setCustomSkillsDir(tempDir.toString());
        SkillFileSyncService skillFileSyncService = new SkillFileSyncService(
                TestAiSupport.aiFixture().agentRuntimeConfig(),
                fixture.skillManagementService(),
                new DefaultResourceLoader());
        SkillPackageService skillPackageService = new SkillPackageService(
                TestAiSupport.aiFixture().agentRuntimeConfig(),
                fixture.skillManagementService(),
                skillFileSyncService);
        toolController = TestBeanSupport.aiToolController(
                fixture.toolManagementService(),
                fixture.expertManagementService(),
                mock(DataTools.class),
                new TimeTool(),
                new ObjectMapper());
        skillController = new AiSkillController(
                fixture.skillManagementService(),
                fixture.expertManagementService(),
                skillFileSyncService,
                skillPackageService);
        expertController = new AiExpertController(
                fixture.expertManagementService(),
                fixture.expertRuntimeRegistry());
        exceptionHandler = new AiPlatformExceptionHandler();
    }

    @Test
    void listsBuiltInTools() {
        assertThat(toolController.list()).extracting(AiToolDefinition::toolId)
                .contains("data.queryServicesAll", "data.queryServicesByServiceType", "time.getCurrentTimeRange");
    }

    @Test
    void createsAllowlistedTool() {
        AiToolDefinition created = toolController.create(new AiToolController.SaveToolRequest(
                "custom.tool", "Custom", null, "desc", ToolType.JAVA_BEAN,
                "timeTool.getCurrentTimeRange", "{}", "{}", true));

        assertThat(created.toolId()).isEqualTo("custom.tool");
        assertThat(toolController.get("custom.tool").enabled()).isTrue();
    }

    @Test
    void rejectsNonAllowlistedToolImplementation() {
        assertThatThrownBy(() -> toolController.create(new AiToolController.SaveToolRequest(
                "bad.tool", "Bad", null, "desc", ToolType.JAVA_BEAN,
                "evil.run", "{}", "{}", true)))
                .isInstanceOf(AiPlatformApiException.class);
    }

    @Test
    void testsToolOutput() {
        Map<String, Object> result = toolController.test("time.getCurrentTimeRange", null);
        assertThat(result).containsEntry("ok", true);
        assertThat(String.valueOf(result.get("output"))).contains("fromTime", "toTime");
    }

    @Test
    void blocksDeletingReferencedTool() {
        ResponseEntity<Map<String, Object>> response = exceptionHandler.handleApiException(
                catchApi(() -> toolController.delete("data.queryServicesAll")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("error", "tool_in_use");
    }

    @Test
    void rejectsInvalidSkillId() {
        assertThatThrownBy(() -> skillController.create(new AiSkillController.SaveSkillRequest(
                "INVALID", "Name", null, "desc", "classpath:/ai/skills/x/SKILL.md", null, true)))
                .isInstanceOf(AiPlatformApiException.class);
    }

    @Test
    void createsCustomExpert() {
        var created = expertController.create(new AiExpertController.SaveExpertRequest(
                "custom-ops", "Custom Ops", null, "ops expert", ExpertType.CUSTOM,
                null, null, "You are ops.",
                List.of("data.queryServicesAll"),
                List.of("skill.data.metrics"),
                null, true));

        assertThat(created.expertId()).isEqualTo("custom-ops");
        assertThat(expertController.list()).extracting(AiExpertDefinition::expertId)
                .contains("custom-ops");
    }

    @Test
    void reloadsExpertRuntime() {
        Map<String, Object> result = expertController.reload("data");
        assertThat(result).containsEntry("reloaded", true);
    }

    @Test
    void listsToolReferences() {
        Map<String, Object> refs = toolController.references("data.queryServicesAll");
        assertThat(refs).containsEntry("toolId", "data.queryServicesAll");
        assertThat((List<?>) refs.get("expertIds")).isNotEmpty();
    }

    @Test
    void loadsSkillContentPreview() {
        Map<String, Object> content = skillController.content("skill.data.metrics");
        assertThat(content).containsKey("markdown");
        assertThat(String.valueOf(content.get("markdown"))).isNotBlank();
    }

    @Test
    void listsSkillReferences() {
        Map<String, Object> refs = skillController.references("skill.data.metrics");
        assertThat(refs).containsEntry("skillId", "skill.data.metrics");
        @SuppressWarnings("unchecked")
        List<String> expertIds = (List<String>) refs.get("expertIds");
        assertThat(expertIds).contains("data");
    }

    private static AiPlatformApiException catchApi(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected AiPlatformApiException");
        } catch (AiPlatformApiException e) {
            return e;
        }
    }
}
