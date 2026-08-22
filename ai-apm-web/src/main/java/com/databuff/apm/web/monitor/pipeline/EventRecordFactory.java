package com.databuff.apm.web.monitor.pipeline;

import com.databuff.apm.web.monitor.eval.RuleEvaluationResult;
import com.databuff.apm.web.monitor.MonitorRecordIdGenerator;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class EventRecordFactory {

    private final MonitorRecordIdGenerator idGenerator;

    public EventRecordFactory(MonitorRecordIdGenerator idGenerator) {
        this.idGenerator = idGenerator;
    }

    public EventRecord fromEvaluation(
            long ruleId,
            String ruleName,
            String service,
            RuleEvaluationResult result,
            boolean silenced,
            Instant triggeredAt) {
        String status = result.triggered() ? EventRecord.STATUS_TRIGGER : EventRecord.STATUS_NORMAL;
        return new EventRecord(
                idGenerator.nextEventId(),
                ruleId,
                ruleName,
                service,
                result.detectionWay(),
                result.level(),
                status,
                result.message(),
                result.groupKey(),
                silenced,
                triggeredAt,
                result.metricId(),
                result.metricLabel(),
                result.metricUnit(),
                result.value(),
                result.threshold(),
                result.comparator());
    }
}
