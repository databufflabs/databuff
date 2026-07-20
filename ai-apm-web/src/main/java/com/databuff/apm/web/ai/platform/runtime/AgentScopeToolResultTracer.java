package com.databuff.apm.web.ai.platform.runtime;

import com.databuff.apm.web.ai.platform.task.ExpertMessageConstants;
import com.databuff.apm.web.ai.platform.task.ExpertSessionResolver;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tracing.Tracer;
import io.agentscope.core.tracing.TracerRegistry;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.function.Supplier;

@Component
public class AgentScopeToolResultTracer implements Tracer {

    private final AgentScopeSessionHook sessionHook;

    public AgentScopeToolResultTracer(AgentScopeSessionHook sessionHook) {
        this.sessionHook = sessionHook;
    }

    @PostConstruct
    void registerTracer() {
        TracerRegistry.register(this);
    }

    @Override
    public Mono<ToolResultBlock> callTool(
            Toolkit toolkit,
            ToolCallParam param,
            Supplier<Mono<ToolResultBlock>> next) {
        long startedAtMs = System.currentTimeMillis();
        return next.get().doOnNext(result -> capture(param, result, startedAtMs));
    }

    private void capture(ToolCallParam param, ToolResultBlock result, long startedAtMs) {
        if (param == null || result == null) {
            return;
        }
        long durationMs = Math.max(0L, System.currentTimeMillis() - startedAtMs);
        // Prefer task-scoped ChatScope key so capture matches TraceRecorder; AgentScope memory
        // uses the logical session id on RuntimeContext.getSessionId().
        String captureSessionId = chatScopeSessionId(param.getRuntimeContext());
        if (captureSessionId == null || captureSessionId.isBlank()) {
            captureSessionId = ExpertSessionResolver.sessionIdFromRuntimeContext(param.getRuntimeContext())
                    .orElse("");
        }
        sessionHook.captureToolResult(captureSessionId, param.getToolUseBlock(), result, durationMs);
    }

    private static String chatScopeSessionId(RuntimeContext runtimeContext) {
        if (runtimeContext == null) {
            return null;
        }
        Object scoped = runtimeContext.get(ExpertMessageConstants.META_RUNTIME_SESSION_ID);
        if (scoped != null && !String.valueOf(scoped).isBlank()) {
            return String.valueOf(scoped).trim();
        }
        String sessionId = runtimeContext.getSessionId();
        return sessionId == null || sessionId.isBlank() ? null : sessionId.trim();
    }
}
