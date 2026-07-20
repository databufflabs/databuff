package com.databuff.apm.web.ai.platform.skill;

import com.databuff.apm.web.ai.TestBeanSupport;
import com.databuff.apm.web.ai.agent.AgentRuntimeConfig;
import com.databuff.apm.web.ai.platform.runtime.SkillFileSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class SkillPackageServiceTest {

    @TempDir
    Path tempDir;

    private SkillPackageService service;
    private SkillManagementService skillManagementService;

    @BeforeEach
    void setUp() {
        AgentRuntimeConfig runtimeConfig = new AgentRuntimeConfig();
        runtimeConfig.setCustomSkillsDir(tempDir.toString());
        skillManagementService = TestBeanSupport.skillManagementService();
        SkillFileSyncService skillFileSyncService = new SkillFileSyncService(
                runtimeConfig,
                skillManagementService,
                new org.springframework.core.io.DefaultResourceLoader());
        service = new SkillPackageService(runtimeConfig, skillManagementService, skillFileSyncService);
    }

    @Test
    void parsesFrontMatterFromSkillMarkdown() {
        String markdown = """
                ---
                name: skill.demo.test
                description: Demo skill
                ---
                body
                """;
        Map<String, String> frontMatter = SkillPackageService.parseFrontMatter(markdown);
        assertThat(frontMatter).containsEntry("name", "skill.demo.test")
                .containsEntry("description", "Demo skill");
    }

    @Test
    void previewsAndImportsZipPackage() throws Exception {
        byte[] zipBytes = buildZip("""
                ---
                name: skill.demo.import
                description: Imported skill
                ---
                Use this skill.
                """, "scripts/run.sh", "#!/bin/sh\necho hi");

        MockMultipartFile file = new MockMultipartFile(
                "file", "demo.zip", "application/zip", zipBytes);

        SkillPackageService.SkillImportPreview preview = service.previewZip(file);
        assertThat(preview.skillId()).isEqualTo("skill.demo.import");
        assertThat(preview.description()).isEqualTo("Imported skill");
        assertThat(preview.files()).extracting(SkillPackageService.SkillFileEntry::path)
                .contains("SKILL.md", "scripts/run.sh");

        AiSkillDefinition created = service.importZip(
                new MockMultipartFile("file", "demo.zip", "application/zip", zipBytes),
                "展示名称",
                null,
                "自定义描述",
                true);
        assertThat(created.skillId()).isEqualTo("skill.demo.import");
        assertThat(created.name()).isEqualTo("展示名称");
        assertThat(created.description()).isEqualTo("自定义描述");
        assertThat(Files.exists(tempDir.resolve("skill.demo.import/SKILL.md"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("skill.demo.import/scripts/run.sh"))).isTrue();

        assertThat(service.listFiles("skill.demo.import"))
                .extracting(SkillPackageService.SkillFileEntry::path)
                .contains("SKILL.md", "scripts/run.sh");
        SkillPackageService.SkillFileContent content = service.readFile("skill.demo.import", "scripts/run.sh");
        assertThat(content.content()).contains("echo hi");
    }

    @Test
    void listsBuiltinSkillPackageFilesIncludingTemplates() {
        AgentRuntimeConfig runtimeConfig = new AgentRuntimeConfig();
        runtimeConfig.setCustomSkillsDir(tempDir.resolve("custom-skills").toString());
        Path builtin = Path.of("../deploy/common/skills").toAbsolutePath().normalize();
        if (!Files.isDirectory(builtin.resolve("skill.summary.html"))) {
            builtin = Path.of("deploy/common/skills").toAbsolutePath().normalize();
        }
        runtimeConfig.setBuiltinSkillsDir(builtin.toString());
        SkillPackageService listingService = new SkillPackageService(
                runtimeConfig,
                skillManagementService,
                new SkillFileSyncService(
                        runtimeConfig,
                        skillManagementService,
                        new org.springframework.core.io.DefaultResourceLoader()));

        assertThat(listingService.listFiles("skill.summary.html"))
                .extracting(SkillPackageService.SkillFileEntry::path)
                .contains(
                        "SKILL.md",
                        "templates/summary-brief.html",
                        "templates/report-analysis.html",
                        "templates/inspection-report.html",
                        "templates/trend-chart-snippet.html");
    }

    private static byte[] buildZip(String skillMarkdown, String extraPath, String extraContent) throws Exception {
        Path zipPath = Files.createTempFile("skill-test-", ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            zos.putNextEntry(new ZipEntry("demo-skill/SKILL.md"));
            zos.write(skillMarkdown.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("demo-skill/" + extraPath));
            zos.write(extraContent.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        byte[] bytes = Files.readAllBytes(zipPath);
        Files.deleteIfExists(zipPath);
        return bytes;
    }
}
