package com.databuff.apm.web.ai.platform.expert;

import com.databuff.apm.web.ai.platform.BuiltInExpertCatalog;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * Builds the routable digital-expert catalog injected into the brain system prompt at runtime.
 */
@Service
public class BrainRoutingCatalog {

    private final ExpertManagementService expertManagementService;

    public BrainRoutingCatalog(ExpertManagementService expertManagementService) {
        this.expertManagementService = expertManagementService;
    }

    public String resolveBrainSystemPrompt(AiExpertDefinition brainExpert) {
        String base = brainExpert == null || blank(brainExpert.systemPrompt())
                ? BuiltInExpertCatalog.brainPromptBase()
                : brainExpert.systemPrompt();
        return base + "\n\n" + buildRoutableExpertsSection();
    }

    public List<AiExpertDefinition> listRoutableExperts() {
        return expertManagementService.list().stream()
                .filter(AiExpertDefinition::enabled)
                .filter(expert -> !"brain".equals(expert.expertId()))
                .sorted(Comparator.comparing(AiExpertDefinition::expertId))
                .toList();
    }

    public String buildRoutableExpertsSection() {
        List<AiExpertDefinition> experts = listRoutableExperts();
        if (experts.isEmpty()) {
            return """
                    可派发的数字专家（dispatchExpertTask）：
                    （暂无可用专家，请先配置数字专家。）
                    """;
        }
        StringBuilder section = new StringBuilder("""
                可派发的数字专家（dispatchExpertTask，targetExpertId 必须与下列 id 完全一致）：
                """);
        for (AiExpertDefinition expert : experts) {
            section.append("- `").append(expert.expertId()).append("`");
            if (!blank(expert.name())) {
                section.append(" — ").append(expert.name().trim());
            }
            section.append('\n');
            if (!blank(expert.description())) {
                section.append("  职责：").append(expert.description().trim()).append('\n');
            }
            if (!blank(expert.category())) {
                section.append("  分类：").append(expert.category().trim()).append('\n');
            }
        }
        section.append("""
                路由提示：
                - 先阅读各专家的职责与分类，再选择最匹配的 targetExpertId。
                - 不要编造 targetExpertId，只能使用上面列出的 id。
                - 派发规则与协作时序见 skill.brain.routing。
                """);
        return section.toString().trim();
    }

    public String routableExpertsFingerprint() {
        return listRoutableExperts().stream()
                .map(expert -> expert.expertId() + "@" + expert.version() + ":"
                        + nullSafe(expert.name()) + ":" + nullSafe(expert.description()))
                .reduce((left, right) -> left + "|" + right)
                .orElse("");
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
