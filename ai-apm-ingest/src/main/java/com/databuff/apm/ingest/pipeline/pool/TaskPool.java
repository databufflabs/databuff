package com.databuff.apm.ingest.pipeline.pool;

import com.databuff.apm.ingest.pipeline.shard.RoundRobinStrategy;
import com.databuff.apm.ingest.pipeline.shard.ShardingStrategy;
import com.databuff.apm.ingest.pipeline.task.Task;

import java.util.Objects;

public final class TaskPool<T extends Task> {

    private final int taskSize;
    private final T[] tasks;
    private final ShardingStrategy shardingStrategy;

    @SafeVarargs
    public TaskPool(ShardingStrategy shardingStrategy, T... tasks) {
        Objects.requireNonNull(tasks);
        if (tasks.length == 0) {
            throw new IllegalArgumentException("tasks required");
        }
        this.tasks = tasks;
        this.taskSize = tasks.length;
        this.shardingStrategy = shardingStrategy == null ? new RoundRobinStrategy() : shardingStrategy;
    }

    public boolean handleEvent(Object key, Object event) {
        int target = taskSize == 1 ? 0 : shardingStrategy.chooseTask(key);
        return tasks[target].handleEvent(key, event);
    }

    public void init() {
        shardingStrategy.init(taskSize);
        for (T task : tasks) {
            task.init();
        }
    }

    public void close() {
        for (T task : tasks) {
            task.close();
        }
    }

    public int getTaskSize() {
        return taskSize;
    }

    /** Snapshot of pooled tasks (same array instance; do not mutate). */
    public T[] tasks() {
        return tasks;
    }
}
