package com.databuff.apm.web.ai.platform.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fan-in hub for expert completion events.
 * <p>
 * Queues and drains are <b>per sessionId</b>: different sessions drain concurrently;
 * within one session, {@link BrainRoundContinuer#continueBrainRound} is expected to block
 * until that turn finishes so messages are processed serially.
 */
@Service
public class BrainContinuationService {

    private static final Logger log = LoggerFactory.getLogger(BrainContinuationService.class);

    private final ObjectProvider<BrainRoundContinuer> brainRoundContinuer;
    private final ExpertTaskPendingRegistry pendingRegistry;
    private final ConcurrentMap<String, ConcurrentLinkedQueue<ExpertTaskCompletionEvent>> queues =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicBoolean> draining = new ConcurrentHashMap<>();
    /** One drain worker may run per session; different sessions use the pool concurrently. */
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "brain-continuation");
        thread.setDaemon(true);
        return thread;
    });

    public BrainContinuationService(
            ObjectProvider<BrainRoundContinuer> brainRoundContinuer,
            ExpertTaskPendingRegistry pendingRegistry) {
        this.brainRoundContinuer = brainRoundContinuer;
        this.pendingRegistry = pendingRegistry;
    }

    public void notifyTaskFinished(ExpertTaskCompletionEvent event) {
        if (event == null || event.sessionId() == null || event.sessionId().isBlank()) {
            return;
        }
        queues.computeIfAbsent(event.sessionId().trim(), key -> new ConcurrentLinkedQueue<>()).add(event);
        scheduleDrain(event.sessionId().trim());
    }

    private void scheduleDrain(String sessionId) {
        AtomicBoolean flag = draining.computeIfAbsent(sessionId, key -> new AtomicBoolean(false));
        if (!flag.compareAndSet(false, true)) {
            return;
        }
        executor.execute(() -> drainSession(sessionId, flag));
    }

    private void drainSession(String sessionId, AtomicBoolean flag) {
        try {
            BrainRoundContinuer continuer = brainRoundContinuer.getIfAvailable();
            if (continuer == null) {
                log.warn("BrainRoundContinuer unavailable, drop continuation for session {}", sessionId);
                ConcurrentLinkedQueue<ExpertTaskCompletionEvent> queue = queues.remove(sessionId);
                if (queue != null) {
                    ExpertTaskCompletionEvent dropped;
                    while ((dropped = queue.poll()) != null) {
                        pendingRegistry.removePending(sessionId, dropped.taskId());
                    }
                }
                return;
            }
            ConcurrentLinkedQueue<ExpertTaskCompletionEvent> queue = queues.get(sessionId);
            ExpertTaskCompletionEvent event;
            // Serial per session: continuer blocks until the brain turn for this event completes.
            while (queue != null && (event = queue.poll()) != null) {
                try {
                    continuer.continueBrainRound(event);
                } catch (Exception e) {
                    log.warn("Brain continuation failed for session {} task {}: {}",
                            sessionId, event.taskId(), e.getMessage());
                    pendingRegistry.removePending(sessionId, event.taskId());
                }
            }
        } finally {
            flag.set(false);
            ConcurrentLinkedQueue<ExpertTaskCompletionEvent> remaining = queues.get(sessionId);
            if (remaining != null && !remaining.isEmpty()) {
                scheduleDrain(sessionId);
            }
        }
    }

    void shutdownForTests() {
        executor.shutdownNow();
    }
}
