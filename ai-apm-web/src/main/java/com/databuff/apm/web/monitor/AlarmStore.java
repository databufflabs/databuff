package com.databuff.apm.web.monitor;

import com.databuff.apm.web.monitor.eval.EventRulePayloadParser;
import com.databuff.apm.web.persistence.AlarmPersistence;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Component
public class AlarmStore {

    @Autowired
    private ObjectProvider<AlarmPersistence> persistence;
    private final Map<String, Alarm> events = new LinkedHashMap<>();
    private final MonitorRecordIdGenerator idGenerator;

    public AlarmStore(MonitorRecordIdGenerator idGenerator) {
        this.idGenerator = idGenerator;
    }

    public Alarm open(String service, String detectionWay, String level, String message) {
        return open(service, detectionWay, level, message, Instant.now());
    }

    public Alarm open(
            String service,
            String detectionWay,
            String level,
            String message,
            Instant triggeredAt) {
        return open(0L, service, detectionWay, level, message, triggeredAt, Alarm.STATUS_OPEN, null);
    }

    /** One-shot alert for a single event: triggered and resolved at the same instant. */
    public Alarm openResolved(
            String service,
            String detectionWay,
            String level,
            String message,
            Instant triggeredAt) {
        return openResolved(0L, service, detectionWay, level, message, triggeredAt);
    }

    /** One-shot alert tied to the originating rule ({@code policyId = ruleId}). */
    public Alarm openResolved(
            long policyId,
            String service,
            String detectionWay,
            String level,
            String message,
            Instant triggeredAt) {
        Instant at = triggeredAt == null ? Instant.now() : triggeredAt;
        return open(policyId, service, detectionWay, level, message, at, Alarm.STATUS_RESOLVED, at);
    }

    private Alarm open(
            long policyId,
            String service,
            String detectionWay,
            String level,
            String message,
            Instant triggeredAt,
            String status,
            Instant resolvedAt) {
        String id = idGenerator.nextAlarmId();
        String resolvedLevel = level == null || level.isBlank() ? "warning" : level;
        String way = EventRulePayloadParser.normalizeWay(detectionWay);
        Instant effectiveTriggeredAt = triggeredAt == null ? Instant.now() : triggeredAt;
        Alarm event = new Alarm(
                id,
                policyId,
                service,
                way,
                resolvedLevel,
                message,
                status,
                effectiveTriggeredAt,
                resolvedAt);
        events.put(id, event);
        ifAvailable(sync -> sync.persist(event));
        return event;
    }

    public Optional<Alarm> resolveById(String id) {
        Alarm open = events.get(id);
        if (open == null || !Alarm.STATUS_OPEN.equals(open.status())) {
            return Optional.empty();
        }
        Alarm resolved = resolveAlarm(open, Instant.now());
        return Optional.of(resolved);
    }

    public void resolveAllOpenByServiceAndDetectionWay(String service, String detectionWay) {
        String way = EventRulePayloadParser.normalizeWay(detectionWay);
        events.values().stream()
                .filter(event -> Alarm.STATUS_OPEN.equals(event.status()))
                .filter(event -> service != null && service.equals(event.service()))
                .filter(event -> way.equals(EventRulePayloadParser.normalizeWay(event.detectionWay())))
                .forEach(alarm -> resolveAlarm(alarm, Instant.now()));
    }

    private Alarm resolveAlarm(Alarm open, Instant at) {
        Instant resolvedAt = at == null ? Instant.now() : at;
        Alarm resolved = open.resolve(resolvedAt);
        events.put(open.id(), resolved);
        ifAvailable(sync -> sync.persist(resolved));
        return resolved;
    }

    public Optional<Alarm> findOpenByService(String service) {
        return events.values().stream()
                .filter(event -> Alarm.STATUS_OPEN.equals(event.status()))
                .filter(event -> service != null && service.equals(event.service()))
                .findFirst();
    }

    public List<Alarm> listOpenByService(String service) {
        return events.values().stream()
                .filter(event -> Alarm.STATUS_OPEN.equals(event.status()))
                .filter(event -> service != null && service.equals(event.service()))
                .toList();
    }

    public List<Alarm> listOpen() {
        return events.values().stream()
                .filter(event -> Alarm.STATUS_OPEN.equals(event.status()))
                .toList();
    }

    public Optional<Alarm> findLastResolvedByService(String service, long withinMillis) {
        Instant cutoff = Instant.now().minusMillis(withinMillis);
        return events.values().stream()
                .filter(event -> Alarm.STATUS_RESOLVED.equals(event.status()))
                .filter(event -> service != null && service.equals(event.service()))
                .filter(event -> event.resolvedAt() != null && !event.resolvedAt().isBefore(cutoff))
                .max(Comparator.comparing(Alarm::resolvedAt));
    }

    public List<AlarmIncident> groupOpenIncidents() {
        Map<String, List<Alarm>> grouped = events.values().stream()
                .filter(event -> Alarm.STATUS_OPEN.equals(event.status()))
                .collect(Collectors.groupingBy(
                        Alarm::service,
                        LinkedHashMap::new,
                        Collectors.toList()));
        return grouped.entrySet().stream()
                .map(entry -> new AlarmIncident(entry.getKey(), entry.getValue().size(), List.copyOf(entry.getValue())))
                .sorted(Comparator.comparingInt(AlarmIncident::openCount).reversed())
                .toList();
    }

    public List<Alarm> listRecent(int limit) {
        return events.values().stream()
                .sorted(Comparator.comparing(Alarm::triggeredAt).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    public List<Alarm> listInTimeRange(Instant from, Instant to) {
        if (from == null || to == null) {
            return List.of();
        }
        return events.values().stream()
                .filter(event -> {
                    Instant triggeredAt = event.triggeredAt();
                    return !triggeredAt.isBefore(from) && !triggeredAt.isAfter(to);
                })
                .sorted(Comparator.comparing(Alarm::triggeredAt).reversed())
                .toList();
    }

    public Optional<Alarm> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(events.get(id));
    }

    public void clear() {
        events.clear();
    }

    public void replaceAll(List<Alarm> loaded) {
        events.clear();
        loaded.forEach(event -> events.put(event.id(), event));
    }

    public void persistExisting(Alarm event) {
        events.put(event.id(), event);
        ifAvailable(sync -> sync.persist(event));
    }

    private void ifAvailable(java.util.function.Consumer<AlarmPersistence> consumer) {
        if (persistence != null) {
            persistence.ifAvailable(consumer);
        }
    }
}
