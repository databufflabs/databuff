package com.databuff.apm.web.persistence;

import com.databuff.apm.common.storage.ApmConfigRepository;
import com.databuff.apm.common.storage.ApmReadRepository;
import com.databuff.apm.web.config.ApmStorageProperties;
import com.databuff.apm.web.monitor.Alarm;
import com.databuff.apm.web.monitor.pipeline.EventRecord;
import com.databuff.apm.web.monitor.pipeline.EventRecordFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Component
public class EventPersistence {

    private static final Logger log = LoggerFactory.getLogger(EventPersistence.class);
    private static final int MEMORY_LIMIT = 10_000;
    private static final int HYDRATE_LIMIT = 2_000;

    private final ApmReadRepository readRepository;
    private final EventRecordFactory eventRecordFactory;
    private final String configDatabase;
    private final ConcurrentLinkedDeque<EventRecord> recentEvents = new ConcurrentLinkedDeque<>();
    private volatile boolean persistenceEnabled;
    private volatile boolean metricColumnsEnabled;

    public EventPersistence(
            ApmReadRepository readRepository,
            EventRecordFactory eventRecordFactory,
            ApmStorageProperties storageProperties) {
        this.readRepository = readRepository;
        this.eventRecordFactory = eventRecordFactory;
        this.configDatabase = storageProperties.configDatabase();
    }

    void reloadFromStore() {
        ApmConfigRepository repository = new ApmConfigRepository(readRepository, configDatabase);
        if (!repository.eventSchemaReady()) {
            log.info("Monitor event store not ready; raw events stay in-memory only");
            return;
        }
        try {
            // Tables without the V008 metric columns keep the legacy read/write shape.
            metricColumnsEnabled = repository.eventMetricColumnsReady();
            List<ApmConfigRepository.EventRow> rows =
                    repository.loadRecentEvents(HYDRATE_LIMIT, metricColumnsEnabled);
            for (ApmConfigRepository.EventRow row : rows) {
                remember(toEventRecord(row));
            }
            persistenceEnabled = true;
            log.info("Monitor raw event persistence enabled ({} rows from store, metric columns: {})",
                    rows.size(), metricColumnsEnabled);
        } catch (Exception e) {
            log.warn("Failed to initialize monitor event persistence: {}", e.getMessage());
        }
    }

    public void persist(EventRecord event) {
        remember(event);
        if (!persistenceEnabled) {
            return;
        }
        try {
            ApmConfigRepository repository = new ApmConfigRepository(readRepository, configDatabase);
            if (metricColumnsEnabled) {
                repository.upsertEventWithMetric(toRow(event));
            } else {
                repository.upsertEvent(toRow(event));
            }
        } catch (Exception e) {
            log.warn("Failed to persist monitor event {}: {}", event.id(), e.getMessage());
        }
    }

    /** Trigger events in half-open {@code [from, to)} for cockpit per-minute alarm counts. */
    public List<EventRecord> listTriggerEventsInRange(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) {
            return List.of();
        }
        List<EventRecord> matched = new ArrayList<>();
        for (EventRecord event : recentEvents) {
            if (!event.isAbnormal()) {
                continue;
            }
            Instant triggeredAt = event.triggeredAt();
            if (triggeredAt.isBefore(from) || !triggeredAt.isBefore(to)) {
                continue;
            }
            matched.add(event);
        }
        return matched;
    }

    public void linkToAlarm(String eventId, String alarmId) {
        if (!persistenceEnabled || eventId == null || eventId.isBlank() || alarmId == null || alarmId.isBlank()) {
            return;
        }
        try {
            new ApmConfigRepository(readRepository, configDatabase).upsertAlarmEvent(
                    new ApmConfigRepository.AlarmEventRow(alarmId, eventId, Instant.now()));
        } catch (Exception e) {
            log.warn("Failed to link event {} to alarm {}: {}", eventId, alarmId, e.getMessage());
        }
    }

    public boolean isPersistenceEnabled() {
        return persistenceEnabled;
    }

    public Optional<ApmConfigRepository.EventRow> findById(String eventId) {
        if (!persistenceEnabled || eventId == null || eventId.isBlank()) {
            return Optional.empty();
        }
        try {
            return new ApmConfigRepository(readRepository, configDatabase).loadEventById(eventId);
        } catch (Exception e) {
            log.warn("Failed to load monitor event {}: {}", eventId, e.getMessage());
            return Optional.empty();
        }
    }

    public List<ApmConfigRepository.EventRow> listForAlarm(Alarm alarm) {
        if (!persistenceEnabled || alarm == null) {
            return List.of();
        }
        return listForAlarms(List.of(alarm)).getOrDefault(alarm.id(), List.of());
    }

    public Map<String, List<ApmConfigRepository.EventRow>> listForAlarms(Collection<Alarm> alarms) {
        if (!persistenceEnabled || alarms == null || alarms.isEmpty()) {
            return Map.of();
        }
        List<String> alarmIds = alarms.stream()
                .map(Alarm::id)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (alarmIds.isEmpty()) {
            return Map.of();
        }
        try {
            return new ApmConfigRepository(readRepository, configDatabase)
                    .listEventsByAlarmIds(alarmIds, EventRecord.STATUS_TRIGGER);
        } catch (Exception e) {
            log.warn("Failed to batch list monitor events for alarms: {}", e.getMessage());
            return Map.of();
        }
    }

    public List<Map<String, Object>> trendForAlarm(Alarm alarm, long intervalSeconds) {
        long intervalMillis = Math.max(1L, intervalSeconds) * 1000L;
        Map<Long, Long> buckets = new TreeMap<>();
        for (ApmConfigRepository.EventRow event : listForAlarm(alarm)) {
            long bucket = event.triggeredAt().toEpochMilli() / intervalMillis * intervalMillis;
            buckets.merge(bucket, 1L, Long::sum);
        }
        List<Map<String, Object>> points = new java.util.ArrayList<>();
        for (Map.Entry<Long, Long> entry : buckets.entrySet()) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("time", entry.getKey());
            point.put("eventCnt", entry.getValue());
            points.add(point);
        }
        return points;
    }

    private void remember(EventRecord event) {
        if (event == null) {
            return;
        }
        recentEvents.addFirst(event);
        while (recentEvents.size() > MEMORY_LIMIT) {
            recentEvents.removeLast();
        }
    }

    private static EventRecord toEventRecord(ApmConfigRepository.EventRow row) {
        return new EventRecord(
                row.id(),
                row.ruleId(),
                row.ruleName(),
                row.service(),
                row.detectionWay(),
                row.level(),
                row.status(),
                row.message(),
                row.groupKey(),
                row.silenced(),
                row.triggeredAt(),
                row.metricId(),
                row.metricLabel(),
                row.metricUnit(),
                row.value(),
                row.threshold(),
                row.comparator());
    }

    private static ApmConfigRepository.EventRow toRow(EventRecord event) {
        return new ApmConfigRepository.EventRow(
                event.id(),
                event.ruleId(),
                event.ruleName(),
                event.service(),
                event.detectionWay(),
                event.level(),
                event.status(),
                event.message(),
                event.groupKey(),
                event.silenced(),
                event.triggeredAt(),
                event.metricId(),
                event.metricLabel(),
                event.metricUnit(),
                event.value(),
                event.threshold(),
                event.comparator());
    }
}
