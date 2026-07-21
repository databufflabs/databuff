package com.databuff.apm.web.ai.platform.task;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ExpertMessageContext {

    private ExpertMessageContext() {
    }

    public static String wrapTaskInput(
            String sessionId,
            int roundIndex,
            String taskId,
            String sourceExpertId,
            String body) {
        StringBuilder sb = new StringBuilder();
        appendContextLine(sb, ExpertMessageConstants.CONTEXT_SESSION_PREFIX, sessionId);
        appendContextLine(sb, ExpertMessageConstants.CONTEXT_ROUND_PREFIX, String.valueOf(roundIndex));
        appendContextLine(sb, ExpertMessageConstants.CONTEXT_TASK_PREFIX, taskId);
        appendContextLine(sb, ExpertMessageConstants.CONTEXT_SOURCE_EXPERT_PREFIX, sourceExpertId);
        sb.append(body == null ? "" : body.trim());
        return sb.toString();
    }

    public static String wrapBrainContinuation(
            String sessionId,
            int roundIndex,
            String taskId,
            String targetExpertId,
            String text,
            boolean failure) {
        return wrapBrainContinuation(sessionId, roundIndex, taskId, targetExpertId, text, failure, true);
    }

    /**
     * @param allAsyncComplete after pending-1 for this response, whether session pending reached 0
     */
    public static String wrapBrainContinuation(
            String sessionId,
            int roundIndex,
            String taskId,
            String targetExpertId,
            String text,
            boolean failure,
            boolean allAsyncComplete) {
        String header = failure
                ? "[数字专家 " + targetExpertId + " · taskId=" + taskId + " · 失败]\n---\n"
                : "[数字专家 " + targetExpertId + " · taskId=" + taskId + " · 已完成]\n---\n";
        String body = text == null ? "" : text;
        return wrapTaskInput(sessionId, roundIndex, taskId, targetExpertId, header + body)
                + ExpertMessageConstants.expertResultContinueHint(failure, allAsyncComplete);
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

    private static void appendContextLine(StringBuilder sb, String prefix, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sb.append(prefix).append(value.trim()).append("]\n");
    }

    private static void putIfNotBlank(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value.trim());
        }
    }
}
