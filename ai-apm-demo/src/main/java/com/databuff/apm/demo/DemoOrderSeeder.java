package com.databuff.apm.demo;

import com.databuff.apm.demo.support.OtlpLogFixture;
import com.databuff.apm.demo.support.OtlpTraceFixture;
import com.databuff.apm.demo.support.DemoTraceBatch;

/**
 * Continuous OTLP trace seeder for the service-chain demo.
 */
public final class DemoOrderSeeder {

    private DemoOrderSeeder() {
    }

    public static void main(String[] args) {
        String endpoint = System.getenv().getOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT", "http://127.0.0.1:4318");
        long traceIntervalSeconds = Long.parseLong(System.getenv().getOrDefault("SEED_INTERVAL_SECONDS", "30"));
        long jvmMetricIntervalSeconds = Long.parseLong(
                System.getenv().getOrDefault("JVM_METRIC_INTERVAL_SECONDS", "60"));
        System.out.println("[service-chain] seeding traces + correlated logs to " + endpoint + " every "
                + traceIntervalSeconds + "s, JVM metrics every " + jvmMetricIntervalSeconds + "s (pid "
                + ProcessHandle.current().pid() + ")");
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
                System.out.println("[service-chain] shutdown (signal or JVM exit)"), "demo-seeder-shutdown"));

        long sentTraces = 0;
        long sentLogs = 0;
        long sentMetrics = 0;
        long lastMetricAtMillis = 0L;
        long jvmMetricIntervalMillis = jvmMetricIntervalSeconds * 1000L;
        while (true) {
            try {
                DemoTraceBatch batch = OtlpTraceFixture.nextTraceBatch();
                int traceStatus = OtlpTraceFixture.postTraceBatch(endpoint, batch);
                if (traceStatus < 200 || traceStatus >= 300) {
                    System.err.println("[service-chain] trace OTLP HTTP " + traceStatus);
                } else {
                    sentTraces++;
                    if (sentTraces == 1 || sentTraces % 20 == 0) {
                        System.out.println("[service-chain] sent " + sentTraces + " trace batches (HTTP "
                                + traceStatus + ")");
                    }
                }

                int logStatus = OtlpLogFixture.postLogs(endpoint, batch);
                if (logStatus < 200 || logStatus >= 300) {
                    System.err.println("[service-chain] log OTLP HTTP " + logStatus);
                } else {
                    sentLogs++;
                    if (sentLogs == 1 || sentLogs % 20 == 0) {
                        System.out.println("[service-chain] sent " + sentLogs + " correlated log batches (HTTP "
                                + logStatus + ")");
                    }
                }

                long now = System.currentTimeMillis();
                if (lastMetricAtMillis == 0L || now - lastMetricAtMillis >= jvmMetricIntervalMillis) {
                    int metricStatus = OtlpTraceFixture.postMetrics(endpoint);
                    lastMetricAtMillis = now;
                    if (metricStatus < 200 || metricStatus >= 300) {
                        System.err.println("[service-chain] metric OTLP HTTP " + metricStatus);
                    } else {
                        sentMetrics++;
                        if (sentMetrics == 1 || sentMetrics % 10 == 0) {
                            System.out.println("[service-chain] sent " + sentMetrics + " JVM metric batches (HTTP "
                                    + metricStatus + ")");
                        }
                    }
                }

                Thread.sleep(traceIntervalSeconds * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("[service-chain] interrupted, exiting");
                return;
            } catch (Exception e) {
                System.err.println("[service-chain] seed failed: " + e.getMessage());
                try {
                    Thread.sleep(traceIntervalSeconds * 1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    System.out.println("[service-chain] interrupted during backoff, exiting");
                    return;
                }
            }
        }
    }
}
