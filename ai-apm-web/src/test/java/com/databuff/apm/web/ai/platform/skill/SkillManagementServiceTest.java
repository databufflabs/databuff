package com.databuff.apm.web.ai.platform.skill;

import com.databuff.apm.web.ai.TestBeanSupport;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillManagementServiceTest {

    @Test
    void seedsBuiltInSkillsWithContentUriAndProtectsDelete() {
        SkillManagementService service = TestBeanSupport.skillManagementService();
        assertThat(service.list()).extracting(AiSkillDefinition::skillId)
                .contains("skill.brain.routing", "skill.data.metrics", "skill.inspection.health");
        assertThat(service.find("skill.data.metrics")).get()
                .extracting(AiSkillDefinition::contentUri)
                .asString()
                .startsWith("deploy-common:");
        assertThat(service.delete("skill.data.metrics")).isFalse();
    }

    @Test
    void savesCustomSkillLocationOnlyAndIncrementsVersion() {
        SkillManagementService service = TestBeanSupport.skillManagementService();
        Instant now = Instant.now();
        AiSkillDefinition created = service.save(new AiSkillDefinition(
                "custom.skill", "Custom", null, "custom skill",
                "file:///skills/custom/SKILL.md", "./data/skills/custom.skill/SKILL.md",
                true, false, 0, "sha256:a", now, now));
        AiSkillDefinition updated = service.save(new AiSkillDefinition(
                "custom.skill", "Custom", null, "custom skill",
                "file:///skills/custom/SKILL.md", "./data/skills/custom.skill/SKILL.md",
                true, false, 0, "sha256:b", now, now));

        assertThat(created.contentUri()).isEqualTo("file:///skills/custom/SKILL.md");
        assertThat(updated.version()).isEqualTo(2);
        assertThat(service.delete("custom.skill")).isTrue();
    }

    @Test
    void rejectsSkillWithoutContentUri() {
        SkillManagementService service = TestBeanSupport.skillManagementService();
        assertThatThrownBy(() -> service.save(new AiSkillDefinition(
                "custom.skill", "Custom", null, "custom skill", "", "./data/skills/custom.skill/SKILL.md",
                true, false, 0, "", Instant.now(), Instant.now())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
