package com.databuff.apm.web.ai.platform.runtime;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Aligns {@link ToolUseBlock#getContent()} with {@link ToolUseBlock#getInput()} before toolkit
 * schema validation.
 *
 * <p>AgentScope 2.0.0 validates tool args via {@code content} but invokes with {@code input}. Some
 * providers (e.g. Anthropic-compatible non-stream) leave {@code content} as Map.toString(),
 * {@code "{}"}, or invalid JSON while {@code input} already holds the parsed map — causing
 * {@code required property not found} for every required tool. This middleware only rewrites when
 * content is missing, invalid, or an empty object and input is non-empty.
 */
public final class NormalizeToolCallArgsMiddleware implements MiddlewareBase {

    public static final NormalizeToolCallArgsMiddleware INSTANCE =
            new NormalizeToolCallArgsMiddleware();

    private static final Logger log = LoggerFactory.getLogger(NormalizeToolCallArgsMiddleware.class);

    private NormalizeToolCallArgsMiddleware() {
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        if (input == null || input.toolCalls() == null || input.toolCalls().isEmpty()) {
            return next.apply(input);
        }
        List<ToolUseBlock> original = input.toolCalls();
        List<ToolUseBlock> aligned = new ArrayList<>(original.size());
        boolean changed = false;
        for (ToolUseBlock toolUse : original) {
            ToolUseBlock nextBlock = alignContentWithInput(toolUse);
            aligned.add(nextBlock);
            if (nextBlock != toolUse) {
                changed = true;
            }
        }
        return next.apply(changed ? new ActingInput(List.copyOf(aligned)) : input);
    }

    /**
     * When {@code input} has args but {@code content} cannot be used for schema validation,
     * rebuild the block with content serialized from input.
     */
    static ToolUseBlock alignContentWithInput(ToolUseBlock toolUse) {
        if (toolUse == null) {
            return null;
        }
        Map<String, Object> args = toolUse.getInput();
        if (args == null || args.isEmpty()) {
            return toolUse;
        }
        if (!contentNeedsInputBackfill(toolUse.getContent())) {
            return toolUse;
        }
        String json = serializeArgs(args);
        if (json.equals(toolUse.getContent())) {
            return toolUse;
        }
        log.debug(
                "Aligning tool '{}' content with input ({} keys)",
                toolUse.getName(),
                args.size());
        ToolUseBlock.Builder builder = ToolUseBlock.builder()
                .id(toolUse.getId())
                .name(toolUse.getName())
                .input(args)
                .content(json)
                .state(toolUse.getState());
        Map<String, Object> metadata = toolUse.getMetadata();
        if (metadata != null && !metadata.isEmpty()) {
            builder.metadata(metadata);
        }
        return builder.build();
    }

    static boolean contentNeedsInputBackfill(String content) {
        if (content == null || content.isBlank()) {
            return true;
        }
        if (!JsonUtils.isValidJsonObject(content)) {
            return true;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = JsonUtils.getJsonCodec().fromJson(content, Map.class);
            return parsed == null || parsed.isEmpty();
        } catch (Exception ignored) {
            return true;
        }
    }

    private static String serializeArgs(Map<String, Object> args) {
        try {
            return JsonUtils.getJsonCodec().toJson(args);
        } catch (Exception e) {
            log.warn("Failed to serialize tool input args: {}", e.getMessage());
            return "{}";
        }
    }
}
