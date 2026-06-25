package com.databuff.apm.web.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiLlmProviderPropertiesTest {

    @Test
    void defaultsToMaskingApiKey() {
        assertThat(new AiLlmProviderProperties().maskApiKey()).isTrue();
    }

    @Test
    void allowsDisablingMask() {
        AiLlmProviderProperties properties = new AiLlmProviderProperties();
        properties.setMaskApiKey(false);
        assertThat(properties.maskApiKey()).isFalse();
    }
}
