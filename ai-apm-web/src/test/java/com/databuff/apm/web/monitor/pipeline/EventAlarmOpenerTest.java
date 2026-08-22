package com.databuff.apm.web.monitor.pipeline;

import com.databuff.apm.web.ai.TestBeanSupport;
import com.databuff.apm.web.monitor.Alarm;
import com.databuff.apm.web.monitor.AlarmStore;
import com.databuff.apm.web.monitor.EventRule;
import com.databuff.apm.web.persistence.EventPersistence;
import com.databuff.apm.web.monitor.TestMonitorRecordIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventAlarmOpenerTest {

    private AlarmStore alarmStore;
    private EventPersistence eventPersistence;
    private EventAlarmOpener opener;

    @BeforeEach
    void setUp() {
        alarmStore = new AlarmStore(TestMonitorRecordIds.create());
        eventPersistence = mock(EventPersistence.class);
        when(eventPersistence.isPersistenceEnabled()).thenReturn(true);
        opener = TestBeanSupport.eventAlarmOpener(alarmStore, eventPersistence);
    }

    @Test
    void opensOneAlarmPerAbnormalEvent() {
        EventRecord event = abnormalEvent("E1", "checkout", "service checkout error rate 10.00%");

        Optional<Alarm> opened = opener.openForEvent(event);

        assertThat(opened).isPresent();
        assertThat(opened.get().message()).isEqualTo("service checkout error rate 10.00%");
        assertThat(opened.get().status()).isEqualTo(Alarm.STATUS_RESOLVED);
        assertThat(opened.get().resolvedAt()).isEqualTo(opened.get().triggeredAt());
        verify(eventPersistence).linkToAlarm("E1", opened.get().id());
    }

    @Test
    void eachEventCreatesSeparateAlarm() {
        opener.openForEvent(abnormalEvent("E1", "checkout", "first alert"));
        opener.openForEvent(abnormalEvent("E2", "checkout", "second alert"));

        assertThat(alarmStore.listOpen()).isEmpty();
        assertThat(alarmStore.listRecent(10)).hasSize(2);
    }

    @Test
    void ignoresNormalEvents() {
        EventRecord event = new EventRecord(
                "E1",
                1L,
                "error rate rule",
                "checkout",
                EventRule.WAY_THRESHOLD,
                "critical",
                EventRecord.STATUS_RECOVER,
                "recovered",
                "checkout",
                false,
                Instant.now());

        assertThat(opener.openForEvent(event)).isEmpty();
        assertThat(alarmStore.listOpen()).isEmpty();
    }

    @Test
    void recordsRecoveryEventAsResolvedAlarm() {
        EventRecord event = new EventRecord(
                "E2", 1L, "error rate rule", "checkout", EventRule.WAY_THRESHOLD,
                "critical", EventRecord.STATUS_RECOVER, "recovered", "checkout", false,
                Instant.now());

        Optional<Alarm> recovered = opener.openRecoveryForEvent(event);

        assertThat(recovered).isPresent();
        assertThat(recovered.get().status()).isEqualTo(Alarm.STATUS_RESOLVED);
        verify(eventPersistence).linkToAlarm("E2", recovered.get().id());
    }

    private static EventRecord abnormalEvent(String id, String service, String message) {
        return new EventRecord(
                id,
                1L,
                "error rate rule",
                service,
                EventRule.WAY_THRESHOLD,
                "critical",
                EventRecord.STATUS_TRIGGER,
                message,
                service,
                false,
                Instant.now());
    }
}
