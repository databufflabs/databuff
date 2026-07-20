package com.databuff.apm.web.ai.platform.runtime;

import com.databuff.apm.web.ai.InMemoryLlmProviderStore;
import com.databuff.apm.web.ai.OpenAiCompatibleChatClient;
import com.databuff.apm.web.ai.platform.expert.AiExpertDefinition;
import com.databuff.apm.web.ai.platform.expert.BrainRoutingCatalog;
import com.databuff.apm.web.ai.platform.skill.AiSkillDefinition;
import com.databuff.apm.web.ai.platform.skill.SkillManagementService;
import com.databuff.apm.web.ai.platform.tool.AiToolDefinition;
import com.databuff.apm.web.ai.platform.tool.ExpertToolResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Keeps one AgentScope runtime per {@code (chatSessionId, expertId)} so multi-turn chat and
 * serial async dispatches to the same expert reuse memory via {@code stateStore}.
 * <p>
 * ChatScope / TaskContext stay on {@code sessionId#task:{taskId}} keys; AgentScope memory uses the
 * logical chat session id on the per-expert agent instance.
 */
@Service
@Lazy
public class SessionExpertRuntimeRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionExpertRuntimeRegistry.class);

    private final SkillManagementService skillManagementService;
    private final InMemoryLlmProviderStore llmProviderStore;
    private final ExpertToolResolver expertToolResolver;
    private final AgentScopeRuntimeAdapter runtimeAdapter;
    private final BrainRoutingCatalog brainRoutingCatalog;
    private final ConcurrentMap<String, CachedSessionRuntime> runtimes = new ConcurrentHashMap<>();

    public SessionExpertRuntimeRegistry(
            SkillManagementService skillManagementService,
            InMemoryLlmProviderStore llmProviderStore,
            ExpertToolResolver expertToolResolver,
            AgentScopeRuntimeAdapter runtimeAdapter,
            BrainRoutingCatalog brainRoutingCatalog) {
        this.skillManagementService = skillManagementService;
        this.llmProviderStore = llmProviderStore;
        this.expertToolResolver = expertToolResolver;
        this.runtimeAdapter = runtimeAdapter;
        this.brainRoutingCatalog = brainRoutingCatalog;
    }

    public ExpertRuntime getOrCreate(String sessionId, AiExpertDefinition expert) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required for session-scoped runtime");
        }
        if (expert == null || expert.expertId() == null || expert.expertId().isBlank()) {
            throw new IllegalArgumentException("expert is required for session-scoped runtime");
        }
        if (!expert.enabled()) {
            throw new IllegalStateException("expert is disabled: " + expert.expertId());
        }
        String normalizedSessionId = ExpertChatScopeRegistry.parentSessionId(sessionId.trim());
        if (normalizedSessionId == null) {
            normalizedSessionId = sessionId.trim();
        }
        String expertId = expert.expertId().trim();
        String cacheEntryKey = cacheEntryKey(normalizedSessionId, expertId);
        RuntimeCacheKey expectedKey = computeCacheKey(expert);
        CachedSessionRuntime cached = runtimes.get(cacheEntryKey);
        if (cached != null && cached.cacheKey.fingerprint().equals(expectedKey.fingerprint())) {
            return cached.runtime;
        }
        synchronized (lockFor(cacheEntryKey)) {
            cached = runtimes.get(cacheEntryKey);
            if (cached != null && cached.cacheKey.fingerprint().equals(expectedKey.fingerprint())) {
                return cached.runtime;
            }
            if (cached != null) {
                cached.runtime.close();
            }
            ExpertRuntime runtime = runtimeAdapter.buildSessionRuntime(expert, normalizedSessionId);
            RuntimeCacheKey actualKey = runtime instanceof AgentScopeExpertRuntime scoped
                    ? scoped.cacheKey()
                    : expectedKey;
            runtimes.put(cacheEntryKey, new CachedSessionRuntime(normalizedSessionId, expertId, actualKey, runtime));
            log.info("Created session-scoped runtime for session {} expert {} with cache key {}",
                    normalizedSessionId, expertId, actualKey.fingerprint());
            return runtime;
        }
    }

    public void release(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String normalizedSessionId = ExpertChatScopeRegistry.parentSessionId(sessionId.trim());
        if (normalizedSessionId == null) {
            normalizedSessionId = sessionId.trim();
        }
        List<String> keys = new ArrayList<>();
        for (var entry : runtimes.entrySet()) {
            if (normalizedSessionId.equals(entry.getValue().sessionId())) {
                keys.add(entry.getKey());
            }
        }
        for (String key : keys) {
            CachedSessionRuntime removed = runtimes.remove(key);
            if (removed != null) {
                removed.runtime.close();
                log.info("Released session-scoped runtime for session {} expert {}",
                        removed.sessionId(), removed.expertId());
            }
        }
    }

    public void release(String sessionId, String expertId) {
        if (sessionId == null || sessionId.isBlank() || expertId == null || expertId.isBlank()) {
            return;
        }
        String normalizedSessionId = ExpertChatScopeRegistry.parentSessionId(sessionId.trim());
        if (normalizedSessionId == null) {
            normalizedSessionId = sessionId.trim();
        }
        String key = cacheEntryKey(normalizedSessionId, expertId.trim());
        CachedSessionRuntime removed = runtimes.remove(key);
        if (removed != null) {
            removed.runtime.close();
            log.info("Released session-scoped runtime for session {} expert {}",
                    removed.sessionId(), removed.expertId());
        }
    }

    public void releaseByExpert(String expertId) {
        if (expertId == null || expertId.isBlank()) {
            return;
        }
        String normalizedExpertId = expertId.trim();
        List<String> keys = new ArrayList<>();
        for (var entry : runtimes.entrySet()) {
            if (normalizedExpertId.equals(entry.getValue().expertId())) {
                keys.add(entry.getKey());
            }
        }
        for (String key : keys) {
            CachedSessionRuntime removed = runtimes.remove(key);
            if (removed != null) {
                removed.runtime.close();
                log.info("Released session-scoped runtime for session {} expert {} (expert invalidated)",
                        removed.sessionId(), removed.expertId());
            }
        }
    }

    public void releaseAll() {
        for (String key : List.copyOf(runtimes.keySet())) {
            CachedSessionRuntime removed = runtimes.remove(key);
            if (removed != null) {
                removed.runtime.close();
            }
        }
    }

    private RuntimeCacheKey computeCacheKey(AiExpertDefinition expert) {
        List<AiToolDefinition> tools = expertToolResolver.resolve(expert);
        java.util.List<AiSkillDefinition> skills = expert.skillIds().stream()
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

    static String cacheEntryKey(String sessionId, String expertId) {
        return sessionId + '\0' + expertId;
    }

    private Object lockFor(String cacheEntryKey) {
        return ("session-expert-runtime-lock:" + cacheEntryKey).intern();
    }

    private record CachedSessionRuntime(
            String sessionId,
            String expertId,
            RuntimeCacheKey cacheKey,
            ExpertRuntime runtime) {
    }
}
