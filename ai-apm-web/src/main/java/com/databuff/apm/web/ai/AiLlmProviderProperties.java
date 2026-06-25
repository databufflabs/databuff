package com.databuff.apm.web.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "apm.ai.provider")
public class AiLlmProviderProperties {

    /** When true, provider detail API omits stored API keys from responses. */
    private boolean maskApiKey = true;

    public boolean maskApiKey() {
        return maskApiKey;
    }

    public void setMaskApiKey(boolean maskApiKey) {
        this.maskApiKey = maskApiKey;
    }
}
