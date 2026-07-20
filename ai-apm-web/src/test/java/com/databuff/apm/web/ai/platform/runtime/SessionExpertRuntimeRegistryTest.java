package com.databuff.apm.web.ai.platform.runtime;

import com.databuff.apm.web.ai.TestAiSupport;
import com.databuff.apm.web.ai.TestBeanSupport;
import com.databuff.apm.web.ai.UpdateLlmProviderRequest;
import com.databuff.apm.web.ai.platform.expert.AiExpertDefinition;
import com.databuff.apm.web.ai.platform.expert.BrainRoutingCatalog;
import com.databuff.apm.web.ai.platform.expert.ExpertManagementService;
import com.databuff.apm.web.ai.platform.skill.SkillManagementService;
import com.databuff.apm.web.ai.platform.tool.ExpertToolResolver;
import com.databuff.apm.web.ai.platform.tool.ToolManagementService;
import com.databuff.apm.web.ai.tool.ApmToolkit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SessionExpertRuntimeRegistryTest {

    @TempDir
    Path tempDir;

    private SessionExpertRuntimeRegistry registry;
    private ExpertManagementService expertManagementService;

    @BeforeEach
    void setUp() {
        TestAiSupport.AiFixture aiFixture = TestAiSupport.aiFixture();
        aiFixture.agentRuntimeConfig().setCustomSkillsDir(tempDir.toString());
        aiFixture.store().updateProvider("openai", new UpdateLlmProviderRequest(null, "sk-test", null, true));
        TestAiSupport.PlatformRuntimeFixture fixture = aiFixture.buildPlatformRuntime(mock(ApmToolkit.class));
        ToolManagementService toolManagementService = TestBeanSupport.toolManagementService();
        SkillManagementService skillManagementService = TestBeanSupport.skillManagementService();
        expertManagementService = TestBeanSupport.expertManagementService(toolManagementService, skillManagementService);
        ExpertToolResolver expertToolResolver = new ExpertToolResolver(toolManagementService);
        BrainRoutingCatalog brainRoutingCatalog = new BrainRoutingCatalog(expertManagementService);
        registry = new SessionExpertRuntimeRegistry(
                skillManagementService,
                aiFixture.store(),
                expertToolResolver,
                fixture.runtimeAdapter(),
                brainRoutingCatalog);
    }

    @Test
    void reusesSameBrainRuntimeForSessionUntilReleased() {
        AiExpertDefinition brain = expertManagementService.find("brain").orElseThrow();
        String sessionId = "session-a";

        ExpertRuntime first = registry.getOrCreate(sessionId, brain);
        ExpertRuntime second = registry.getOrCreate(sessionId, brain);

        assertThat(first).isSameAs(second);
        assertThat(first).isInstanceOf(AgentScopeExpertRuntime.class);
    }

    @Test
    void keepsSeparateRuntimesPerExpertInSameSession() {
        AiExpertDefinition brain = expertManagementService.find("brain").orElseThrow();
        AiExpertDefinition inspection = expertManagementService.find("inspection").orElseThrow();
        String sessionId = "session-multi-expert";

        ExpertRuntime brainRuntime = registry.getOrCreate(sessionId, brain);
        ExpertRuntime inspectionRuntime = registry.getOrCreate(sessionId, inspection);

        assertThat(inspectionRuntime).isNotSameAs(brainRuntime);
        assertThat(registry.getOrCreate(sessionId, inspection)).isSameAs(inspectionRuntime);
    }

    @Test
    void releaseOneExpertDoesNotDropOtherExpertsInSession() {
        AiExpertDefinition brain = expertManagementService.find("brain").orElseThrow();
        AiExpertDefinition inspection = expertManagementService.find("inspection").orElseThrow();
        String sessionId = "session-release-one";

        ExpertRuntime brainRuntime = registry.getOrCreate(sessionId, brain);
        ExpertRuntime inspectionRuntime = registry.getOrCreate(sessionId, inspection);

        registry.release(sessionId, "brain");

        assertThat(registry.getOrCreate(sessionId, brain)).isNotSameAs(brainRuntime);
        assertThat(registry.getOrCreate(sessionId, inspection)).isSameAs(inspectionRuntime);
    }

    @Test
    void releaseRecreatesRuntimeForSameSession() {
        AiExpertDefinition brain = expertManagementService.find("brain").orElseThrow();
        String sessionId = "session-b";

        ExpertRuntime first = registry.getOrCreate(sessionId, brain);
        registry.release(sessionId);
        ExpertRuntime second = registry.getOrCreate(sessionId, brain);

        assertThat(second).isNotSameAs(first);
    }
}
