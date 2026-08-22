package com.databuff.apm.web.monitor.webhook;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Fixed-size ring buffer of webhook send records (in-memory delivery visibility). */
public class WebhookSendRecordStore {

    private final int capacity;
    private final ArrayDeque<WebhookSendRecord> records = new ArrayDeque<>();

    public WebhookSendRecordStore(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public synchronized void append(WebhookSendRecord record) {
        if (record == null) {
            return;
        }
        records.addFirst(record);
        while (records.size() > capacity) {
            records.removeLast();
        }
    }

    public synchronized List<WebhookSendRecord> list(int limit) {
        int size = Math.max(0, Math.min(limit, records.size()));
        List<WebhookSendRecord> copy = new ArrayList<>(records);
        return Collections.unmodifiableList(copy.subList(0, size));
    }

    public synchronized int size() {
        return records.size();
    }
}
