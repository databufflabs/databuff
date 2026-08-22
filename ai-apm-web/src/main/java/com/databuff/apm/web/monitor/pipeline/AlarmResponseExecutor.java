package com.databuff.apm.web.monitor.pipeline;

import com.databuff.apm.web.monitor.Alarm;
import com.databuff.apm.web.monitor.NotifyChannelService;
import org.springframework.stereotype.Component;

/** Single alarm-response funnel. Webhook routing comes only from application config. */
@Component
public class AlarmResponseExecutor {

    private final NotifyChannelService notifyChannelService;

    public AlarmResponseExecutor(NotifyChannelService notifyChannelService) {
        this.notifyChannelService = notifyChannelService;
    }

    public void dispatch(Alarm alarm, EventRecord event) {
        notifyChannelService.notifyAlert(alarm, event);
    }
}
