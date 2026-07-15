package com.databuff.apm.web.monitor.executor;

import com.databuff.apm.web.storage.DorisAvailability;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * In-process task executor for monitor rules: evaluate → raw event → silence → alert → response.
 */
@Component
public class MonitorExecutorScheduler {

    private final EventRuleExecutionOrchestrator orchestrator;
    private final DorisAvailability dorisAvailability;

    public MonitorExecutorScheduler(
            EventRuleExecutionOrchestrator orchestrator,
            DorisAvailability dorisAvailability) {
        this.orchestrator = orchestrator;
        this.dorisAvailability = dorisAvailability;
    }

    @Scheduled(cron = "${apm.alarm.evaluation-cron:0 * * * * ?}")
    public void evaluateRules() {
        if (dorisAvailability.isUnavailable()) {
            return;
        }
        orchestrator.runAllMonitors();
    }
}
