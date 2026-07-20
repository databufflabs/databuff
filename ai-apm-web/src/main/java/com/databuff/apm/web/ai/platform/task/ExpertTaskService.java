package com.databuff.apm.web.ai.platform.task;

import com.databuff.apm.web.ai.agent.AiMessageStatus;
import com.databuff.apm.web.ai.agent.AiMessageType;
import com.databuff.apm.web.ai.agent.AiSessionStore;
import com.databuff.apm.web.ai.platform.expert.ExpertManagementService;
import com.databuff.apm.web.persistence.ExpertTaskPersistence;
import com.databuff.apm.web.ai.platform.runtime.ExpertChatInput;
import com.databuff.apm.web.ai.platform.runtime.ExpertChatContext;
import com.databuff.apm.web.ai.platform.runtime.ExpertChatScopeRegistry;
import com.databuff.apm.web.ai.platform.runtime.ExpertRuntime;
import com.databuff.apm.web.ai.platform.runtime.ExpertRuntimeEvent;
import com.databuff.apm.web.ai.platform.runtime.ExpertRuntimeRegistry;
import com.databuff.apm.web.ai.platform.runtime.SessionExpertRuntimeRegistry;
import com.databuff.apm.web.ai.platform.runtime.SessionWorkspaceService;
import com.databuff.apm.web.ai.platform.runtime.TaskGeneratedFileRegistry;
import com.databuff.apm.web.ai.platform.expert.AiExpertDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Service
public class ExpertTaskService {

    private static final Logger log = LoggerFactory.getLogger(ExpertTaskService.class);

    private final ExpertManagementService expertManagementService;
    private final ObjectProvider<ExpertRuntimeRegistry> expertRuntimeRegistry;
    private final ObjectProvider<SessionExpertRuntimeRegistry> sessionExpertRuntimeRegistry;
    private final ObjectProvider<ExpertTaskPersistence> persistence;
    private final AiSessionStore sessionStore;
    private final ExpertTaskPendingRegistry pendingRegistry;
    private final ExpertTaskTextGuard taskTextGuard;
    private final BrainContinuationService brainContinuationService;
    private final SessionWorkspaceService workspaceService;
    private final TaskGeneratedFileRegistry generatedFileRegistry;
    private final ThreadPoolTaskExecutor taskExecutor;
    private final ConcurrentMap<String, ExpertTask> tasks = new ConcurrentHashMap<>();

    @Autowired
    public ExpertTaskService(
            ExpertManagementService expertManagementService,
            ObjectProvider<ExpertRuntimeRegistry> expertRuntimeRegistry,
            ObjectProvider<SessionExpertRuntimeRegistry> sessionExpertRuntimeRegistry,
            ObjectProvider<ExpertTaskPersistence> persistence,
            AiSessionStore sessionStore,
            ExpertTaskPendingRegistry pendingRegistry,
            ExpertTaskTextGuard taskTextGuard,
            @Lazy BrainContinuationService brainContinuationService,
            SessionWorkspaceService workspaceService,
            TaskGeneratedFileRegistry generatedFileRegistry) {
        this.expertManagementService = expertManagementService;
        this.expertRuntimeRegistry = expertRuntimeRegistry;
        this.sessionExpertRuntimeRegistry = sessionExpertRuntimeRegistry;
        this.persistence = persistence;
        this.sessionStore = sessionStore;
        this.pendingRegistry = pendingRegistry;
        this.taskTextGuard = taskTextGuard;
        this.brainContinuationService = brainContinuationService;
        this.workspaceService = workspaceService;
        this.generatedFileRegistry = generatedFileRegistry;
        this.taskExecutor = createExecutor();
    }

