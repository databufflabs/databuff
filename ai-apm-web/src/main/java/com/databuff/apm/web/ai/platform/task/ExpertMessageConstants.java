package com.databuff.apm.web.ai.platform.task;

public final class ExpertMessageConstants {

    public static final String META_SESSION_ID = "sessionId";
    public static final String META_ROUND_INDEX = "roundIndex";
    public static final String META_TASK_ID = "taskId";
    public static final String META_SOURCE_EXPERT_ID = "sourceExpertId";
    public static final String META_TRIGGER_SOURCE = "triggerSource";
    public static final String META_RUNTIME_SESSION_ID = "runtimeSessionId";
    public static final String META_IS_EXPERT_DELIVERABLE = "isExpertDeliverable";
    public static final String META_IS_ROUND_FINAL = "isRoundFinal";

    public static final String TRIGGER_USER = "user";
    public static final String TRIGGER_EXPERT_DISPATCH = "expert_dispatch";
    public static final String TRIGGER_EXPERT_RESULT = "expert_result";
    public static final String TRIGGER_BRAIN_CONTINUE = "brain_continue";

    private ExpertMessageConstants() {
    }

    /** Neutral tool result after dispatch is accepted. No wait/protocol coaching. */
    public static String asyncWaitMessage(String taskId, String targetExpertId) {
        return "已派出：targetExpertId=" + targetExpertId + "，taskId=" + taskId;
    }

    /** Neutral tool result when the same target expert already has an in-flight task. */
    public static String serialDispatchBusyMessage(String taskId, String targetExpertId) {
        return "该专家当前占用中：targetExpertId=" + targetExpertId + "，进行中 taskId=" + taskId;
    }
}
