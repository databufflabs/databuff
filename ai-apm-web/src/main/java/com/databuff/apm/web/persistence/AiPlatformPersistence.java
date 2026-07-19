package com.databuff.apm.web.persistence;

import com.databuff.apm.common.storage.ApmConfigRepository;
import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.web.ai.platform.capability.AiCapabilityDefinition;
import com.databuff.apm.web.ai.platform.capability.CapabilityManagementService;
import com.databuff.apm.web.ai.platform.expert.AiExpertDefinition;
import com.databuff.apm.web.ai.platform.expert.ExpertManagementService;
import com.databuff.apm.web.ai.platform.expert.ExpertRuntimeOptions;
import com.databuff.apm.web.ai.platform.expert.ExpertType;
import com.databuff.apm.web.ai.platform.skill.AiSkillDefinition;
import com.databuff.apm.web.ai.platform.skill.SkillManagementService;
import com.databuff.apm.web.ai.platform.tool.AiToolDefinition;
import com.databuff.apm.web.ai.platform.tool.ToolManagementService;
import com.databuff.apm.web.ai.platform.tool.ToolType;
import com.databuff.apm.web.config.ApmStorageProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AiPlatformPersistence {

    private static final Logger log = LoggerFactory.getLogger(AiPlatformPersistence.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final ApmReadRepository readRepository;
    private final ToolManagementService toolManagementService;
    private final SkillManagementService skillManagementService;
    private final ExpertManagementService expertManagementService;
    private final CapabilityManagementService capabilityManagementService;
    private final String configDatabase;
    private volatile boolean persistenceEnabled;

    public AiPlatformPersistence(
            ApmReadRepository readRepository,
            ToolManagementService toolManagementService,
            SkillManagementService skillManagementService,
            ExpertManagementService expertManagementService,
            CapabilityManagementService capabilityManagementService,
            ApmStorageProperties storageProperties) {
        this.readRepository = readRepository;
        this.toolManagementService = toolManagementService;
        this.skillManagementService = skillManagementService;
        this.expertManagementService = expertManagementService;
        this.capabilityManagementService = capabilityManagementService;
        this.configDatabase = storageProperties.configDatabase();
    }

    void reloadFromStore() {
        ApmConfigRepository repository = new ApmConfigRepository(readRepository, configDatabase);
        if (!repository.aiPlatformSchemaReady()) {
            log.info("AI platform config store not ready; platform definitions stay in-memory only");
            return;
        }
        // Load each section independently so one bad row (e.g. an expert referencing
        // a disabled skill) does not abort hydration of unrelated sections — most
        // notably capabilities, which the homepage 7-step arc depends on.
        List<AiToolDefinition> tools = loadSection("tools", repository::loadAiTools, AiPlatformPersistence::toToolDefinition);
        List<AiSkillDefinition> skills = loadSection("skills", repository::loadAiSkills, AiPlatformPersistence::toSkillDefinition);
        List<AiExpertDefinition> experts = loadSection("experts", repository::loadAiExperts, AiPlatformPersistence::toExpertDefinition);
        List<AiCapabilityDefinition> capabilities = loadSection("capabilities", repository::loadAiCapabilities, AiPlatformPersistence::toCapabilityDefinition);

        applySection("tools", tools, toolManagementService::applyPersistedRows);
        applySection("skills", skills, skillManagementService::applyPersistedRows);
        applySection("experts", experts, expertManagementService::applyPersistedRows);
        applySection("capabilities", capabilities, capabilityManagementService::applyPersistedRows);

        persistenceEnabled = true;
        log.info("AI platform persistence enabled ({} tools, {} skills, {} experts, {} capabilities from store)",
                tools.size(), skills.size(), experts.size(), capabilities.size());
    }

    private <R, T> List<T> loadSection(String name, SqlLoader<R> loader, java.util.function.Function<R, T> mapper) {
        try {
            return loader.load().stream().map(mapper).toList();
        } catch (Exception e) {
            log.warn("Failed to load AI platform {} from store: {}", name, e.getMessage());
            return List.of();
        }
    }

    private <T> void applySection(String name, List<T> rows, java.util.function.Consumer<List<T>> applier) {
        if (rows.isEmpty()) {
            return;
        }
        try {
            applier.accept(rows);
        } catch (Exception e) {
            log.warn("Failed to apply AI platform {} from store (skipped): {}", name, e.getMessage());
        }
    }

    @FunctionalInterface
    private interface SqlLoader<R> {
        List<R> load() throws Exception;
    }

    public void persistTool(AiToolDefinition definition) {
        if (!persistenceEnabled) {
            return;
        }
        try {
            new ApmConfigRepository(readRepository, configDatabase).upsertAiTool(toToolRow(definition));
        } catch (Exception e) {
            log.warn("Failed to persist AI tool {}: {}", definition.toolId(), e.getMessage());
        }
    }

    public void deleteTool(String toolId) {
        if (!persistenceEnabled) {
            return;
        }
        try {
            new ApmConfigRepository(readRepository, configDatabase).deleteAiTool(toolId);
        } catch (Exception e) {
            log.warn("Failed to delete AI tool {} from store: {}", toolId, e.getMessage());
        }
    }

    public void persistSkill(AiSkillDefinition definition) {
        if (!persistenceEnabled) {
            return;
        }
        try {
            new ApmConfigRepository(readRepository, configDatabase).upsertAiSkill(toSkillRow(definition));
        } catch (Exception e) {
            log.warn("Failed to persist AI skill {}: {}", definition.skillId(), e.getMessage());
        }
    }

    public void deleteSkill(String skillId) {
        if (!persistenceEnabled) {
            return;
        }
        try {
            new ApmConfigRepository(readRepository, configDatabase).deleteAiSkill(skillId);
        } catch (Exception e) {
            log.warn("Failed to delete AI skill {} from store: {}", skillId, e.getMessage());
        }
    }

    public void persistExpert(AiExpertDefinition definition) {
        if (!persistenceEnabled) {
            return;
        }
        try {
            new ApmConfigRepository(readRepository, configDatabase).upsertAiExpert(toExpertRow(definition));
        } catch (Exception e) {
            log.warn("Failed to persist AI expert {}: {}", definition.expertId(), e.getMessage());
        }
    }

    public void deleteExpert(String expertId) {
        if (!persistenceEnabled) {
            return;
        }
        try {
            new ApmConfigRepository(readRepository, configDatabase).deleteAiExpert(expertId);
        } catch (Exception e) {
            log.warn("Failed to delete AI expert {} from store: {}", expertId, e.getMessage());
        }
    }

    public void persistCapability(AiCapabilityDefinition definition) {
        if (!persistenceEnabled) {
            return;
        }
        try {
            new ApmConfigRepository(readRepository, configDatabase).upsertAiCapability(toCapabilityRow(definition));
        } catch (Exception e) {
            log.warn("Failed to persist AI capability {}: {}", definition.capabilityId(), e.getMessage());
        }
    }

    public void deleteCapability(String capabilityId) {
        if (!persistenceEnabled) {
            return;
        }
        try {
            new ApmConfigRepository(readRepository, configDatabase).deleteAiCapability(capabilityId);
        } catch (Exception e) {
            log.warn("Failed to delete AI capability {} from store: {}", capabilityId, e.getMessage());
        }
    }

    boolean persistenceEnabled() {
        return persistenceEnabled;
    }

    private static AiToolDefinition toToolDefinition(ApmConfigRepository.AiToolRow row) {
        return new AiToolDefinition(
                row.toolId(), row.name(), row.category(), row.description(), ToolType.valueOf(row.type()),
                row.implementation(), row.schemaJson(), row.configJson(), row.enabled(), row.builtIn(),
                row.version(), row.createdAt(), row.updatedAt());
    }

    private static ApmConfigRepository.AiToolRow toToolRow(AiToolDefinition definition) {
        return new ApmConfigRepository.AiToolRow(
                definition.toolId(), definition.name(), definition.category(), definition.description(), definition.type().name(),
                definition.implementation(), definition.schemaJson(), definition.configJson(),
                definition.enabled(), definition.builtIn(), definition.version(),
                definition.createdAt(), definition.updatedAt());
    }

    private static AiSkillDefinition toSkillDefinition(ApmConfigRepository.AiSkillRow row) {
        return new AiSkillDefinition(
                row.skillId(), row.name(), row.category(), row.description(), row.contentUri(), row.filePath(),
                row.enabled(), row.builtIn(), row.version(), row.checksum(), row.createdAt(), row.updatedAt());
    }

    private static ApmConfigRepository.AiSkillRow toSkillRow(AiSkillDefinition definition) {
        return new ApmConfigRepository.AiSkillRow(
                definition.skillId(), definition.name(), definition.category(), definition.description(), definition.contentUri(),
                definition.filePath(), definition.enabled(), definition.builtIn(), definition.version(),
                definition.checksum(), definition.createdAt(), definition.updatedAt());
    }

    private static AiExpertDefinition toExpertDefinition(ApmConfigRepository.AiExpertRow row) {
        return new AiExpertDefinition(
                row.expertId(), row.name(), row.category(), row.description(), ExpertType.valueOf(row.type()),
                row.modelProviderCode(), row.modelName(), row.systemPrompt(),
                readStringList(row.toolIdsJson()), readStringList(row.skillIdsJson()),
                readOptions(row.optionsJson()), row.enabled(), row.builtIn(), row.version(),
                row.createdAt(), row.updatedAt());
    }

    private static ApmConfigRepository.AiExpertRow toExpertRow(AiExpertDefinition definition) {
        return new ApmConfigRepository.AiExpertRow(
                definition.expertId(), definition.name(), definition.category(), definition.description(), definition.type().name(),
                definition.modelProviderCode(), definition.modelName(), definition.systemPrompt(),
                writeJson(definition.toolIds()), writeJson(definition.skillIds()), writeJson(definition.options()),
                definition.enabled(), definition.builtIn(), definition.version(),
                definition.createdAt(), definition.updatedAt());
    }

    private static AiCapabilityDefinition toCapabilityDefinition(ApmConfigRepository.AiCapabilityRow row) {
        return new AiCapabilityDefinition(
                row.capabilityId(), row.name(), row.tagline(), null, row.expertId(),
                CapabilityManagementService.readPrompts(row.promptsJson()),
                row.enabled(), row.builtIn(), row.version(), row.createdAt(), row.updatedAt(),
                row.defaultName(), row.defaultTagline(), row.defaultExpertId(),
                CapabilityManagementService.readPrompts(row.defaultPromptsJson()));
    }

    private static ApmConfigRepository.AiCapabilityRow toCapabilityRow(AiCapabilityDefinition definition) {
        return new ApmConfigRepository.AiCapabilityRow(
                definition.capabilityId(), definition.name(), definition.tagline(), definition.expertId(),
                CapabilityManagementService.writePrompts(definition.prompts()),
                definition.enabled(), definition.builtIn(), definition.version(),
                definition.createdAt(), definition.updatedAt(),
                definition.defaultName(), definition.defaultTagline(), definition.defaultExpertId(),
                CapabilityManagementService.writePrompts(definition.defaultPrompts()));
    }

    private static List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, STRING_LIST);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid string list json", e);
        }
    }

    private static ExpertRuntimeOptions readOptions(String json) {
        if (json == null || json.isBlank()) {
            return ExpertRuntimeOptions.defaults();
        }
        try {
            return OBJECT_MAPPER.readValue(json, ExpertRuntimeOptions.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid expert options json", e);
        }
    }

    private static String writeJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to write json", e);
        }
    }
}