    public ExpertTask submit(ExpertTaskRequest request) {
        validateRequest(request);
        // Same targetExpertId is serial per session: refuse parallel create (no dedupe / reuse).
        Optional<ExpertTask> inFlight = findInFlightForTarget(request.sessionId(), request.targetExpertId());
        if (inFlight.isPresent()) {
            ExpertTask busy = inFlight.get();
            throw new SerialExpertDispatchException(busy);
        }
        String sourceExpertId = request.sourceExpertId() == null ? "" : request.sourceExpertId().trim();
        String targetExpertId = request.targetExpertId().trim();
        // Never persist self-dispatch edges like qa→qa; fall back to brain as dispatcher.
        if (sourceExpertId.isBlank() || sourceExpertId.equals(targetExpertId)) {
            sourceExpertId = "brain";
        }
        int roundIndex = resolveRoundIndex(request);
        String userName = metadataString(request.metadata(), "userName");
        if (userName == null) {
            userName = sessionStore.peekUserName(request.sessionId());
        }
        sessionStore.ensureSession(
                request.sessionId(),
                sourceExpertId,
                null,
                null,
                userName);
        Map<String, Object> metadata = new LinkedHashMap<>(request.metadata());
        metadata.putIfAbsent("userName", userName);
        metadata.put(ExpertMessageConstants.META_ROUND_INDEX, roundIndex);
        metadata.put(ExpertMessageConstants.META_SOURCE_EXPERT_ID, sourceExpertId);
        Instant now = Instant.now();
        ExpertTask created = new ExpertTask(
                UUID.randomUUID().toString(),
                request.parentTaskId(),
                request.sessionId(),
                sourceExpertId,
                targetExpertId,
                ExpertTaskStatus.CREATED,
                request.input(),
                null,
                null,
                metadata,
                now,
                now,
                null);
        tasks.put(created.taskId(), created);
        pendingRegistry.addPending(created.sessionId(), created.taskId());
        if ("brain".equals(created.sourceExpertId())) {
            sessionStore.setRunning(created.sessionId(), true);
        }
        persist(created);
        publish(ExpertTaskEvent.created(created));
        Future<?> future = taskExecutor.submit(() -> runTask(created.taskId()));
        pendingRegistry.registerTaskFuture(created.sessionId(), created.taskId(), future);
        return created;
    }

    /**
     * In-flight (non-terminal) task for the same session + target expert, if any.
     * Used to enforce serial dispatch per expert.
     */
    public Optional<ExpertTask> findInFlightForTarget(String sessionId, String targetExpertId) {
        if (sessionId == null || sessionId.isBlank() || targetExpertId == null || targetExpertId.isBlank()) {
            return Optional.empty();
        }
        String sid = sessionId.trim();
        String target = targetExpertId.trim();
        return listBySession(sid).stream()
                .filter(task -> target.equals(task.targetExpertId()))
                .filter(task -> task.status() != null && !task.status().isTerminal())
                .findFirst();
    }

