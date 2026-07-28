package com.databuff.apm.web.ai.platform.task;

import com.databuff.apm.web.ai.OpenAiCompatibleChatClient;
import com.databuff.apm.web.ai.TestAiSupport;
import com.databuff.apm.web.ai.TestBeanSupport;
import com.databuff.apm.web.ai.UpdateLlmProviderRequest;
import com.databuff.apm.web.ai.agent.AiChatOrchestrator;
import com.databuff.apm.web.ai.agent.AiRuntimeForwarder;
import com.databuff.apm.web.ai.agent.AiRuntimeRouter;
import com.databuff.apm.web.ai.agent.AiSessionStore;
import com.databuff.apm.web.ai.platform.BuiltInExpertCatalog;
import com.databuff.apm.web.ai.platform.expert.AiExpertDefinition;
import com.databuff.apm.web.ai.platform.expert.BrainRoutingCatalog;
import com.databuff.apm.web.ai.platform.runtime.ExpertChatInput;
import com.databuff.apm.web.ai.platform.runtime.ExpertChatResult;
import com.databuff.apm.web.ai.platform.runtime.ExpertRuntime;
import com.databuff.apm.web.ai.platform.runtime.ExpertRuntimeEvent;
import com.databuff.apm.web.ai.platform.runtime.ExpertRuntimeRegistry;
import com.databuff.apm.web.ai.platform.runtime.SessionExpertRuntimeRegistry;
import com.databuff.apm.web.ai.tool.ApmToolkit;
import com.databuff.apm.web.support.WebTestClusterSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.databuff.apm.web.ai.platform.task.ExpertMessageContractTest.CONTEXT_HEADER_BANNED;
import static com.databuff.apm.web.ai.platform.task.ExpertMessageContractTest.PROTOCOL_COACHING_BANNED;
import static com.databuff.apm.web.ai.platform.task.ExpertMessageContractTest.assertNoBanned;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration coverage for brain↔expert interaction contract after protocol-prompt cleanup:
 * neutral receipts, content-only wakeups, specialist task-body-only inputs, catalog/skill hygiene.
 */
class BrainExpertInteractionContractIntegrationTest {

    @TempDir
    Path tempDir;

    private AiSessionStore sessionStore;
    private ExpertTaskPendingRegistry pendingRegistry;
    private ExpertTaskService taskService;
    private AiChatOrchestrator orchestrator;
    private TestAiSupport.PlatformRuntimeFixture fixture;

    private final List<String> brainPrompts = new CopyOnWriteArrayList<>();
    private final List<String> dataInputs = new CopyOnWriteArrayList<>();
    private final List<String> inspectionInputs = new CopyOnWriteArrayList<>();
    private final List<String> opsInputs = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        TestAiSupport.AiFixture aiFixture = TestAiSupport.aiFixture();
        aiFixture.agentRuntimeConfig().setCustomSkillsDir(tempDir.resolve("custom").toString());
        aiFixture.store().updateProvider("openai", new UpdateLlmProviderRequest(null, "sk-test", null, true));
        fixture = aiFixture.buildPlatformRuntime(Mockito.mock(ApmToolkit.class));

        sessionStore = new AiSessionStore();
        pendingRegistry = new ExpertTaskPendingRegistry();
        ExpertTaskTextGuard textGuard = new ExpertTaskTextGuard();
        AtomicReference<BrainRoundContinuer> continuerRef = new AtomicReference<>();
        BrainContinuationService continuationService =
                new BrainContinuationService(continuerRefProvider(continuerRef), pendingRegistry);

