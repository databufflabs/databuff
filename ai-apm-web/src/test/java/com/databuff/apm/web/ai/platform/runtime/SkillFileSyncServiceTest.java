package com.databuff.apm.web.ai.platform.runtime;

import com.databuff.apm.web.ai.TestBeanSupport;
import com.databuff.apm.web.ai.agent.AgentRuntimeConfig;
import com.databuff.apm.web.ai.platform.skill.AiSkillDefinition;
import com.databuff.apm.web.ai.platform.skill.DeployCommonSkills;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SkillFileSyncServiceTest {

    @TempDir
    Path tempDir;

    private SkillFileSyncService service;
    private AgentRuntimeConfig runtimeConfig;

    @BeforeEach
    void setUp() throws Exception {
        Path builtinDir = tempDir.resolve("builtin");
        Path customDir = tempDir.resolve("custom");
        Files.createDirectories(builtinDir.resolve("skill.data.metrics"));
        Files.writeString(
                builtinDir.resolve("skill.data.metrics/SKILL.md"),
                "---\nname: skill.data.metrics\ndescription: metrics\n---\nmetrics body");
        Path qaProductSkill = Path.of("../deploy/common/skills/skill.qa.product/SKILL.md").normalize();
        Files.createDirectories(builtinDir.resolve("skill.qa.product"));
        Files.copy(qaProductSkill, builtinDir.resolve("skill.qa.product/SKILL.md"));

        runtimeConfig = new AgentRuntimeConfig();
        runtimeConfig.setBuiltinSkillsDir(builtinDir.toString());
        runtimeConfig.setCustomSkillsDir(customDir.toString());
        service = new SkillFileSyncService(
                runtimeConfig,
                TestBeanSupport.skillManagementService(),
                new DefaultResourceLoader());
    }

    @Test
    void resolvesDeployCommonBuiltinWithoutCopying() throws Exception {
        AiSkillDefinition skill = new AiSkillDefinition(
                "skill.data.metrics",
                "问数口径",
                null,
                "Metrics skill",
                DeployCommonSkills.contentUri("skill.data.metrics"),
                DeployCommonSkills.contentUri("skill.data.metrics"),
                true,
                true,
                1L,
                "",
                Instant.now(),
                Instant.now());

        Path synced = service.syncSkill(skill);

        assertThat(synced).isNotNull();
        assertThat(synced).isEqualTo(runtimeConfig.builtinSkillsDirectory().resolve("skill.data.metrics"));
        assertThat(Files.exists(runtimeConfig.customSkillsDirectory().resolve("skill.data.metrics/SKILL.md"))).isFalse();
        assertThat(service.readSkillContent(skill)).contains("skill.data.metrics");
    }

    @Test
    void qaProductSkillSteersUsersToOpenTelemetryNotOneAgent() throws Exception {
        AiSkillDefinition skill = new AiSkillDefinition(
                "skill.qa.product",
                "产品答疑",
                null,
                "Product QA skill",
                DeployCommonSkills.contentUri("skill.qa.product"),
                DeployCommonSkills.contentUri("skill.qa.product"),
                true,
                true,
                1L,
                "",
                Instant.now(),
                Instant.now());

        String body = service.readSkillContent(skill);

        assertThat(body)
                .contains("不支持")
                .contains("OneAgent")
                .contains("OpenTelemetry")
                .contains("docs/opentelemetry-otlp-ingestion.md");
    }

    @Test
    void syncsRemoteSkillToCustomDirectory() throws Exception {
        Path source = tempDir.resolve("source/SKILL.md");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "---\nname: custom.skill\n---\ncustom");

        AiSkillDefinition skill = new AiSkillDefinition(
                "custom.skill",
                "Custom",
                null,
                "custom",
                source.toUri().toString(),
                "",
                true,
                true,
                1L,
                "",
                Instant.now(),
                Instant.now());

        Path synced = service.syncSkill(skill);

        assertThat(synced).isNotNull();
        assertThat(Files.exists(runtimeConfig.customSkillsDirectory().resolve("custom.skill/SKILL.md"))).isTrue();
    }
}
