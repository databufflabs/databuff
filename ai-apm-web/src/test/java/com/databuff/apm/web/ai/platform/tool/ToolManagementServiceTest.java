package com.databuff.apm.web.ai.platform.tool;

import com.databuff.apm.web.ai.TestBeanSupport;
import com.databuff.apm.web.persistence.AiPlatformPersistence;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ToolManagementServiceTest {

    @Test
    void seedsBuiltInToolsAndProtectsDelete() {
        ToolManagementService service = TestBeanSupport.toolManagementService();
        assertThat(service.list()).extracting(AiToolDefinition::toolId)
                .contains(
                        "common.getCurrentTimeRange",
                        "common.getTimeRangeAroundTime",
                        "common.drawTrendCharts",
                        "time.getCurrentTimeRange",
                        "time.getTimeRangeAroundTime",
                        "data.queryServicesAll",
                        "data.queryServicesByServiceType",
                        "data.queryServiceTopology",
                        "data.queryTraceListByCondition",
                        "data.queryTraceDetail",
                        "data.queryServiceAlarms",
                        "data.queryMetricData",
                        "inspect.inspectService",
                        "Bash",
                        "BashOutput",
                        "KillShell",
                        "platform.queryDorisBusinessData",
                        "platform.querySelfMonitorMetrics");
        assertThat(service.delete("data.queryServicesAll")).isFalse();
    }

    @Test
    void savesCustomToolAndIncrementsVersion() {
        ToolManagementService service = TestBeanSupport.toolManagementService();
        Instant now = Instant.now();
        AiToolDefinition created = service.save(new AiToolDefinition(
                "custom.echo", "Echo", null, "echo", ToolType.JAVA_BEAN, "echoTool.echo",
                "{}", "{}", true, false, 0, now, now));
        AiToolDefinition updated = service.save(new AiToolDefinition(
                "custom.echo", "Echo 2", null, "echo", ToolType.JAVA_BEAN, "echoTool.echo",
                "{}", "{}", true, false, 0, now, now));

        assertThat(created.version()).isEqualTo(1);
        assertThat(updated.version()).isEqualTo(2);
        assertThat(service.delete("custom.echo")).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void persistsCustomToolOnSave() {
        ObjectProvider<AiPlatformPersistence> provider = mock(ObjectProvider.class);
        AiPlatformPersistence persistence = mock(AiPlatformPersistence.class);
        doAnswer(invocation -> {
            Consumer<AiPlatformPersistence> consumer = invocation.getArgument(0);
            consumer.accept(persistence);
            return null;
        }).when(provider).ifAvailable(any());

        ToolManagementService service = TestBeanSupport.toolManagementService(provider, null);
        Instant now = Instant.now();
        AiToolDefinition saved = service.save(new AiToolDefinition(
                "custom.persist", "Persist", null, "persist", ToolType.JAVA_BEAN, "persistTool.run",
                "{}", "{}", true, false, 0, now, now));

        verify(persistence).persistTool(saved);
    }

    @Test
    void rejectsInvalidTool() {
        ToolManagementService service = TestBeanSupport.toolManagementService();
        assertThatThrownBy(() -> service.save(new AiToolDefinition(
                "", "Echo", null, "echo", ToolType.JAVA_BEAN, "echoTool.echo",
                "{}", "{}", true, false, 0, Instant.now(), Instant.now())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