        ExpertRuntimeRegistry registry = mock(ExpertRuntimeRegistry.class);
        ExpertRuntime dataRuntime = mock(ExpertRuntime.class);
        when(dataRuntime.stream(any(ExpertChatInput.class))).thenAnswer(invocation -> {
            ExpertChatInput input = invocation.getArgument(0);
            dataInputs.add(input.message() == null ? "" : input.message());
            return Flux.just(ExpertRuntimeEvent.text("data结论：发现 3 条活跃告警，service-a 平均耗时 240ms"));
        });
        ExpertRuntime inspectionRuntime = mock(ExpertRuntime.class);
        when(inspectionRuntime.stream(any(ExpertChatInput.class))).thenAnswer(invocation -> {
            ExpertChatInput input = invocation.getArgument(0);
            inspectionInputs.add(input.message() == null ? "" : input.message());
            return Flux.just(ExpertRuntimeEvent.text("inspection结论：service-a 健康分偏低"));
        });
        ExpertRuntime opsRuntime = mock(ExpertRuntime.class);
        when(opsRuntime.stream(any(ExpertChatInput.class))).thenAnswer(invocation -> {
            ExpertChatInput input = invocation.getArgument(0);
            opsInputs.add(input.message() == null ? "" : input.message());
            return Flux.error(new RuntimeException("ssh 连接 192.168.1.10 超时"));
        });

        ExpertRuntime brainRuntime = mock(ExpertRuntime.class);
        when(brainRuntime.stream(any(ExpertChatInput.class))).thenAnswer(invocation -> {
            ExpertChatInput input = invocation.getArgument(0);
            String message = input.message() == null ? "" : input.message();
            brainPrompts.add(message);
            if (message.contains("数字专家")
                    && (message.contains("已完成") || message.contains("失败"))) {
                return Flux.just(ExpertRuntimeEvent.text("终稿：已根据专家交付给出结论"));
            }
            return Flux.empty();
        });
        when(brainRuntime.chat(any(ExpertChatInput.class)))
                .thenReturn(Mono.just(ExpertChatResult.ok("终稿")));
        when(registry.getOrCreate("data")).thenReturn(dataRuntime);
        when(registry.getOrCreate("inspection")).thenReturn(inspectionRuntime);
        when(registry.getOrCreate("ops")).thenReturn(opsRuntime);
        when(registry.getOrCreate("brain")).thenReturn(brainRuntime);

        SessionExpertRuntimeRegistry sessionRegistry = mock(SessionExpertRuntimeRegistry.class);
        when(sessionRegistry.getOrCreate(any(String.class), any())).thenAnswer(invocation -> {
            AiExpertDefinition expert = invocation.getArgument(1);
            if (expert == null) {
                return brainRuntime;
            }
            return switch (expert.expertId()) {
                case "data" -> dataRuntime;
                case "inspection" -> inspectionRuntime;
                case "ops" -> opsRuntime;
                default -> brainRuntime;
            };
        });

