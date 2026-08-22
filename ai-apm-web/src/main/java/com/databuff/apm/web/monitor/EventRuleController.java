package com.databuff.apm.web.monitor;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/alarm")
public class EventRuleController {

    private final EventRuleService eventRuleService;
    private final AlarmStore alarmStore;
    private final AlarmSilenceStore alarmSilenceStore;

    public EventRuleController(
            EventRuleService eventRuleService,
            AlarmStore alarmStore,
            AlarmSilenceStore alarmSilenceStore) {
        this.eventRuleService = eventRuleService;
        this.alarmStore = alarmStore;
        this.alarmSilenceStore = alarmSilenceStore;
    }

    @GetMapping("/rules")
    public List<EventRule> listRules() {
        return eventRuleService.listRules();
    }

    @PostMapping("/rules")
    public EventRule createRule(@RequestBody EventRuleStore.CreateRequest request) {
        return eventRuleService.createRule(request);
    }

    @PutMapping("/rules/{id}/enabled")
    public EventRule setEnabled(@PathVariable long id, @RequestParam boolean enabled) {
        return eventRuleService.updateEnabled(id, enabled)
                .orElseThrow(() -> new IllegalArgumentException("rule not found: " + id));
    }

    @DeleteMapping("/rules/{id}")
    public Map<String, Boolean> deleteRule(@PathVariable long id) {
        return Map.of("deleted", eventRuleService.deleteRule(id));
    }

    @GetMapping("/events")
    public List<Alarm> listEvents(@RequestParam(defaultValue = "50") int limit) {
        return alarmStore.listRecent(limit);
    }

    @GetMapping("/incidents")
    public List<AlarmIncident> listOpenIncidents() {
        return alarmStore.groupOpenIncidents();
    }

    @PostMapping("/silence")
    public Map<String, Object> silenceService(
            @RequestParam String service,
            @RequestParam(defaultValue = "30") long minutes) {
        alarmSilenceStore.silenceService(service, minutes);
        return Map.of("service", service, "silencedMinutes", minutes);
    }
}
