package com.databuff.apm.web.monitor;

import com.databuff.apm.common.id.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

@Component
public class MonitorRecordIdGenerator {

    private final SnowflakeIdGenerator snowflake;

    @Autowired
    public MonitorRecordIdGenerator(
            @Value("${apm.node.worker-id:#{null}}") Long configuredWorkerId) {
        this.snowflake = new SnowflakeIdGenerator(resolveWorkerId(configuredWorkerId));
    }

    MonitorRecordIdGenerator(long workerId) {
        this.snowflake = new SnowflakeIdGenerator(workerId);
    }

    public String nextAlarmId() {
        return "A" + snowflake.nextId();
    }

    public String nextEventId() {
        return "E" + snowflake.nextId();
    }

    public String nextBatchId() {
        return "B" + snowflake.nextId();
    }

    private static long resolveWorkerId(Long configuredWorkerId) {
        if (configuredWorkerId != null
                && configuredWorkerId >= 0
                && configuredWorkerId <= SnowflakeIdGenerator.maxWorkerId()) {
            return configuredWorkerId;
        }
        return workerIdFromHostname();
    }

    private static long workerIdFromHostname() {
        String hostname = System.getenv("HOSTNAME");
        if (hostname == null || hostname.isBlank()) {
            try {
                hostname = InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException ignored) {
                hostname = "local";
            }
        }
        return Math.floorMod(hostname.hashCode(), (int) SnowflakeIdGenerator.maxWorkerId() + 1);
    }
}
