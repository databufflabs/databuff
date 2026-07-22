package com.databuff.apm.web.ai.platform.runtime;

import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.util.JsonUtils;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NormalizeToolCallArgsMiddlewareTest {

    @Test
    void leavesValidContentUnchanged() {
        ToolUseBlock original = ToolUseBlock.builder()
                .id("call-1")
                .name("echoName")
                .input(Map.of("serviceName", "order-api"))
                .content("{\"serviceName\":\"order-api\"}")
                .build();

        ToolUseBlock aligned = NormalizeToolCallArgsMiddleware.alignContentWithInput(original);

        assertThat(aligned).isSameAs(original);
    }

    @Test
    void backfillsMapToStringContentFromInput() {
        ToolUseBlock original = ToolUseBlock.builder()
                .id("call-1")
                .name("echoName")
                .input(Map.of("serviceName", "order-api"))
                .content("{serviceName=order-api}")
                .build();

        ToolUseBlock aligned = NormalizeToolCallArgsMiddleware.alignContentWithInput(original);

        assertThat(aligned).isNotSameAs(original);
        assertThat(JsonUtils.isValidJsonObject(aligned.getContent())).isTrue();
        assertThat(aligned.getContent()).contains("order-api");
        assertThat(aligned.getInput()).containsEntry("serviceName", "order-api");
        assertThat(aligned.getId()).isEqualTo("call-1");
        assertThat(aligned.getName()).isEqualTo("echoName");
    }

    @Test
    void backfillsEmptyObjectContentWhenInputPresent() {
        ToolUseBlock original = ToolUseBlock.builder()
                .id("call-2")
                .name("echoName")
                .input(Map.of("serviceName", "checkout"))
                .content("{}")
                .build();

        ToolUseBlock aligned = NormalizeToolCallArgsMiddleware.alignContentWithInput(original);

        assertThat(aligned.getContent()).contains("checkout");
        assertThat(NormalizeToolCallArgsMiddleware.contentNeedsInputBackfill(aligned.getContent()))
                .isFalse();
    }

    @Test
    void doesNotTouchEmptyInputEvenWhenContentEmpty() {
        ToolUseBlock original = ToolUseBlock.builder()
                .id("call-3")
                .name("getTime")
                .input(Map.of())
                .content("{}")
                .build();

        assertThat(NormalizeToolCallArgsMiddleware.alignContentWithInput(original))
                .isSameAs(original);
    }
}
