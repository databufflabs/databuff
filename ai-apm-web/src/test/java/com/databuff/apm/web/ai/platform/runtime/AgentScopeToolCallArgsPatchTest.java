package com.databuff.apm.web.ai.platform.runtime;

import io.agentscope.core.agent.accumulator.ToolCallsAccumulator;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression: AgentScope 2.0.0 validated tool args via {@code ToolUseBlock.content} while
 * invocation used {@code input}. Anthropic non-stream (e.g. MiniMax) often left content as
 * Map.toString() / "{}", causing {@code required property not found}. DataBuff classpath
 * patches fix accumulator + executor.
 */
class AgentScopeToolCallArgsPatchTest {

    public static class EchoTools {
        @Tool(description = "Echo a required name")
        public String echoName(
                @ToolParam(name = "serviceName", description = "Required service name")
                String serviceName) {
            return "ok:" + serviceName;
        }
    }

    @Test
    void accumulatorBackfillsContentFromInputWhenRawContentIsMapToString() {
        ToolCallsAccumulator accumulator = new ToolCallsAccumulator();
        accumulator.add(ToolUseBlock.builder()
                .id("call-1")
                .name("echoName")
                .input(Map.of("serviceName", "order-api"))
                .content("{serviceName=order-api}")
                .build());

        List<ToolUseBlock> built = accumulator.buildAllToolCalls();
        assertThat(built).hasSize(1);
        ToolUseBlock block = built.get(0);
        assertThat(block.getInput()).containsEntry("serviceName", "order-api");
        assertThat(JsonUtils.isValidJsonObject(block.getContent())).isTrue();
        assertThat(block.getContent()).contains("order-api");
    }

    @Test
    void toolkitExecutesWhenContentEmptyButInputHasRequiredArgs() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new EchoTools());

        ToolUseBlock toolUse = ToolUseBlock.builder()
                .id("call-2")
                .name("echoName")
                .input(Map.of("serviceName", "checkout"))
                .content("{}")
                .build();

        ToolResultBlock result = toolkit.callTool(ToolCallParam.builder()
                        .toolUseBlock(toolUse)
                        .input(toolUse.getInput())
                        .build())
                .block();

        assertThat(result).isNotNull();
        String output = String.valueOf(result.getOutput());
        assertThat(output)
                .doesNotContain("Parameter validation failed")
                .doesNotContain("required property")
                .contains("ok:checkout");
    }
}
