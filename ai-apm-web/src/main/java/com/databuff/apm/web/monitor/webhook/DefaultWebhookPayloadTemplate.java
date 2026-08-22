package com.databuff.apm.web.monitor.webhook;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in default template — the single unified delivery shape: every POST (1 alert
 * or N) carries {@code {"batchId","count","events":[...]}} ({@code events} is the native
 * batch key of the standard-event style receivers consume, and reads naturally for any
 * other receiver; {@code batchId}/{@code count} stay for retry correlation and send
 * records). A single alert is simply the {@code count == 1} case — receivers parse one
 * body shape, never a bare single object.
 */
@Component
public class DefaultWebhookPayloadTemplate implements WebhookPayloadTemplate {

    public static final String ID = "default";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "DataBuff 默认模版";
    }

    @Override
    public Map<String, Object> render(String batchId, List<Map<String, Object>> alertPayloads) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("batchId", batchId);
        body.put("count", alertPayloads.size());
        body.put("events", new java.util.ArrayList<>(alertPayloads));
        return body;
    }
}
