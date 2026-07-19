package com.databuff.apm.web.persistence;

import com.databuff.apm.web.TestStorageSupport;
import com.databuff.apm.web.ai.TestBeanSupport;
import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.web.ai.platform.capability.CapabilityManagementService;
import com.databuff.apm.web.ai.platform.expert.ExpertManagementService;
import com.databuff.apm.web.ai.platform.skill.AiSkillDefinition;
import com.databuff.apm.web.ai.platform.skill.SkillManagementService;
import com.databuff.apm.web.ai.platform.tool.ToolManagementService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiPlatformPersistenceTest {

    @Test
    void loadsToolsSkillsAndExpertsFromStore() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet schemaRs = mock(ResultSet.class);
        ResultSet toolRs = mock(ResultSet.class);
        ResultSet skillRs = mock(ResultSet.class);
        ResultSet expertRs = mock(ResultSet.class);
        ResultSet capabilityRs = mock(ResultSet.class);
        Instant now = Instant.parse("2026-06-06T08:00:00Z");

        when(reader.connection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("LIMIT 1")) {
                return schemaRs;
            }
            if (sql.contains("config_ai_tool")) {
                return toolRs;
            }
            if (sql.contains("config_ai_skill")) {
                return skillRs;
            }
            if (sql.contains("config_ai_expert")) {
                return expertRs;
            }
            if (sql.contains("config_ai_capability")) {
                return capabilityRs;
            }
            return schemaRs;
        });

        when(toolRs.next()).thenReturn(true, false);
        when(toolRs.getString("tool_id")).thenReturn("custom.tool");
        when(toolRs.getString("name")).thenReturn("Custom Tool");
        when(toolRs.getString("description")).thenReturn("tool description");
        when(toolRs.getString("type")).thenReturn("JAVA_BEAN");
        when(toolRs.getString("implementation")).thenReturn("customToolBean");
        when(toolRs.getString("schema_json")).thenReturn("{}");
        when(toolRs.getString("config_json")).thenReturn("{}");
        when(toolRs.getInt("enabled")).thenReturn(1);
        when(toolRs.getInt("built_in")).thenReturn(0);
        when(toolRs.getLong("version")).thenReturn(3L);
        when(toolRs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
        when(toolRs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));

        when(skillRs.next()).thenReturn(true, false);
        when(skillRs.getString("skill_id")).thenReturn("custom.skill");
        when(skillRs.getString("name")).thenReturn("Custom Skill");
        when(skillRs.getString("description")).thenReturn("skill description");
        when(skillRs.getString("content_uri")).thenReturn("s3://skills/custom/SKILL.md");
        when(skillRs.getString("file_path")).thenReturn("/mnt/skills/custom/SKILL.md");
        when(skillRs.getInt("enabled")).thenReturn(1);
        when(skillRs.getInt("built_in")).thenReturn(0);
        when(skillRs.getLong("version")).thenReturn(2L);
        when(skillRs.getString("checksum")).thenReturn("sha256:abc");
        when(skillRs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
        when(skillRs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));

        when(expertRs.next()).thenReturn(true, false);
        when(expertRs.getString("expert_id")).thenReturn("custom.expert");
        when(expertRs.getString("name")).thenReturn("Custom Expert");
        when(expertRs.getString("description")).thenReturn("expert description");
        when(expertRs.getString("type")).thenReturn("CUSTOM");
        when(expertRs.getString("model_provider_code")).thenReturn("openai");
        when(expertRs.getString("model_name")).thenReturn("gpt-4o-mini");
        when(expertRs.getString("system_prompt")).thenReturn("You are a custom expert.");
        when(expertRs.getString("tool_ids_json")).thenReturn("[\"custom.tool\"]");
        when(expertRs.getString("skill_ids_json")).thenReturn("[\"custom.skill\"]");
        when(expertRs.getString("options_json")).thenReturn("""
                {"stream":false,"enablePlan":true,"dynamicSkillsEnabled":false,
                "exposeToolEvents":true}
                """);
        when(expertRs.getInt("enabled")).thenReturn(1);
        when(expertRs.getInt("built_in")).thenReturn(0);
        when(expertRs.getLong("version")).thenReturn(7L);
        when(expertRs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
        when(expertRs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));

        ToolManagementService toolService = TestBeanSupport.toolManagementService();
        SkillManagementService skillService = TestBeanSupport.skillManagementService();
        ExpertManagementService expertService = TestBeanSupport.expertManagementService(toolService, skillService);
        CapabilityManagementService capabilityService = TestBeanSupport.capabilityManagementService();
        when(capabilityRs.next()).thenReturn(false);
        AiPlatformPersistence sync = new AiPlatformPersistence(
                reader, toolService, skillService, expertService, capabilityService, TestStorageSupport.storage());

        sync.reloadFromStore();

        assertThat(sync.persistenceEnabled()).isTrue();
        assertThat(toolService.find("custom.tool")).isPresent();
        assertThat(skillService.find("custom.skill").orElseThrow().contentUri())
                .isEqualTo("s3://skills/custom/SKILL.md");
        assertThat(expertService.find("custom.expert")).isPresent();
        assertThat(expertService.find("custom.expert").orElseThrow().toolIds()).containsExactly("custom.tool");
        assertThat(expertService.find("custom.expert").orElseThrow().skillIds()).containsExactly("custom.skill");
        assertThat(expertService.find("custom.expert").orElseThrow().version()).isEqualTo(7L);
    }

    @Test
    void loadsCapabilitiesEvenWhenExpertHydrationFails() throws Exception {
        ApmReadRepository reader = mock(ApmReadRepository.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet schemaRs = mock(ResultSet.class);
        ResultSet toolRs = mock(ResultSet.class);
        ResultSet skillRs = mock(ResultSet.class);
        ResultSet expertRs = mock(ResultSet.class);
        ResultSet capabilityRs = mock(ResultSet.class);
        Instant now = Instant.parse("2026-06-06T08:00:00Z");

        when(reader.connection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("LIMIT 1")) {
                return schemaRs;
            }
            if (sql.contains("config_ai_tool")) {
                return toolRs;
            }
            if (sql.contains("config_ai_skill")) {
                return skillRs;
            }
            if (sql.contains("config_ai_expert")) {
                return expertRs;
            }
            if (sql.contains("config_ai_capability")) {
                return capabilityRs;
            }
            return schemaRs;
        });

        when(toolRs.next()).thenReturn(false);
        when(skillRs.next()).thenReturn(false);

        // Expert row references a skill that is disabled in the store -> validate() throws.
        when(expertRs.next()).thenReturn(true, false);
        when(expertRs.getString("expert_id")).thenReturn("brain");
        when(expertRs.getString("name")).thenReturn("AI大脑");
        when(expertRs.getString("description")).thenReturn("router");
        when(expertRs.getString("type")).thenReturn("BRAIN");
        when(expertRs.getString("model_provider_code")).thenReturn(null);
        when(expertRs.getString("model_name")).thenReturn(null);
        when(expertRs.getString("system_prompt")).thenReturn("");
        when(expertRs.getString("tool_ids_json")).thenReturn("[]");
        when(expertRs.getString("skill_ids_json")).thenReturn("[\"skill.brain.routing\"]");
        when(expertRs.getString("options_json")).thenReturn("{}");
        when(expertRs.getInt("enabled")).thenReturn(1);
        when(expertRs.getInt("built_in")).thenReturn(1);
        when(expertRs.getLong("version")).thenReturn(2L);
        when(expertRs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
        when(expertRs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));

        when(capabilityRs.next()).thenReturn(true, false);
        when(capabilityRs.getString("capability_id")).thenReturn("1");
        when(capabilityRs.getString("name")).thenReturn("看得见");
        when(capabilityRs.getString("tagline")).thenReturn("自然语言问系统");
        when(capabilityRs.getString("expert_id")).thenReturn("");
        when(capabilityRs.getString("prompts_json")).thenReturn("[]");
        when(capabilityRs.getInt("enabled")).thenReturn(1);
        when(capabilityRs.getInt("built_in")).thenReturn(1);
        when(capabilityRs.getLong("version")).thenReturn(1L);
        when(capabilityRs.getTimestamp("created_at")).thenReturn(Timestamp.from(now));
        when(capabilityRs.getTimestamp("updated_at")).thenReturn(Timestamp.from(now));
        when(capabilityRs.getString("default_name")).thenReturn("看得见");
        when(capabilityRs.getString("default_tagline")).thenReturn("自然语言问系统");
        when(capabilityRs.getString("default_expert_id")).thenReturn("");
        when(capabilityRs.getString("default_prompts_json")).thenReturn("[]");

        ToolManagementService toolService = TestBeanSupport.toolManagementService();
        SkillManagementService skillService = TestBeanSupport.skillManagementService();
        // Force the catalog brain-routing skill to disabled, mirroring the demo's bad row.
        AiSkillDefinition routing = skillService.find("skill.brain.routing").orElseThrow();
        skillService.save(new AiSkillDefinition(
                routing.skillId(), routing.name(), routing.category(), routing.description(),
                routing.contentUri(), routing.filePath(), false, routing.builtIn(),
                routing.version(), routing.checksum(), routing.createdAt(), routing.updatedAt()));
        ExpertManagementService expertService = TestBeanSupport.expertManagementService(toolService, skillService);
        CapabilityManagementService capabilityService = TestBeanSupport.capabilityManagementService();
        AiPlatformPersistence sync = new AiPlatformPersistence(
                reader, toolService, skillService, expertService, capabilityService, TestStorageSupport.storage());

        sync.reloadFromStore();

        // Capabilities must still hydrate even though expert hydration skipped the bad row.
        assertThat(sync.persistenceEnabled()).isTrue();
        assertThat(capabilityService.find("1")).isPresent();
        // The bad expert row is skipped rather than aborting the whole section; the
        // in-memory built-in brain expert (from @PostConstruct) remains intact.
        assertThat(expertService.find("brain")).isPresent();
        Mockito.verify(expertRs, Mockito.atLeastOnce()).next();
    }
}
