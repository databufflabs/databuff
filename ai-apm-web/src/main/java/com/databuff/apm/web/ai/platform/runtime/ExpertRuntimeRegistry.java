package com.databuff.apm.web.ai.platform.runtime;

import com.databuff.apm.web.ai.InMemoryLlmProviderStore;
import com.databuff.apm.web.ai.OpenAiCompatibleChatClient;
import com.databuff.apm.web.ai.platform.expert.AiExpertDefinition;
import com.databuff.apm.web.ai.platform.expert.BrainRoutingCatalog;
import com.databuff.apm.web.ai.platform.expert.ExpertManagementService;
import com.databuff.apm.web.ai.platform.skill.AiSkillDefinition;
import com.databuff.apm.web.ai.platform.skill.SkillManagementService;
import com.databuff.apm.web.ai.platform.tool.AiToolDefinition;
import com.databuff.apm.web.ai.platform.tool.ExpertToolResolver;
import com.databuff.apm.web.ai.platform.tool.ToolManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@Lazy
public class ExpertRuntimeRegistry {

    private static final Logger log = LoggerFactory.getLogger(ExpertRuntimeRegistry.class);

    private final ExpertManagementService expertManagementService;
    private final ToolManagementService toolManagementService;
    private final SkillManagementService skillManagementService;
    private final InMemoryLlmProviderStore llmProviderStore;
    private final ExpertToolResolver expertToolResolver;
    private final AgentScopeRuntimeAdapter runtimeAdapter;
    private final BrainRoutingCatalog brainRoutingCatalog;
    private final ObjectProvider<SessionExpertRuntimeRegistry> sessionExpertRuntimeRegistry;
    private final ConcurrentMap<String, CachedRuntime> runtimes = new ConcurrentHashMap<>();

    public ExpertRuntimeRegistry(
            ExpertManagementService expertManagementService,
            ToolManagementService toolManagementService,
            SkillManagementService skillManagementService,
            InMemoryLlmProviderStore llmProviderStore,
            ExpertToolResolver expertToolResolver,
            AgentScopeRuntimeAdapter runtimeAdapter,
            BrainRoutingCatalog brainRoutingCatalog,
            ObjectProvider<SessionExpertRuntimeRegistry> sessionExpertRuntimeRegistry) {
        this.expertManagementService = expertManagementService;
        this.toolManagementService = toolManagementService;
        this.skillManagementService = skillManagementService;
        this.llmProviderStore = llmProviderStore;
        this.expertToolResolver = expertToolResolver;
        this.runtimeAdapter = runtimeAdapter;
        this.brainRoutingCatalog = brainRoutingCatalog;
        this.sessionExpertRuntimeRegistry = sessionExpertRuntimeRegistry;
    }

    public ExpertRuntime getOrCreate(String expertId) {
        AiExpertDefinition expert = expertManagementService.find(expertId)
                .orElseThrow(() -> new IllegalArgumentException("expert not found: " + expertId));
        if (!expert.enabled()) {
            throw new IllegalStateException("expert is disabled: " + expertId);
        }
        RuntimeCacheKey expectedKey = computeCacheKey(expert);
        CachedRuntime cached = runtimes.get(expertId);
        if (cached != null && cached.cacheKey.fingerprint().equals(expectedKey.fingerprint())) {
            return cached.runtime;
        }
        synchronized (lockFor(expertId)) {
            cached = runtimes.get(expertId);
            if (cached != null && cached.cacheKey.fingerprint().equals(expectedKey.fingerprint())) {
                return cached.runtime;
            }
            if (cached != null) {
                cached.runtime.close();
            }
            ExpertRuntime runtime = runtimeAdapter.buildRuntime(expert);
            RuntimeCacheKey actualKey = runtime instanceof AgentScopeExpertRuntime scoped
                    ? scoped.cacheKey()
                    : expectedKey;
            runtimes.put(expertId, new CachedRuntime(actualKey, runtime));
            log.info("Created runtime for expert {} with cache key {}", expertId, actualKey.fingerprint());
            return runtime;
        }
    }

    public void invalidate(String expertId, String reason) {
        CachedRuntime removed = runtimes.remove(expertId);
        if (removed != null) {
            removed.runtime.close();
            log.info("Invalidated runtime for expert {}: {}", expertId, reason);
        }
        SessionExpertRuntimeRegistry sessionRegistry = sessionExpertRuntimeRegistry.getIfAvailable();
        if (sessionRegistry != null) {
            sessionRegistry.releaseByExpert(expertId);
        }
    }

    public void invalidateByTool(String toolId) {
        expertManagementService.list().stream()
                .filter(expert -> expert.toolIds().contains(toolId))
                .map(AiExpertDefinition::expertId)
                .forEach(id -> invalidate(id, "tool changed: " + toolId));
    }

    public void invalidateBySkill(String skillId) {
        expertManagementService.list().stream()
                .filter(expert -> expert.skillIds().contains(skillId))
                .map(AiExpertDefinition::expertId)
                .forEach(id -> invalidate(id, "skill changed: " + skillId));
    }

    public void invalidateByProvider(String providerCode) {
        expertManagementService.list().stream()
                .filter(expert -> providerCode == null
                        || providerCode.isBlank()
                        || providerCode.equals(expert.modelProviderCode())
                        || expert.modelProviderCode() == null
                        || expert.modelProviderCode().isBlank())
                .map(AiExpertDefinition::expertId)
                .forEach(id -> invalidate(id, "provider changed: " + providerCode));
    }

    public List<ExpertRuntimeStatus> listStatus() {
        List<ExpertRuntimeStatus> statuses = new ArrayList<>();
        for (AiExpertDefinition expert : expertManagementService.list()) {
            CachedRuntime cached = runtimes.get(expert.expertId());
            if (cached == null) {
                statuses.add(ExpertRuntimeStatus.notLoaded(expert.expertId()));
            } else {
                statuses.add(cached.runtime.status());
            }
        }
        statuses.sort(Comparator.comparing(ExpertRuntimeStatus::expertId));
        return statuses;
    }

    private RuntimeCacheKey computeCacheKey(AiExpertDefinition expert) {
        List<AiToolDefinition> tools = expertToolResolver.resolve(expert);
        List<AiSkillDefinition> skills = expert.skillIds().stream()
                .map(skillManagementService::find)
                .flatMap(java.util.Optional::stream)
                .filter(AiSkillDefinition::enabled)
                .toList();
        OpenAiCompatibleChatClient.ResolvedLlmProvider provider = runtimeAdapter.resolveProvider(expert)
                .orElse(null);
        String providerCode = provider == null ? "default" : provider.providerCode();
        long providerVersion = provider == null ? 0L : llmProviderStore.providerVersion(providerCode);
        String routingCatalogHash = "brain".equals(expert.expertId())
                ? brainRoutingCatalog.routableExpertsFingerprint()
                : "";
        return RuntimeCacheKey.of(expert, tools, skills, providerCode, providerVersion, routingCatalogHash);
    }

    private Object lockFor(String expertId) {
        return ("expert-runtime-lock:" + expertId).intern();
    }

    private record CachedRuntime(RuntimeCacheKey cacheKey, ExpertRuntime runtime) {
    }
}
