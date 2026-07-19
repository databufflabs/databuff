package com.databuff.apm.web.ai.platform.expert;

import com.databuff.apm.web.ai.platform.BuiltInExpertCatalog;
import com.databuff.apm.web.ai.platform.runtime.ExpertRuntimeRegistry;
import com.databuff.apm.web.ai.platform.skill.AiSkillDefinition;
import com.databuff.apm.web.ai.platform.skill.SkillManagementService;
import com.databuff.apm.web.ai.platform.tool.ToolManagementService;
import com.databuff.apm.web.persistence.AiPlatformPersistence;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class ExpertManagementService {

    private static final Logger log = LoggerFactory.getLogger(ExpertManagementService.class);

    @Autowired
    private ToolManagementService toolManagementService;
    @Autowired
    private SkillManagementService skillManagementService;
    @Autowired
    private ObjectProvider<AiPlatformPersistence> persistence;
    @Autowired
    private ObjectProvider<ExpertRuntimeRegistry> runtimeRegistry;
    private final ConcurrentMap<String, AiExpertDefinition> experts = new ConcurrentHashMap<>();

    @PostConstruct
    void initDefaults() {
        BuiltInExpertCatalog.experts().forEach(expert -> experts.put(expert.expertId(), expert));
    }

    public List<AiExpertDefinition> list() {
        return experts.values().stream()
                .sorted(Comparator.comparing(AiExpertDefinition::expertId))
                .toList();
    }

    public Optional<AiExpertDefinition> find(String expertId) {
        return Optional.ofNullable(experts.get(expertId));
    }

    public AiExpertDefinition save(AiExpertDefinition definition) {
        validate(definition);
        Instant now = Instant.now();
        AiExpertDefinition saved = experts.compute(definition.expertId(), (id, existing) -> {
            if (existing == null) {
                long version = definition.version() <= 0 ? 1L : definition.version();
                return new AiExpertDefinition(
                        definition.expertId(), definition.name(), definition.category(), definition.description(), definition.type(),
                        definition.modelProviderCode(), definition.modelName(), definition.systemPrompt(),
                        definition.toolIds(), definition.skillIds(), definition.options(),
                        definition.enabled(), definition.builtIn(), version, now, now);
            }
            return new AiExpertDefinition(
                    definition.expertId(), definition.name(), definition.category(), definition.description(), definition.type(),
                    definition.modelProviderCode(), definition.modelName(), definition.systemPrompt(),
                    definition.toolIds(), definition.skillIds(), definition.options(),
                    definition.enabled(), existing.builtIn(), existing.version() + 1, existing.createdAt(), now);
        });
        ifAvailable(sync -> sync.persistExpert(saved));
        invalidateRuntime(saved.expertId(), "expert saved");
        refreshBrainRouting(saved.expertId(), "expert saved");
        return saved;
    }

    public boolean delete(String expertId) {
        AiExpertDefinition existing = experts.get(expertId);
        if (existing == null || existing.builtIn()) {
            return false;
        }
        boolean removed = experts.remove(expertId) != null;
        if (removed) {
            ifAvailable(sync -> sync.deleteExpert(expertId));
            invalidateRuntime(expertId, "expert deleted");
            refreshBrainRouting(expertId, "expert deleted");
        }
        return removed;
    }

    public void applyPersistedRows(List<AiExpertDefinition> definitions) {
        boolean routableExpertsChanged = false;
        for (AiExpertDefinition definition : definitions) {
            try {
                validate(definition);
            } catch (Exception e) {
                // Skip a single bad persisted row (e.g. references a disabled skill)
                // rather than aborting hydration of every other expert.
                log.warn("Skipping persisted expert {} from store: {}", definition.expertId(), e.getMessage());
                continue;
            }
            experts.compute(definition.expertId(), (id, existing) -> {
                boolean builtIn = (existing != null && existing.builtIn()) || definition.builtIn();
                List<String> toolIds;
                String name;
                if (existing != null && existing.builtIn() && "brain".equals(id)) {
                    toolIds = existing.toolIds();
                    name = existing.name();
                } else if (existing != null && existing.builtIn()) {
                    toolIds = mergeIds(existing.toolIds(), definition.toolIds());
                    name = definition.name();
                } else {
                    toolIds = definition.toolIds();
                    name = definition.name();
                }
                List<String> skillIds = existing != null && existing.builtIn()
                        ? mergeBuiltInSkillIds(existing.skillIds(), definition.skillIds())
                        : definition.skillIds();
                String systemPrompt = existing != null && existing.builtIn()
                        ? resolveBuiltInSystemPrompt(existing, definition)
                        : definition.systemPrompt();
                return new AiExpertDefinition(
                        definition.expertId(), name, definition.category(), definition.description(), definition.type(),
                        definition.modelProviderCode(), definition.modelName(), systemPrompt,
                        toolIds, skillIds, definition.options(), definition.enabled(),
                        builtIn, definition.version(), definition.createdAt(), definition.updatedAt());
            });
            if (!"brain".equals(definition.expertId())) {
                routableExpertsChanged = true;
            }
        }
        if (routableExpertsChanged) {
            refreshBrainRouting("persisted", "experts reloaded from persistence");
        }
    }

    private static String resolveBuiltInSystemPrompt(AiExpertDefinition builtInDefault, AiExpertDefinition persisted) {
        if (blank(persisted.systemPrompt())
                || isLegacyBuiltInDefaultPrompt(persisted.expertId(), persisted.systemPrompt())) {
            return builtInDefault.systemPrompt();
        }
        return persisted.systemPrompt();
    }

    private static boolean isLegacyBuiltInDefaultPrompt(String expertId, String systemPrompt) {
        if (blank(systemPrompt)) {
            return true;
        }
        return switch (expertId) {
            case "data" -> systemPrompt.startsWith("You are the DataBuff APM data expert");
            case "brain" -> systemPrompt.startsWith("You are the DataBuff APM brain expert")
                    || systemPrompt.contains("Your primary job is routing");
            case "inspection" -> systemPrompt.startsWith("你是 DataBuff APM 智能巡检专家，负责对服务健康状态做初步异常检测");
            default -> false;
        };
    }

    private static List<String> mergeIds(List<String> builtInIds, List<String> persistedIds) {
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
        if (builtInIds != null) {
            merged.addAll(builtInIds);
        }
        if (persistedIds != null) {
            merged.addAll(persistedIds);
        }
        return List.copyOf(merged);
    }

    private static List<String> mergeBuiltInSkillIds(List<String> builtInSkillIds, List<String> persistedSkillIds) {
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
        if (builtInSkillIds != null) {
            merged.addAll(builtInSkillIds);
        }
        if (persistedSkillIds != null) {
            java.util.Set<String> catalogSkillIds = BuiltInExpertCatalog.skills().stream()
                    .map(AiSkillDefinition::skillId)
                    .collect(java.util.stream.Collectors.toSet());
            for (String skillId : persistedSkillIds) {
                if (!catalogSkillIds.contains(skillId)) {
                    merged.add(skillId);
                }
            }
        }
        return List.copyOf(merged);
    }

    private void validate(AiExpertDefinition definition) {
        if (definition == null || blank(definition.expertId()) || blank(definition.name())) {
            throw new IllegalArgumentException("expertId and name are required");
        }
        if (definition.type() == null) {
            throw new IllegalArgumentException("expert type is required");
        }
        for (String toolId : definition.toolIds()) {
            if (definition.options().toolAccessMode() == ExpertToolAccessMode.BLOCKLIST) {
                if (!toolManagementService.exists(toolId)) {
                    throw new IllegalArgumentException("tool not found: " + toolId);
                }
            } else if (!toolManagementService.existsEnabled(toolId)) {
                throw new IllegalArgumentException("tool not found or disabled: " + toolId);
            }
        }
        for (String skillId : definition.skillIds()) {
            if (!skillManagementService.existsEnabled(skillId)) {
                throw new IllegalArgumentException("skill not found or disabled: " + skillId);
            }
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private void ifAvailable(java.util.function.Consumer<AiPlatformPersistence> consumer) {
        if (persistence != null) {
            persistence.ifAvailable(consumer);
        }
    }

    private void invalidateRuntime(String expertId, String reason) {
        if (runtimeRegistry != null) {
            runtimeRegistry.ifAvailable(registry -> registry.invalidate(expertId, reason));
        }
    }

    private void refreshBrainRouting(String changedExpertId, String reason) {
        if ("brain".equals(changedExpertId)) {
            return;
        }
        invalidateRuntime("brain", "routable expert changed (" + changedExpertId + "): " + reason);
    }

    public List<String> listExpertIdsReferencingTool(String toolId) {
        if (blank(toolId)) {
            return List.of();
        }
        return experts.values().stream()
                .filter(definition -> definition.toolIds().contains(toolId))
                .map(AiExpertDefinition::expertId)
                .sorted()
                .toList();
    }

    public List<String> listExpertIdsReferencingSkill(String skillId) {
        if (blank(skillId)) {
            return List.of();
        }
        return experts.values().stream()
                .filter(definition -> definition.skillIds().contains(skillId))
                .map(AiExpertDefinition::expertId)
                .sorted()
                .toList();
    }
}
