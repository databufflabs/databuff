package com.databuff.apm.web.persistence;

import com.databuff.apm.web.storage.DorisAvailability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Loads Doris-backed config after the web port is listening so startup is not blocked on remote JDBC.
 *
 * <p>Periodic Doris probes always call {@link #ensureHydrated(true)} while Doris is reachable, so
 * config is re-pulled after outages and after "ping OK but reads were empty/stale" cluster mess.
 * Incomplete loads rely on the next probe (no tight retry loop).
 */
@Component
public class PersistenceStartupHydrator {

    private static final Logger log = LoggerFactory.getLogger(PersistenceStartupHydrator.class);

    private final LlmProviderPersistence llmProviderPersistence;
    private final PersistentEventRuleStore eventRuleStore;
    private final AlarmPersistence alarmPersistence;
    private final AlarmSilencePersistence alarmSilencePersistence;
    private final AiSessionPersistence aiSessionPersistence;
    private final NotifyChannelPersistence notifyChannelPersistence;
    private final TrafficLightConfigPersistence trafficLightConfigPersistence;
    private final AiPlatformPersistence aiPlatformPersistence;
    private final EventPersistence eventPersistence;
    private final AlarmPolicyHydrator alarmPolicyHydrator;
    private final ExpertTaskPersistence expertTaskPersistence;
    private final MetricCorePersistence metricCorePersistence;
    private final DorisAvailability dorisAvailability;
    private final AtomicBoolean hydrateRunning = new AtomicBoolean(false);
    /**
     * Force-reload requests (Doris recovery). Generation ensures an in-flight hydrate that started
     * before the force cannot clear it; follow-up runs until a load started at/after the request succeeds.
     */
    private final AtomicLong forceGeneration = new AtomicLong(0);
    private final AtomicLong satisfiedForceGeneration = new AtomicLong(0);

    public PersistenceStartupHydrator(
            LlmProviderPersistence llmProviderPersistence,
            PersistentEventRuleStore eventRuleStore,
            AlarmPersistence alarmPersistence,
            AlarmSilencePersistence alarmSilencePersistence,
            AiSessionPersistence aiSessionPersistence,
            NotifyChannelPersistence notifyChannelPersistence,
            TrafficLightConfigPersistence trafficLightConfigPersistence,
            AiPlatformPersistence aiPlatformPersistence,
            ExpertTaskPersistence expertTaskPersistence,
            EventPersistence eventPersistence,
            AlarmPolicyHydrator alarmPolicyHydrator,
            MetricCorePersistence metricCorePersistence,
            DorisAvailability dorisAvailability) {
        this.llmProviderPersistence = llmProviderPersistence;
        this.eventRuleStore = eventRuleStore;
        this.alarmPersistence = alarmPersistence;
        this.alarmSilencePersistence = alarmSilencePersistence;
        this.aiSessionPersistence = aiSessionPersistence;
        this.notifyChannelPersistence = notifyChannelPersistence;
        this.trafficLightConfigPersistence = trafficLightConfigPersistence;
        this.aiPlatformPersistence = aiPlatformPersistence;
        this.expertTaskPersistence = expertTaskPersistence;
        this.eventPersistence = eventPersistence;
        this.alarmPolicyHydrator = alarmPolicyHydrator;
        this.metricCorePersistence = metricCorePersistence;
        this.dorisAvailability = dorisAvailability;
    }

    @Order(Ordered.LOWEST_PRECEDENCE)
    @EventListener(WebServerInitializedEvent.class)
    public void hydrateAsync(WebServerInitializedEvent event) {
        if (dorisAvailability.isUnavailable()) {
            log.info("Skip persistence hydrate while Doris unavailable (web port {})", event.getWebServer().getPort());
            return;
        }
        scheduleHydrate("persistence-hydrator");
        log.info("Persistence hydrate scheduled (web port {})", event.getWebServer().getPort());
    }

    /**
     * Periodic / recovery entry. Always re-reads live load status.
     *
     * @param forceReload {@code true} after Doris outage — pull config again even if previously loaded
     */
    public void ensureHydrated(boolean forceReload) {
        if (dorisAvailability.isUnavailable()) {
            return;
        }
        if (forceReload) {
            forceGeneration.incrementAndGet();
        }
        if (!needsHydrate()) {
            return;
        }
        scheduleHydrate("persistence-hydrator-recovery");
    }

    /** @deprecated use {@link #ensureHydrated(boolean)} */
    public void scheduleRecoveryHydrate() {
        ensureHydrated(true);
    }

    /** Live status: tables ready and last load succeeded, and no pending force-reload. */
    public boolean isHydrateCompleted() {
        return llmProviderPersistence.persistenceEnabled() && !forcePending();
    }

    private boolean needsHydrate() {
        return forcePending() || !llmProviderPersistence.persistenceEnabled();
    }

    private boolean forcePending() {
        return forceGeneration.get() != satisfiedForceGeneration.get();
    }

    private void scheduleHydrate(String threadName) {
        if (!needsHydrate()) {
            return;
        }
        if (!hydrateRunning.compareAndSet(false, true)) {
            return;
        }
        Thread worker = new Thread(() -> {
            boolean loaded = false;
            try {
                loaded = hydrateAll();
            } finally {
                hydrateRunning.set(false);
                // A newer force-reload arrived while we were loading: run again now.
                // Incomplete loads wait for the periodic probe (avoids a tight retry loop).
                if (loaded && forcePending()) {
                    scheduleHydrate("persistence-hydrator-followup");
                }
            }
        }, threadName);
        worker.setDaemon(true);
        worker.start();
    }

    /** @return {@code true} when LLM config tables were ready and load succeeded */
    boolean hydrateAll() {
        long forceGenAtStart = forceGeneration.get();
        long started = System.currentTimeMillis();
        try {
            metricCorePersistence.reloadFromStore();
            trafficLightConfigPersistence.reloadFromStore();
            notifyChannelPersistence.reloadFromStore();
            eventRuleStore.reloadFromStore();
            alarmPolicyHydrator.reloadFromStore();
            eventPersistence.reloadFromStore();
            boolean llmReady = llmProviderPersistence.reloadFromStore();
            alarmSilencePersistence.reloadFromStore();
            alarmPersistence.reloadFromStore();
            aiSessionPersistence.reloadFromStore();
            aiPlatformPersistence.reloadFromStore();
            expertTaskPersistence.reloadFromStore();
            if (llmReady) {
                // Only satisfy force requests that existed when this load started.
                satisfiedForceGeneration.updateAndGet(prev -> Math.max(prev, forceGenAtStart));
                log.info("Persistence hydrate finished in {} ms", System.currentTimeMillis() - started);
                return true;
            }
            log.info("Persistence hydrate incomplete in {} ms; periodic probe will retry",
                    System.currentTimeMillis() - started);
            return false;
        } catch (Exception e) {
            log.warn("Persistence hydrate failed: {}", e.getMessage());
            return false;
        }
    }
}