    public Optional<ExpertTask> get(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    public void cancelSessionTasks(String sessionId) {
        pendingRegistry.cancelSessionTasks(sessionId);
        generatedFileRegistry.removeSession(sessionId);
    }

    public List<ExpertTask> listBySession(String sessionId) {
        return tasks.values().stream()
                .filter(task -> sessionId != null && sessionId.equals(task.sessionId()))
                .sorted(java.util.Comparator.comparing(ExpertTask::createdAt))
                .toList();
    }

    public List<ExpertTask> listAll() {
        return tasks.values().stream()
                .sorted(java.util.Comparator.comparing(ExpertTask::createdAt).reversed())
                .toList();
    }

    public ExpertTask waitFor(String taskId, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            ExpertTask task = tasks.get(taskId);
            if (task == null) {
                throw new IllegalArgumentException("task not found: " + taskId);
            }
            if (task.status().isTerminal()) {
                return task;
            }
            Thread.sleep(200L);
        }
        ExpertTask task = tasks.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("task not found: " + taskId);
        }
        return task;
    }

    public String formatTaskResult(String taskId) {
        return get(taskId).map(this::formatTask).orElse("task not found: " + taskId);
    }

    /**
     * True while this round still has brain-dispatched subtasks without a corresponding
     * expert deliverable (or failure) message in the session store.
     */
    public boolean awaitingSubExpertResponses(String sessionId, int roundIndex) {
        if (sessionId == null || sessionId.isBlank() || roundIndex <= 0) {
            return false;
        }
        List<ExpertTask> tasks = listBySession(sessionId).stream()
                .filter(task -> "brain".equals(task.sourceExpertId()))
                .filter(task -> taskRoundIndex(task) == roundIndex)
                .toList();
        if (tasks.isEmpty()) {
            return false;
        }
        return tasks.stream().anyMatch(task -> !hasExpertResponseInSession(sessionId, roundIndex, task));
    }

    /**
     * True while brain-dispatched subtasks in this round have not yet notified brain via
     * {@link BrainContinuationService#notifyTaskFinished} (pending registry entry still present).
     * This tracks sub-agent completion handoff to brain, not dispatchExpertTask tool return
     * and not merely expert TEXT appearing in the session store.
     */
    public boolean awaitingBrainTaskCompletionNotifications(String sessionId, int roundIndex) {
        if (sessionId == null || sessionId.isBlank() || roundIndex <= 0) {
            return false;
        }
        return listBySession(sessionId).stream()
                .filter(task -> "brain".equals(task.sourceExpertId()))
                .filter(task -> taskRoundIndex(task) == roundIndex)
                .anyMatch(task -> pendingRegistry.isPending(sessionId, task.taskId()));
    }

    private boolean hasExpertResponseInSession(String sessionId, int roundIndex, ExpertTask task) {
        boolean deliverableSeen = sessionStore.messages(sessionId).stream()
                .anyMatch(message -> message.roundIndex() == roundIndex
                        && Boolean.TRUE.equals(message.metadata().get(ExpertMessageConstants.META_IS_EXPERT_DELIVERABLE))
                        && task.taskId().equals(String.valueOf(message.metadata().get(ExpertMessageConstants.META_TASK_ID))));
        if (deliverableSeen) {
            return true;
        }
        boolean failureSeen = sessionStore.messages(sessionId).stream()
                .anyMatch(message -> message.roundIndex() == roundIndex
                        && AiMessageType.ERROR.name().equals(message.messageType())
                        && task.taskId().equals(String.valueOf(message.metadata().get(ExpertMessageConstants.META_TASK_ID))));
        if (failureSeen) {
            return true;
        }
        return false;
    }

    private static int taskRoundIndex(ExpertTask task) {
        return metadataInt(task.metadata(), ExpertMessageConstants.META_ROUND_INDEX, 0);
    }

    private void runTask(String taskId) {
        ExpertTask current = tasks.get(taskId);
        if (current == null) {
            return;
        }
        if (sessionStore.isAbortRequested(current.sessionId())) {
            failTask(current, ExpertTaskStatus.CANCELLED, "task cancelled");
            notifyFailure(current, "task cancelled");
            return;
        }
        current = updateStatus(current, ExpertTaskStatus.RUNNING, null, null);
        publish(ExpertTaskEvent.running(current));
        try {
            ExpertRuntimeRegistry sharedRegistry = expertRuntimeRegistry.getIfAvailable();
            SessionExpertRuntimeRegistry sessionRegistry = sessionExpertRuntimeRegistry.getIfAvailable();
            String targetExpertId = current.targetExpertId();
            AiExpertDefinition targetExpert = expertManagementService.find(targetExpertId)
                    .orElseThrow(() -> new IllegalStateException("expert not found: " + targetExpertId));
            final ExpertRuntime runtime;
            if (sessionRegistry != null) {
                runtime = sessionRegistry.getOrCreate(current.sessionId(), targetExpert);
            } else if (sharedRegistry != null) {
                runtime = sharedRegistry.getOrCreate(targetExpertId);
            } else {
                throw new IllegalStateException("ExpertRuntimeRegistry unavailable");
            }
            int roundIndex = metadataInt(current.metadata(), ExpertMessageConstants.META_ROUND_INDEX,
                    sessionStore.peekCurrentRoundIndex(current.sessionId()));
            String userName = metadataString(current.metadata(), "userName");
            String parentSessionId = current.sessionId();
            String runtimeSessionId = ExpertChatScopeRegistry.taskScopedSessionId(
                    parentSessionId, current.taskId());
            Map<String, Object> context = ExpertMessageContext.taskMetadata(
                    parentSessionId,
                    roundIndex,
                    current.taskId(),
                    current.sourceExpertId(),
                    runtimeSessionId);
            String wrappedInput = ExpertMessageContext.wrapTaskInput(
                    parentSessionId,
                    roundIndex,
                    current.taskId(),
                    current.sourceExpertId(),
                    current.input());
            // ChatScope stays on runtimeSessionId; AgentScope memory uses parentSessionId on the
            // per-(session, expert) runtime so serial dispatches share memory.
            ExpertChatInput input = new ExpertChatInput(
                    wrappedInput,
                    parentSessionId,
                    userName,
                    null,
                    context);
            ExpertChatContext.State chatContext = new ExpertChatContext.State(
                    runtimeSessionId,
                    userName,
                    current.targetExpertId(),
                    null,
                    true,
                    null,
                    current.taskId());
            ExpertTask runningTask = current;
            StringBuilder content = new StringBuilder();
            ExpertTaskContext.enterScope(runtimeSessionId, runningTask.sourceExpertId(), event -> {});
            ExpertChatScopeRegistry.register(chatContext);
            Flux<ExpertRuntimeEvent> events = runtime.stream(input);
            Disposable disposable = events
                    .doOnNext(event -> appendStreamContent(content, event))
                    .doFinally(signal -> {
                        ExpertChatScopeRegistry.unregister(runtimeSessionId);
                        ExpertTaskContext.leaveScope(runtimeSessionId);
                        pendingRegistry.removeTaskDisposable(runningTask.sessionId(), runningTask.taskId());
                    })
                    .subscribe(
                            null,
                            error -> handleTaskStreamFailure(runningTask, roundIndex, userName, error),
                            () -> handleTaskStreamSuccess(runningTask, roundIndex, userName, content));
            pendingRegistry.registerTaskDisposable(runningTask.sessionId(), runningTask.taskId(), disposable);
        } catch (Exception e) {
            log.warn("Expert task {} failed to start: {}", taskId, e.getMessage());
            int failureRound = metadataInt(current.metadata(), ExpertMessageConstants.META_ROUND_INDEX,
                    sessionStore.peekCurrentRoundIndex(current.sessionId()));
            handleTaskStreamFailure(
                    current,
                    failureRound,
                    metadataString(current.metadata(), "userName"),
                    e);
        }
    }

    private void handleTaskStreamSuccess(
            ExpertTask current,
            int roundIndex,
            String userName,
            StringBuilder content) {
        sessionStore.finalizeRoundStreaming(current.sessionId(), current.targetExpertId());
        if (sessionStore.isAbortRequested(current.sessionId())) {
            failTask(current, ExpertTaskStatus.CANCELLED, "task cancelled");
            notifyFailure(current, "task cancelled");
            return;
        }
        String reply = content.toString().trim();
        if (reply.isBlank()) {
            reply = extractExpertReplyFromSession(
                    current.sessionId(), current.targetExpertId(), roundIndex);
        }
        if (reply.isBlank()) {
            handleTaskStreamFailure(
                    current,
                    roundIndex,
                    userName,
                    new IllegalStateException("empty expert task response"));
            return;
        }
        ExpertTask done = updateStatus(
                withGeneratedFiles(current),
                ExpertTaskStatus.SUCCEEDED,
                reply,
                null);
        publish(ExpertTaskEvent.completed(done));
        commitExpertDeliverable(done, roundIndex, reply);
    }

    private void handleTaskStreamFailure(
            ExpertTask current,
            int roundIndex,
            String userName,
            Throwable error) {
        String message = error == null || error.getMessage() == null ? "task failed" : error.getMessage();
        log.warn("Expert task {} failed: {}", current.taskId(), message);
        persistExpertTaskFailure(current, roundIndex, userName, message);
        ExpertTaskStatus status = error instanceof java.util.concurrent.TimeoutException
                || message.contains("Timeout")
                ? ExpertTaskStatus.TIMEOUT
                : ExpertTaskStatus.FAILED;
        ExpertTask failed = failTask(current, status, message);
        notifyFailure(failed, failed.error());
    }

    private static void appendStreamContent(StringBuilder content, ExpertRuntimeEvent event) {
        if (event == null) {
            return;
        }
        String type = event.type();
        if ("text".equals(type) && event.content() != null) {
            content.append(event.content());
            return;
        }
        if ("reasoning".equals(type) && event.content() != null && content.isEmpty()) {
            content.append(event.content());
        }
    }

    private void commitExpertDeliverable(ExpertTask task, int roundIndex, String reply) {
        if (!taskTextGuard.tryCommitExpertTaskText(task.sessionId(), roundIndex, task.taskId())) {
            return;
        }
        Map<String, Object> deliverableMetadata = new LinkedHashMap<>();
        deliverableMetadata.put(ExpertMessageConstants.META_SOURCE_EXPERT_ID, task.sourceExpertId());
        deliverableMetadata.put(
                ExpertMessageConstants.META_TRIGGER_SOURCE,
                ExpertMessageConstants.TRIGGER_EXPERT_DISPATCH);
        Object generatedFiles = task.metadata().get("generatedFiles");
        if (generatedFiles instanceof List<?> files && !files.isEmpty()) {
            deliverableMetadata.put("generatedFiles", files);
        }
        sessionStore.appendExpertDeliverableText(
                task.sessionId(),
                task.targetExpertId(),
                roundIndex,
                task.taskId(),
                reply,
                Map.copyOf(deliverableMetadata));
        if ("brain".equals(task.sourceExpertId())) {
            brainContinuationService.notifyTaskFinished(ExpertTaskCompletionEvent.success(
                    task.sessionId(),
                    roundIndex,
                    task.taskId(),
                    task.targetExpertId(),
                    task.sourceExpertId(),
                    metadataString(task.metadata(), "userName"),
                    reply));
        } else {
            pendingRegistry.removePending(task.sessionId(), task.taskId());
        }
    }

    private void notifyFailure(ExpertTask task, String error) {
        int roundIndex = metadataInt(task.metadata(), ExpertMessageConstants.META_ROUND_INDEX,
                sessionStore.peekCurrentRoundIndex(task.sessionId()));
        if (!"brain".equals(task.sourceExpertId())) {
            pendingRegistry.removePending(task.sessionId(), task.taskId());
        }
        if ("brain".equals(task.sourceExpertId())) {
            brainContinuationService.notifyTaskFinished(ExpertTaskCompletionEvent.failure(
                    task.sessionId(),
                    roundIndex,
                    task.taskId(),
                    task.targetExpertId(),
                    task.sourceExpertId(),
                    metadataString(task.metadata(), "userName"),
                    error));
        }
    }

    private ExpertTask failTask(ExpertTask current, ExpertTaskStatus status, String error) {
        generatedFileRegistry.remove(current.sessionId(), current.taskId());
        ExpertTask failed = updateStatus(current, status, null, error);
        publish(ExpertTaskEvent.failed(failed));
        if (!"brain".equals(failed.sourceExpertId())) {
            pendingRegistry.removePending(failed.sessionId(), failed.taskId());
        }
        return failed;
    }

    ExpertTask withGeneratedFiles(ExpertTask task) {
        List<String> ownedPaths = generatedFileRegistry.paths(task.sessionId(), task.taskId());
        generatedFileRegistry.remove(task.sessionId(), task.taskId());
        if (ownedPaths.isEmpty()) {
            return task;
        }
        Map<String, SessionWorkspaceService.WorkspaceFileInfo> filesByPath =
                workspaceService.listOutputFiles(task.sessionId()).stream()
                        .collect(java.util.stream.Collectors.toMap(
                                SessionWorkspaceService.WorkspaceFileInfo::relativePath,
                                file -> file,
                                (left, right) -> right,
                                LinkedHashMap::new));
        List<Map<String, Object>> generatedFiles = ownedPaths.stream()
                .map(filesByPath::get)
                .filter(java.util.Objects::nonNull)
                .map(SessionWorkspaceService.WorkspaceFileInfo::toMetadata)
                .toList();
        if (generatedFiles.isEmpty()) {
            return task;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(task.metadata());
        metadata.put("generatedFiles", generatedFiles);
        return task.withMetadata(Map.copyOf(metadata));
    }

    private ExpertTask updateStatus(
            ExpertTask current,
            ExpertTaskStatus status,
            String output,
            String error) {
        Instant now = Instant.now();
        Instant completedAt = status.isTerminal() ? now : current.completedAt();
        ExpertTask updated = current.withStatus(status, output, error, now, completedAt);
        tasks.put(current.taskId(), updated);
        persist(updated);
        return updated;
    }

    private String formatTask(ExpertTask task) {
        return switch (task.status()) {
            case SUCCEEDED -> ExpertMessageConstants.asyncWaitMessage(task.taskId(), task.targetExpertId())
                    + "\nstatus=SUCCEEDED\noutput=" + task.output();
            case FAILED, TIMEOUT -> "status=" + task.status() + "\ntargetExpertId=" + task.targetExpertId()
                    + "\nerror=" + task.error();
            case RUNNING, CREATED -> ExpertMessageConstants.asyncWaitMessage(task.taskId(), task.targetExpertId())
                    + "\nstatus=" + task.status();
            case CANCELLED -> "status=CANCELLED\ntargetExpertId=" + task.targetExpertId();
        };
    }

    private int resolveRoundIndex(ExpertTaskRequest request) {
        int fromMetadata = metadataInt(request.metadata(), ExpertMessageConstants.META_ROUND_INDEX, -1);
        if (fromMetadata > 0) {
            return fromMetadata;
        }
        return sessionStore.peekCurrentRoundIndex(request.sessionId());
    }

    private void validateRequest(ExpertTaskRequest request) {
        if (request == null || blank(request.input())) {
            throw new IllegalArgumentException("task input is required");
        }
        if (blank(request.targetExpertId())) {
            throw new IllegalArgumentException("targetExpertId is required");
        }
        var expert = expertManagementService.find(request.targetExpertId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "target expert not found: " + request.targetExpertId()));
        if (!expert.enabled()) {
            throw new IllegalArgumentException("target expert is disabled: " + request.targetExpertId());
        }
    }

    private void persistExpertTaskFailure(
            ExpertTask task,
            int roundIndex,
            String userName,
            String error) {
        if (task == null || task.sessionId() == null || task.sessionId().isBlank()) {
            return;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ExpertMessageConstants.META_TASK_ID, task.taskId());
        metadata.put(ExpertMessageConstants.META_ROUND_INDEX, roundIndex);
        metadata.put(ExpertMessageConstants.META_SOURCE_EXPERT_ID, task.sourceExpertId());
        sessionStore.appendTraceMessage(
                task.sessionId(),
                task.targetExpertId(),
                userName,
                AiMessageType.ERROR,
                "子专家 " + task.targetExpertId() + " 执行失败："
                        + (error == null || error.isBlank() ? "unknown" : error.trim()),
                AiMessageStatus.FAILED,
                metadata);
    }

    private String extractExpertReplyFromSession(String sessionId, String expertId, int roundIndex) {
        return sessionStore.messages(sessionId).stream()
                .filter(message -> expertId.equals(message.expertId()))
                .filter(message -> message.roundIndex() == roundIndex)
                .filter(message -> {
                    String type = message.messageType();
                    return AiMessageType.REASONING.name().equals(type)
                            || AiMessageType.TEXT.name().equals(type);
                })
                .map(AiSessionStore.ChatMessage::content)
                .filter(content -> content != null && !content.isBlank())
                .reduce((first, second) -> second)
                .orElse("");
    }

    private void publish(ExpertTaskEvent event) {
        if (event == null || event.taskId() == null) {
            return;
        }
        get(event.taskId())
                .map(ExpertTask::sessionId)
                .ifPresent(sessionId -> ExpertTaskContext.emit(sessionId, event));
    }

    private void persist(ExpertTask task) {
        if (persistence != null) {
            persistence.ifAvailable(sync -> sync.persistTask(task));
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String metadataString(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null) {
            return null;
        }
        Object value = metadata.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static int metadataInt(Map<String, Object> metadata, String key, int defaultValue) {
        if (metadata == null || key == null) {
            return defaultValue;
        }
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static ThreadPoolTaskExecutor createExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("expert-task-");
        executor.initialize();
        return executor;
    }

    public void registerTaskForTests(ExpertTask task) {
        if (task != null && task.taskId() != null) {
            tasks.put(task.taskId(), task);
        }
    }

    public void shutdownForTests() throws InterruptedException {
        taskExecutor.shutdown();
        taskExecutor.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS);
        brainContinuationService.shutdownForTests();
    }
}
