package com.databuff.apm.web.monitor;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class InMemoryEventRuleStore implements EventRuleStore {

    private final Map<Long, EventRule> rules = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong(1);

    public InMemoryEventRuleStore() {
    }

    @Override
    public List<EventRule> list() {
        return new ArrayList<>(rules.values());
    }

    @Override
    public Optional<EventRule> findById(long id) {
        return Optional.ofNullable(rules.get(id));
    }

    @Override
    public EventRule save(EventRule rule) {
        if (rule.id() <= 0) {
            long id = idSequence.getAndIncrement();
            EventRule created = copyWithId(id, rule, Instant.now());
            rules.put(id, created);
            return created;
        }
        EventRule updated = copyWithId(rule.id(), rule, Instant.now());
        rules.put(rule.id(), updated);
        return updated;
    }

    @Override
    public boolean delete(long id) {
        return rules.remove(id) != null;
    }

    public void replaceAll(List<EventRule> rules) {
        this.rules.clear();
        for (EventRule rule : rules) {
            this.rules.put(rule.id(), rule);
        }
    }

    public void syncIdSequence(long nextId) {
        idSequence.set(nextId);
    }

    EventRule createInternal(CreateRequest request) {
        String metric = request.metric();
        if (metric == null || metric.isBlank()) {
            metric = EventRule.METRIC_ERROR_RATE;
        }
        return new EventRule(
                0,
                request.ruleName(),
                EventRule.CLASSIFY_SINGLE,
                request.detectionWay(),
                request.service(),
                metric,
                request.threshold(),
                normalizeComparator(request.comparator()),
                request.enabled(),
                request.queryJson(),
                Instant.now());
    }

    @Override
    public EventRule create(CreateRequest request) {
        return save(createInternal(request));
    }

    private static EventRule copyWithId(long id, EventRule rule, Instant updatedAt) {
        return new EventRule(
                id,
                rule.ruleName(),
                rule.classify() == null || rule.classify().isBlank()
                        ? EventRule.CLASSIFY_SINGLE : rule.classify(),
                rule.detectionWay() == null || rule.detectionWay().isBlank()
                        ? EventRule.WAY_THRESHOLD : rule.detectionWay(),
                rule.service(),
                rule.metric() == null || rule.metric().isBlank()
                        ? EventRule.METRIC_ERROR_RATE : rule.metric(),
                rule.threshold(),
                normalizeComparator(rule.comparator()),
                rule.enabled(),
                rule.queryJson(),
                updatedAt);
    }

    private static String normalizeComparator(String comparator) {
        return EventRule.normalizeComparator(comparator);
    }
}