        taskService = new ExpertTaskService(
                fixture.expertManagementService(),
                providerOf(registry),
                providerOf(sessionRegistry),
                null,
                sessionStore,
                pendingRegistry,
                textGuard,
                continuationService,
                fixture.sessionWorkspaceService(),
                new com.databuff.apm.web.ai.platform.runtime.TaskGeneratedFileRegistry());
        AiRuntimeRouter runtimeRouter = WebTestClusterSupport.standaloneAiRouter("web-1");
        orchestrator = TestBeanSupport.chatOrchestrator(
                fixture.expertManagementService(),
                registry,
                sessionRegistry,
                sessionStore,
                aiFixture.aiConfigService(),
                aiFixture.agentRuntimeConfig(),
                mock(ApmToolkit.class),
                new OpenAiCompatibleChatClient(aiFixture.agentRuntimeConfig()),
                aiFixture.store(),
                runtimeRouter,
                new AiRuntimeForwarder(runtimeRouter, 120L),
                taskService,
                pendingRegistry,
                textGuard,
                fixture.sessionWorkspaceService(),
                15);
        continuerRef.set(orchestrator);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        taskService.shutdownForTests();
    }

    @Test
    void specialistInput_alarmTask_isPureTaskBodyWithoutContextHeaders() throws Exception {
        String sessionId = startBrainRound("分析一下当前的告警");
        String taskBody = "分析当前的告警情况，查询所有活跃告警";

        ExpertTask task = submit("data", taskBody, sessionId);
        assertThat(taskService.waitFor(task.taskId(), Duration.ofSeconds(5)).status())
                .isEqualTo(ExpertTaskStatus.SUCCEEDED);
        assertThat(awaitRoundFinal(sessionId)).isTrue();

        assertThat(dataInputs).hasSize(1);
        assertThat(dataInputs.get(0)).isEqualTo(taskBody);
        assertNoBanned(dataInputs.get(0), CONTEXT_HEADER_BANNED);
        assertThat(dataInputs.get(0)).doesNotContain("sourceExpertId").doesNotContain("brain");
    }

    @Test
    void specialistInput_businessImpactTask_isPureTaskBodyWithoutContextHeaders() throws Exception {
        String sessionId = startBrainRound("哪条告警影响业务");
        String taskBody = "查询 service-a/service-b 拓扑与调用量、错误率，判断哪条告警影响业务";

        ExpertTask task = submit("inspection", taskBody, sessionId);
        assertThat(taskService.waitFor(task.taskId(), Duration.ofSeconds(5)).status())
                .isEqualTo(ExpertTaskStatus.SUCCEEDED);
        assertThat(awaitRoundFinal(sessionId)).isTrue();

        assertThat(inspectionInputs).hasSize(1);
        assertThat(inspectionInputs.get(0)).isEqualTo(taskBody);
        assertNoBanned(inspectionInputs.get(0), CONTEXT_HEADER_BANNED);
        assertThat(inspectionInputs.get(0)).doesNotContain("[Context:");
    }

    @Test
    void brainWake_success_containsExactDeliverableAndUserRequest_noProtocolCoaching() throws Exception {
        String userAsk = "分析一下当前的告警";
        String sessionId = startBrainRound(userAsk);

        ExpertTask task = submit("data", "查询活跃告警", sessionId);
        assertThat(taskService.waitFor(task.taskId(), Duration.ofSeconds(5)).status())
                .isEqualTo(ExpertTaskStatus.SUCCEEDED);
        assertThat(awaitRoundFinal(sessionId)).isTrue();

        assertThat(brainPrompts).isNotEmpty();
        String wake = brainPrompts.stream()
                .filter(p -> p.contains("数字专家 data") && p.contains("已完成"))
                .findFirst()
                .orElseThrow();

        assertThat(wake)
                .contains("[数字专家 data · taskId=" + task.taskId() + " · 已完成]")
                .contains("data结论：发现 3 条活跃告警，service-a 平均耗时 240ms")
                .contains("[本轮用户原请求]\n" + userAsk)
                .doesNotContain("假设")
                .doesNotContain("host_cpu_high");
        assertNoBanned(wake, PROTOCOL_COACHING_BANNED);
        assertNoBanned(wake, CONTEXT_HEADER_BANNED);

        assertExactlyOneRoundFinal(sessionId, "终稿");
        assertDeliverablePreserved(sessionId, "data", task.taskId(),
                "data结论：发现 3 条活跃告警，service-a 平均耗时 240ms");
    }

    @Test
    void brainWake_failure_containsExactErrorAndUserRequest_noProtocolCoaching() throws Exception {
        String userAsk = "检查 demo 主机磁盘是否打满";
        String sessionId = startBrainRound(userAsk);

        ExpertTask task = submit("ops", "排查磁盘与 inode", sessionId);
        assertThat(taskService.waitFor(task.taskId(), Duration.ofSeconds(5)).status())
                .isEqualTo(ExpertTaskStatus.FAILED);
        assertThat(awaitRoundFinal(sessionId)).isTrue();

        assertThat(opsInputs).hasSize(1);
        assertThat(opsInputs.get(0)).isEqualTo("排查磁盘与 inode");

        String wake = brainPrompts.stream()
                .filter(p -> p.contains("数字专家 ops") && p.contains("失败"))
                .findFirst()
                .orElseThrow();
        assertThat(wake)
                .contains("[数字专家 ops · taskId=" + task.taskId() + " · 失败]")
                .contains("ssh 连接 192.168.1.10 超时")
                .contains("[本轮用户原请求]\n" + userAsk)
                .doesNotContain("已完成");
        assertNoBanned(wake, PROTOCOL_COACHING_BANNED);
        assertNoBanned(wake, CONTEXT_HEADER_BANNED);
        assertExactlyOneRoundFinal(sessionId, "终稿");
    }

    @Test
    void parallelWakeups_preserveBothDeliverables_andNeverCoachPending() throws Exception {
        String userAsk = "并行排查 mysql 告警与环境";
        String sessionId = startBrainRound(userAsk);

        ExpertTask data = submit("data", "查 mysql 告警", sessionId);
        ExpertTask inspection = submit("inspection", "巡检 mysql 上游服务", sessionId);

        assertThat(taskService.waitFor(data.taskId(), Duration.ofSeconds(5)).status())
                .isEqualTo(ExpertTaskStatus.SUCCEEDED);
        assertThat(taskService.waitFor(inspection.taskId(), Duration.ofSeconds(5)).status())
                .isEqualTo(ExpertTaskStatus.SUCCEEDED);
        assertThat(awaitRoundFinal(sessionId)).isTrue();

        assertThat(brainPrompts).hasSizeGreaterThanOrEqualTo(2);
        assertThat(brainPrompts).allSatisfy(prompt -> {
            assertNoBanned(prompt, PROTOCOL_COACHING_BANNED);
            assertNoBanned(prompt, CONTEXT_HEADER_BANNED);
            assertThat(prompt).contains("[本轮用户原请求]\n" + userAsk);
        });
        assertThat(brainPrompts.stream().anyMatch(p -> p.contains("data结论：发现 3 条活跃告警"))).isTrue();
        assertThat(brainPrompts.stream().anyMatch(p -> p.contains("inspection结论：service-a 健康分偏低"))).isTrue();

        // While the second expert is still pending, the first brain TEXT must be demoted.
        assertThat(sessionStore.messages(sessionId))
                .anyMatch(m -> "brain".equals(m.expertId())
                        && "REASONING".equals(m.messageType())
                        && m.content() != null
                        && m.content().contains("终稿"));

        assertExactlyOneRoundFinal(sessionId, "终稿");
        long roundFinalCount = sessionStore.messages(sessionId).stream()
                .filter(m -> "brain".equals(m.expertId())
                        && Boolean.TRUE.equals(m.metadata().get(ExpertMessageConstants.META_IS_ROUND_FINAL)))
                .count();
        assertThat(roundFinalCount).isEqualTo(1L);
    }

    @Test
    void mixedSuccessAndFailure_wakesWithBothStatuses_withoutCoaching() throws Exception {
        String userAsk = "同时查告警并检查主机";
        String sessionId = startBrainRound(userAsk);

        ExpertTask data = submit("data", "查告警", sessionId);
        ExpertTask ops = submit("ops", "查主机", sessionId);

        assertThat(taskService.waitFor(data.taskId(), Duration.ofSeconds(5)).status())
                .isEqualTo(ExpertTaskStatus.SUCCEEDED);
        assertThat(taskService.waitFor(ops.taskId(), Duration.ofSeconds(5)).status())
                .isEqualTo(ExpertTaskStatus.FAILED);
        assertThat(awaitRoundFinal(sessionId)).isTrue();

        assertThat(brainPrompts.stream().anyMatch(p ->
                p.contains("数字专家 data") && p.contains("已完成") && p.contains("data结论"))).isTrue();
        assertThat(brainPrompts.stream().anyMatch(p ->
                p.contains("数字专家 ops") && p.contains("失败") && p.contains("ssh 连接"))).isTrue();
        assertThat(brainPrompts).allSatisfy(prompt -> assertNoBanned(prompt, PROTOCOL_COACHING_BANNED));
        assertExactlyOneRoundFinal(sessionId, "终稿");
    }

    @Test
    void brainCatalogPrompt_excludesProtocolCoaching_routingDutyRemains() {
        String prompt = BuiltInExpertCatalog.brainPromptBase();
        assertThat(prompt)
                .contains("路由与汇总")
                .contains("不要擅自追加")
                .doesNotContain("串行派发")
                .doesNotContain("pending");
        assertNoBanned(prompt, PROTOCOL_COACHING_BANNED);
    }

    @Test
    void brainRoutingCatalogSection_excludesProtocolCoaching_listsExperts() {
        BrainRoutingCatalog catalog = new BrainRoutingCatalog(fixture.expertManagementService());
        String section = catalog.buildRoutableExpertsSection();
        assertThat(section)
                .contains("`data`")
                .contains("`inspection`")
                .contains("`ops`")
                .contains("`qa`")
                .contains("完整传递用户原请求")
                .doesNotContain("串行")
                .doesNotContain("pending");
        assertNoBanned(section, PROTOCOL_COACHING_BANNED);
    }

    @Test
    void brainRoutingSkill_excludesProtocolCoaching_keepsTaskWritingRules() throws Exception {
        String body = Files.readString(resolveBrainRoutingSkill());

        assertThat(body)
                .contains("派发任务（task）写法")
                .contains("汇总与回答")
                .contains("不要编造数据")
                .doesNotContain("派发后行为")
                .doesNotContain("串行派发")
                .doesNotContain("pending=0")
                .doesNotContain("勿输出最终 TEXT")
                .doesNotContain("系统注入");
        assertNoBanned(body, PROTOCOL_COACHING_BANNED);
    }

    @Test
    void brainRoutingSkill_secondExample_stillCoversMultiExpertTaskWriting() throws Exception {
        String body = Files.readString(resolveBrainRoutingSkill());

        assertThat(body)
                .contains("找出最近1小时日志 ERROR 最多的服务")
                .contains("对 service-b 做一次巡检，并生成巡检报告")
                .contains("不要擅自追加")
                .doesNotContain("前端可能折叠")
                .doesNotContain("本轮异步任务全部完成前");
    }

    private static Path resolveBrainRoutingSkill() {
        Path fromModule = Path.of("../deploy/common/skills/skill.brain.routing/SKILL.md")
                .toAbsolutePath()
                .normalize();
        if (Files.isRegularFile(fromModule)) {
            return fromModule;
        }
        Path fromRoot = Path.of("deploy/common/skills/skill.brain.routing/SKILL.md")
                .toAbsolutePath()
                .normalize();
        assertThat(fromRoot).as("skill.brain.routing SKILL.md must exist").isRegularFile();
        return fromRoot;
    }

    private String startBrainRound(String userMessage) {
        String sessionId = sessionStore.ensureSession(null, "brain", "rk", "web-1", "admin");
        sessionStore.appendUserMessage(sessionId, userMessage, "brain", "admin", Map.of());
        sessionStore.reserveAssistantMessageId(sessionId, "brain");
        sessionStore.setRunning(sessionId, true);
        return sessionId;
    }

    private ExpertTask submit(String targetExpertId, String task, String sessionId) {
        int roundIndex = sessionStore.peekCurrentRoundIndex(sessionId);
        return taskService.submit(new ExpertTaskRequest(
                sessionId,
                "brain",
                targetExpertId,
                task,
                null,
                Map.of(ExpertMessageConstants.META_ROUND_INDEX, roundIndex, "userName", "admin")));
    }

    private void assertExactlyOneRoundFinal(String sessionId, String contentSnippet) {
        List<AiSessionStore.ChatMessage> finals = sessionStore.messages(sessionId).stream()
                .filter(m -> "brain".equals(m.expertId())
                        && "TEXT".equals(m.messageType())
                        && Boolean.TRUE.equals(m.metadata().get(ExpertMessageConstants.META_IS_ROUND_FINAL)))
                .toList();
        assertThat(finals).hasSize(1);
        assertThat(finals.get(0).content()).contains(contentSnippet);
        assertThat(finals.get(0).metadata().get(ExpertMessageConstants.META_TRIGGER_SOURCE))
                .isEqualTo(ExpertMessageConstants.TRIGGER_EXPERT_RESULT);
        assertThat(pendingRegistry.hasPending(sessionId)).isFalse();
        assertThat(sessionStore.isRunning(sessionId)).isFalse();
    }

    private void assertDeliverablePreserved(
            String sessionId, String expertId, String taskId, String expectedText) {
        assertThat(sessionStore.messages(sessionId))
                .anySatisfy(message -> {
                    assertThat(message.expertId()).isEqualTo(expertId);
                    assertThat(message.messageType()).isEqualTo("TEXT");
                    assertThat(message.metadata().get(ExpertMessageConstants.META_IS_EXPERT_DELIVERABLE))
                            .isEqualTo(Boolean.TRUE);
                    assertThat(message.metadata().get(ExpertMessageConstants.META_TASK_ID)).isEqualTo(taskId);
                    assertThat(message.content()).isEqualTo(expectedText);
                });
    }

    private boolean awaitRoundFinal(String sessionId) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            boolean seen = sessionStore.messages(sessionId).stream()
                    .anyMatch(message -> "TEXT".equals(message.messageType())
                            && "brain".equals(message.expertId())
                            && Boolean.TRUE.equals(message.metadata().get(ExpertMessageConstants.META_IS_ROUND_FINAL)));
            if (seen && !sessionStore.isRunning(sessionId)) {
                return true;
            }
            Thread.sleep(50L);
        }
        return sessionStore.messages(sessionId).stream()
                .anyMatch(message -> "TEXT".equals(message.messageType())
                        && "brain".equals(message.expertId())
                        && Boolean.TRUE.equals(message.metadata().get(ExpertMessageConstants.META_IS_ROUND_FINAL)));
    }

    private static ObjectProvider<ExpertRuntimeRegistry> providerOf(ExpertRuntimeRegistry registry) {
        return new ObjectProvider<>() {
            @Override public ExpertRuntimeRegistry getObject() { return registry; }
            @Override public ExpertRuntimeRegistry getObject(Object... args) { return registry; }
            @Override public ExpertRuntimeRegistry getIfAvailable() { return registry; }
            @Override public ExpertRuntimeRegistry getIfUnique() { return registry; }
            @Override public void ifAvailable(Consumer<ExpertRuntimeRegistry> c) { c.accept(registry); }
        };
    }

    private static ObjectProvider<SessionExpertRuntimeRegistry> providerOf(SessionExpertRuntimeRegistry registry) {
        return new ObjectProvider<>() {
            @Override public SessionExpertRuntimeRegistry getObject() { return registry; }
            @Override public SessionExpertRuntimeRegistry getObject(Object... args) { return registry; }
            @Override public SessionExpertRuntimeRegistry getIfAvailable() { return registry; }
            @Override public SessionExpertRuntimeRegistry getIfUnique() { return registry; }
            @Override public void ifAvailable(Consumer<SessionExpertRuntimeRegistry> c) { c.accept(registry); }
        };
    }

    private static ObjectProvider<BrainRoundContinuer> continuerRefProvider(
            AtomicReference<BrainRoundContinuer> holder) {
        return new ObjectProvider<>() {
            @Override public BrainRoundContinuer getObject() { return holder.get(); }
            @Override public BrainRoundContinuer getObject(Object... args) { return holder.get(); }
            @Override public BrainRoundContinuer getIfAvailable() { return holder.get(); }
            @Override public BrainRoundContinuer getIfUnique() { return holder.get(); }
            @Override public void ifAvailable(Consumer<BrainRoundContinuer> c) {
                BrainRoundContinuer continuer = holder.get();
                if (continuer != null) {
                    c.accept(continuer);
                }
            }
        };
    }
}
