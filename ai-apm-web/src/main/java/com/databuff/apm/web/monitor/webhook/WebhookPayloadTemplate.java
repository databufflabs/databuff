package com.databuff.apm.web.monitor.webhook;

import java.util.List;
import java.util.Map;

/**
 * Payload renderer for webhook delivery. There is exactly one built-in delivery shape:
 * every POST — 1 alert or N — carries the batch
 * envelope {@code {"batchId","count","events":[...]}}; {@link #render} is its only
 * rendering entry.
 */
public interface WebhookPayloadTemplate {

    /** Stable template id referenced from channel config, e.g. {@code default}. */
    String id();

    /** Human-readable template name for config UIs. */
    String name();

    /**
     * Renders the constant batch-envelope body for one POST: {@code count} is
     * {@code alertPayloads.size()} (a single alert is just the {@code count == 1} case),
     * {@code events} carries the assembled unified alert payloads.
     */
    Map<String, Object> render(String batchId, List<Map<String, Object>> alertPayloads);
}
