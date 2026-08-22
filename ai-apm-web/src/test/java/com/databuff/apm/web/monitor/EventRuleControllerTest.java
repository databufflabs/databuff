package com.databuff.apm.web.monitor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventRuleControllerTest {

    @Test
    void delegatesRuleCrud() {
        InMemoryEventRuleStore store = new InMemoryEventRuleStore();
        EventRuleController controller = new EventRuleController(
                new EventRuleService(store),
                new AlarmStore(TestMonitorRecordIds.create()),
                new AlarmSilenceStore());

        assertThat(controller.listRules()).isEmpty();
        EventRule created = controller.createRule(new EventRuleStore.CreateRequest(
                "new rule", "demo", 0.2, "gt", true));
        assertThat(created.id()).isPositive();
        assertThat(controller.setEnabled(created.id(), false).enabled()).isFalse();
        assertThat(controller.deleteRule(created.id()).get("deleted")).isTrue();
        assertThat(controller.listEvents(5)).isEmpty();
        AlarmStore eventStore = new AlarmStore(TestMonitorRecordIds.create());
        eventStore.open("demo-order", EventRule.WAY_THRESHOLD, "critical", "breached");
        EventRuleController withEvents = new EventRuleController(
                new EventRuleService(store),
                eventStore,
                new AlarmSilenceStore());
        assertThat(withEvents.listOpenIncidents()).hasSize(1);
        assertThat(withEvents.listOpenIncidents().get(0).openCount()).isEqualTo(1);
        assertThat(controller.silenceService("demo-order", 15))
                .containsEntry("service", "demo-order");
    }

    @Test
    void rejectsMissingRuleWhenUpdatingEnabled() {
        EventRuleController controller = new EventRuleController(
                new EventRuleService(new InMemoryEventRuleStore()),
                new AlarmStore(TestMonitorRecordIds.create()),
                new AlarmSilenceStore());
        assertThatThrownBy(() -> controller.setEnabled(9999, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
