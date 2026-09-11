package com.databuff.apm.demo.support;

import com.databuff.apm.demo.fault.DemoConfigurationChange;
import com.databuff.apm.demo.fault.DemoFaultSnapshot;
import org.apache.skywalking.apm.network.language.agent.v3.JVMMetricCollection;
import org.apache.skywalking.apm.network.logging.v3.LogData;

import java.util.List;

/** Push Trace + JVM + Log fixtures to DataBuff SkyWalking gRPC receiver. */
public final class SkyWalkingDemoSeeder {

    private static volatile boolean managementRegistered;

    private SkyWalkingDemoSeeder() {
    }

    public static void seedOnce(String target) throws Exception {
        seedOnce(target, DemoFaultSnapshot.normal(), null, true);
    }

    public static void seedOnce(
            String target,
            DemoFaultSnapshot fault,
            DemoConfigurationChange configurationChange,
            boolean emitMetrics) throws Exception {
        ensureManagementRegistered(target);
        DemoSkyWalkingBatch batch = SkyWalkingTraceFixture.nextBatch(fault);
        SkyWalkingGrpcExporter.postSegments(target, batch.segments());
        List<LogData> logs = new java.util.ArrayList<>(SkyWalkingLogFixture.logsForBatch(batch));
        if (configurationChange != null) {
            logs.add(SkyWalkingLogFixture.configurationLog(configurationChange));
        }
        SkyWalkingGrpcExporter.postLogs(target, logs);
        if (emitMetrics) {
            for (JVMMetricCollection collection : SkyWalkingJvmFixture.nextCollections()) {
                SkyWalkingGrpcExporter.postJvmMetrics(target, collection);
            }
        }
    }

    private static void ensureManagementRegistered(String target) throws InterruptedException {
        if (managementRegistered) {
            return;
        }
        synchronized (SkyWalkingDemoSeeder.class) {
            if (managementRegistered) {
                return;
            }
            int attempt = 0;
            while (true) {
                try {
                    SkyWalkingGrpcExporter.registerInstanceOnce(target);
                    managementRegistered = true;
                    return;
                } catch (Exception e) {
                    attempt++;
                    if (attempt == 1 || attempt % 5 == 0) {
                        System.err.println("[skywalking-demo] management register failed (attempt "
                                + attempt + "): " + e.getMessage());
                    }
                    Thread.sleep(5_000L);
                }
            }
        }
    }

    public static void runLoop(String target, long traceIntervalSeconds, long jvmMetricIntervalSeconds) throws InterruptedException {
        ensureManagementRegistered(target);
        long sentTraces = 0;
        long sentLogs = 0;
        long sentMetrics = 0;
        long lastMetricAtMillis = 0L;
        long jvmMetricIntervalMillis = jvmMetricIntervalSeconds * 1000L;
        while (true) {
            try {
                DemoSkyWalkingBatch batch = SkyWalkingTraceFixture.nextBatch(DemoFaultSnapshot.normal());
                SkyWalkingGrpcExporter.postSegments(target, batch.segments());
                sentTraces++;
                if (sentTraces == 1 || sentTraces % 20 == 0) {
                    System.out.println("[skywalking-demo] sent " + sentTraces + " trace segment batches");
                }

                List<LogData> logs = SkyWalkingLogFixture.logsForBatch(batch);
                SkyWalkingGrpcExporter.postLogs(target, logs);
                sentLogs++;
                if (sentLogs == 1 || sentLogs % 20 == 0) {
                    System.out.println("[skywalking-demo] sent " + sentLogs + " log streams (" + logs.size() + " records)");
                }

                long now = System.currentTimeMillis();
                if (lastMetricAtMillis == 0L || now - lastMetricAtMillis >= jvmMetricIntervalMillis) {
                    for (JVMMetricCollection collection : SkyWalkingJvmFixture.nextCollections()) {
                        SkyWalkingGrpcExporter.postJvmMetrics(target, collection);
                    }
                    lastMetricAtMillis = now;
                    sentMetrics++;
                    if (sentMetrics == 1 || sentMetrics % 10 == 0) {
                        System.out.println("[skywalking-demo] sent " + sentMetrics + " JVM metric batches");
                    }
                }

                Thread.sleep(traceIntervalSeconds * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("[skywalking-demo] interrupted, exiting");
                return;
            } catch (Exception e) {
                System.err.println("[skywalking-demo] seed failed: " + e.getMessage());
                Thread.sleep(traceIntervalSeconds * 1000L);
            }
        }
    }
}
