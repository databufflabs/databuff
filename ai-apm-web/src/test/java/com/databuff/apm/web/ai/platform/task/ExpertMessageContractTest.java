package com.databuff.apm.web.ai.platform.task;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Strict contract for brain↔expert visible text: content delivery only, no protocol coaching.
 */
public class ExpertMessageContractTest {

    /** Phrases that must never appear in model-facing dispatch/continuation text. */
    public static final List<String> PROTOCOL_COACHING_BANNED = List.of(
            "请静静等待",
            "内部通道",
            "系统注入",
            "pending=0",
            "pending>0",
            "pending=",
            "[系统]",
            "勿输出最终",
            "禁止输出最终",
            "尚未完成的子目标",
            "禁止并行重复派发",
            "稍后再试",
            "前端可能折叠",
            "allAsyncComplete",
            "禁止声称");

    public static final List<String> CONTEXT_HEADER_BANNED = List.of(
            "[Context: sessionId=",
            "[Context: roundIndex=",
            "[Context: taskId=",
            "[Context: sourceExpertId=");

    @Test
    void dispatchAcceptedReceipt_isNeutralStatus_alarmsScenario() {
        String receipt = ExpertMessageConstants.asyncWaitMessage("task-alarm-1", "data");

        assertThat(receipt).isEqualTo("已派出：targetExpertId=data，taskId=task-alarm-1");
        assertNoBanned(receipt, PROTOCOL_COACHING_BANNED);
    }

    @Test
    void dispatchAcceptedReceipt_isNeutralStatus_topologyScenario() {
        String receipt = ExpertMessageConstants.asyncWaitMessage("task-topo-2", "inspection");

        assertThat(receipt)
                .isEqualTo("已派出：targetExpertId=inspection，taskId=task-topo-2")
                .doesNotContain("异步任务已受理");
        assertNoBanned(receipt, PROTOCOL_COACHING_BANNED);
    }

    @Test
    void dispatchBusyReceipt_isNeutralStatus_sameDataExpert() {
        String receipt = ExpertMessageConstants.serialDispatchBusyMessage("task-in-flight", "data");

        assertThat(receipt)
                .isEqualTo("该专家当前占用中：targetExpertId=data，进行中 taskId=task-in-flight");
        assertNoBanned(receipt, PROTOCOL_COACHING_BANNED);
    }

    @Test
    void dispatchBusyReceipt_isNeutralStatus_sameOpsExpert() {
        String receipt = ExpertMessageConstants.serialDispatchBusyMessage("ops-busy-9", "ops");

        assertThat(receipt)
                .startsWith("该专家当前占用中：")
                .contains("targetExpertId=ops")
                .contains("进行中 taskId=ops-busy-9")
                .doesNotContain("请等待");
        assertNoBanned(receipt, PROTOCOL_COACHING_BANNED);
    }

    @Test
    void specialistInput_isTaskBodyOnly_alarmAnalysis() {
        String wrapped = ExpertMessageContext.wrapTaskInput(
                "sess-1", 1, "task-1", "brain", "分析当前的告警情况，查询所有活跃告警");

        assertThat(wrapped).isEqualTo("分析当前的告警情况，查询所有活跃告警");
        assertNoBanned(wrapped, CONTEXT_HEADER_BANNED);
        assertThat(wrapped).doesNotContain("sourceExpertId");
    }

    @Test
    void specialistInput_isTaskBodyOnly_businessImpact() {
        String wrapped = ExpertMessageContext.wrapTaskInput(
                "sess-2",
                2,
                "task-2",
                "brain",
                "查询 service-a 拓扑与调用量，判断哪条告警影响业务");

        assertThat(wrapped)
                .isEqualTo("查询 service-a 拓扑与调用量，判断哪条告警影响业务")
                .doesNotContain("sessionId=")
                .doesNotContain("roundIndex=");
        assertNoBanned(wrapped, CONTEXT_HEADER_BANNED);
    }

    @Test
    void brainWake_success_deliversExpertBodyAndUserRequest_noCoaching() {
        String wake = ExpertMessageContext.wrapBrainContinuation(
                "sess-1",
                1,
                "task-data-1",
                "data",
                "## 告警报告\n共 3 个服务存在活跃告警",
                false,
                true,
                "分析一下当前的告警");

        assertThat(wake)
                .startsWith("[数字专家 data · taskId=task-data-1 · 已完成]\n---\n")
                .contains("## 告警报告\n共 3 个服务存在活跃告警")
                .endsWith("[本轮用户原请求]\n分析一下当前的告警")
                .contains("\n\n[本轮用户原请求]\n")
                .doesNotContain("够就")
                .doesNotContain("再 dispatch");
        assertNoBanned(wake, PROTOCOL_COACHING_BANNED);
        assertNoBanned(wake, CONTEXT_HEADER_BANNED);
    }

    @Test
    void brainWake_failure_deliversErrorBodyAndUserRequest_noCoaching() {
        String wake = ExpertMessageContext.wrapBrainContinuation(
                "sess-2",
                1,
                "task-insp-1",
                "inspection",
                "inspection工具连接超时",
                true,
                true,
                "哪条告警影响业务");

        assertThat(wake)
                .startsWith("[数字专家 inspection · taskId=task-insp-1 · 失败]\n---\n")
                .contains("inspection工具连接超时")
                .endsWith("[本轮用户原请求]\n哪条告警影响业务")
                .doesNotContain("已完成")
                .doesNotContain("列出尚未完成");
        assertNoBanned(wake, PROTOCOL_COACHING_BANNED);
        assertNoBanned(wake, CONTEXT_HEADER_BANNED);
    }

    @Test
    void brainWake_withoutUserRequest_omitsUserBlock_stillNoCoaching() {
        String wake = ExpertMessageContext.wrapBrainContinuation(
                "sess-3", 1, "task-ops-1", "ops", "磁盘使用率 42%", false, false, null);

        assertThat(wake)
                .isEqualTo("[数字专家 ops · taskId=task-ops-1 · 已完成]\n---\n磁盘使用率 42%")
                .doesNotContain("本轮用户原请求");
        assertNoBanned(wake, PROTOCOL_COACHING_BANNED);
    }

    @Test
    void brainWake_allAsyncCompleteFlag_doesNotLeakIntoText() {
        String pendingStill = ExpertMessageContext.wrapBrainContinuation(
                "s", 1, "t1", "data", "部分结果A", false, false, "并行排查");
        String pendingDone = ExpertMessageContext.wrapBrainContinuation(
                "s", 1, "t2", "ops", "部分结果B", false, true, "并行排查");

        assertThat(pendingStill).doesNotContain("pending");
        assertThat(pendingDone).doesNotContain("pending");
        assertThat(pendingStill).contains("部分结果A").contains("本轮用户原请求");
        assertThat(pendingDone).contains("部分结果B").contains("本轮用户原请求");
        assertThat(pendingStill.replace("部分结果A", "<BODY>").replace("taskId=t1", "taskId=<ID>")
                .replace(" data ", " <EXPERT> "))
                .isEqualTo(pendingDone.replace("部分结果B", "<BODY>").replace("taskId=t2", "taskId=<ID>")
                        .replace(" ops ", " <EXPERT> "));
    }

    public static void assertNoBanned(String text, List<String> banned) {
        for (String phrase : banned) {
            assertThat(text)
                    .as("must not contain protocol/coaching phrase: %s", phrase)
                    .doesNotContain(phrase);
        }
    }
}
