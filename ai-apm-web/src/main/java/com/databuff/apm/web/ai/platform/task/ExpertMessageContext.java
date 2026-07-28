package com.databuff.apm.web.ai.platform.task;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ExpertMessageContext {

    private ExpertMessageContext() {
    }

    /**
     * Specialist user message: task body only. Session/task ids stay in metadata, not model text.
     */
    public static String wrapTaskInput(
            String sessionId,
            int roundIndex,
            String taskId,
            String sourceExpertId,
            String body) {
        return body == null ? "" : body.trim();
    }

    public static String wrapBrainContinuation(
            String sessionId,
            int roundIndex,
            String taskId,
            String targetExpertId,
            String text,
            boolean failure) {
        return wrapBrainContinuation(sessionId, roundIndex, taskId, targetExpertId, text, failure, true, null);
    }

    /**
     * @param allAsyncComplete unused; kept for call-site compatibility. Wait/finalize is runtime-owned.
     */
    public static String wrapBrainContinuation(
            String sessionId,
            int roundIndex,
            String taskId,
            String targetExpertId,
            String text,
            boolean failure,
            boolean allAsyncComplete) {
        return wrapBrainContinuation(
                sessionId, roundIndex, taskId, targetExpertId, text, failure, allAsyncComplete, null);
    }

    /**
     * Deliver expert result to brain: content only, no protocol coaching.
     *
     * @param allAsyncComplete unused; kept for call-site compatibility
     * @param originalUserRequest optional verbatim user message for this round (context fact only)
     */
    public static String wrapBrainContinuation(
            String sessionId,
            int roundIndex,
            String taskId,
            String targetExpertId,
            String text,
            boolean failure,
            boolean allAsyncComplete,
            String originalUserRequest) {
        String header = failure
                ? "[数字专家 " + targetExpertId + " · taskId=" + taskId + " · 失败]\n---\n"
                : "[数字专家 " + targetExpertId + " · taskId=" + taskId + " · 已完成]\n---\n";
        String body = text == null ? "" : text;
        StringBuilder sb = new StringBuilder();
        sb.append(header).append(body);
        if (originalUserRequest != null && !originalUserRequest.isBlank()) {
            sb.append("\n\n[本轮用户原请求]\n").append(originalUserRequest.trim());
        }
        return sb.toString();
    }

    public static Map<String, Object> taskMetadata(
            String sessionId,
            int roundIndex,
            String taskId,
            String sourceExpertId,
            String runtimeSessionId) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfNotBlank(metadata, ExpertMessageConstants.META_SESSION_ID, sessionId);
        metadata.put(ExpertMessageConstants.META_ROUND_INDEX, roundIndex);
        putIfNotBlank(metadata, ExpertMessageConstants.META_TASK_ID, taskId);
        putIfNotBlank(metadata, ExpertMessageConstants.META_SOURCE_EXPERT_ID, sourceExpertId);
        putIfNotBlank(metadata, ExpertMessageConstants.META_RUNTIME_SESSION_ID, runtimeSessionId);
        metadata.put(ExpertMessageConstants.META_TRIGGER_SOURCE, ExpertMessageConstants.TRIGGER_EXPERT_DISPATCH);
        return Map.copyOf(metadata);
    }

    private static void putIfNotBlank(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value.trim());
        }
    }
}
