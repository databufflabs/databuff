package com.databuff.apm.ingest.component;

import com.databuff.apm.common.platform.PlatformMetricNames;
import com.databuff.apm.ingest.event.MetricEvent;
import com.databuff.apm.ingest.metric.OtlpMetricDirectWriter;
import com.databuff.apm.ingest.pipeline.component.AbstractComponent;
import com.databuff.apm.ingest.pipeline.pool.TaskPool;
import com.databuff.apm.ingest.pipeline.shard.HashShardingStrategy;
import com.databuff.apm.ingest.pipeline.task.AsyncTask;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent OptimizedMetric 组件：接收 {@link MetricEvent} 后转交 {@link AggregateComponent} 聚合写表。
 * OTLP 指标由 {@link OtlpMetricDirectWriter} 直接写 Doris。
 */
public final class MetricComponent extends AbstractComponent<MetricComponent.MetricTask> {

    private final AggregateComponent aggregateComponent;
    private final int bufferSize;
    private final AtomicLong received = new AtomicLong();

    public MetricComponent(AggregateComponent aggregateComponent) {
        this(aggregateComponent, 1024);
    }

    public MetricComponent(AggregateComponent aggregateComponent, int bufferSize) {
        this.aggregateComponent = aggregateComponent;
        this.bufferSize = Math.max(16, bufferSize);
    }

    @Override
    protected String getName() {
        return "metric";
    }

    @Override
    protected TaskPool<MetricTask> generateTaskPool(int taskSize) {
        MetricTask[] tasks = new MetricTask[taskSize];
        for (int i = 0; i < taskSize; i++) {
            tasks[i] = new MetricTask(i);
        }
        return new TaskPool<>(new HashShardingStrategy(), tasks);
    }

    public long receivedCount() {
        return received.get();
    }

    final class MetricTask extends AsyncTask {

        MetricTask(int taskIndex) {
            super(bufferSize, taskIndex, PlatformMetricNames.PIPELINE_METRIC);
        }

        @Override
        protected void processEvent(Object key, Object event) {
            if (event instanceof MetricEvent metricEvent) {
                received.incrementAndGet();
                aggregateComponent.acceptFromMetric(String.valueOf(key), metricEvent);
            }
        }
    }
}
