package com.databuff.apm.web.config;

import com.databuff.apm.web.ai.LlmChatModelFactory;
import com.databuff.apm.web.ai.agent.AgentRuntimeConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * Applies {@code apm.agent.llm-force-http11} to AgentScope LLM transport at startup.
 * Default remains SDK HTTP/2; only when the flag is on do we install HTTP/1.1 (for local
 * cleartext model APIs that reject JDK {@code Upgrade: h2c}).
 */
@Configuration
public class AgentScopeHttpTransportConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeHttpTransportConfiguration.class);

    private final AgentRuntimeConfig agentRuntimeConfig;

    public AgentScopeHttpTransportConfiguration(AgentRuntimeConfig agentRuntimeConfig) {
        this.agentRuntimeConfig = agentRuntimeConfig;
    }

    @PostConstruct
    void applyHttpTransportPreference() {
        boolean forceHttp11 = agentRuntimeConfig.isLlmForceHttp11();
        LlmChatModelFactory.configureForceHttp11(forceHttp11);
        if (forceHttp11) {
            log.info("AgentScope LLM HTTP transport forced to HTTP/1.1 (apm.agent.llm-force-http11=true)");
        }
    }
}
