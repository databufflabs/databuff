package com.databuff.apm.demo;

import com.databuff.apm.demo.fault.ChangeEventPublisher;
import com.databuff.apm.demo.fault.DemoConfigurationChange;
import com.databuff.apm.demo.fault.DemoFaultController;
import com.databuff.apm.demo.fault.DemoFaultHttpServer;
import com.databuff.apm.demo.fault.DemoFaultSnapshot;
import com.databuff.apm.demo.fault.KafkaChangeEventPublisher;
import com.databuff.apm.demo.fault.UnavailableChangeEventPublisher;
import com.databuff.apm.demo.support.DemoTraceBatch;
import com.databuff.apm.demo.support.OtlpLogFixture;
import com.databuff.apm.demo.support.OtlpTraceFixture;
import com.databuff.apm.demo.support.SkyWalkingDemoSeeder;

/**
 * Continuous demo seeder for OTLP and/or SkyWalking native protocol.
 *
 * <p>{@code SEED_PROTOCOL}: {@code otlp} (default), {@code skywalking}, or {@code both}.
 * The fixed fault API is always available; starting a fault additionally requires
 * {@code DEMO_CHANGE_KAFKA_BOOTSTRAP_SERVERS}.
 */
public final class DemoOrderSeeder {

    private DemoOrderSeeder() {
    }

    public static void main(String[] args) throws Exception {
        String protocol = env("SEED_PROTOCOL", "otlp").trim().toLowerCase();
        long traceIntervalSeconds = positiveLong("SEED_INTERVAL_SECONDS", 30L);
        long jvmMetricIntervalSeconds = positiveLong("JVM_METRIC_INTERVAL_SECONDS", 60L);
        boolean seedOtlp = protocol.equals("otlp") || protocol.equals("both");
        boolean seedSkyWalking = protocol.equals("skywalking") || protocol.equals("both");
        if (!seedOtlp && !seedSkyWalking) {
            throw new IllegalArgumentException(
                    "SEED_PROTOCOL must be otlp, skywalking, or both (was " + protocol + ")");
        }

        String otlpEndpoint = env("OTEL_EXPORTER_OTLP_ENDPOINT", "http://127.0.0.1:4318");
        String skyWalkingTarget = env("SKYWALKING_GRPC_TARGET", "127.0.0.1:11800");
        String kafkaTopic = env(
                "DEMO_CHANGE_KAFKA_TOPIC", KafkaChangeEventPublisher.DEFAULT_TOPIC);
        String kafkaBootstrap = env("DEMO_CHANGE_KAFKA_BOOTSTRAP_SERVERS", "");
        ChangeEventPublisher publisher = kafkaBootstrap.isBlank()
                ? new UnavailableChangeEventPublisher(kafkaTopic)
                : new KafkaChangeEventPublisher(kafkaBootstrap, kafkaTopic);
        DemoFaultController faultController = new DemoFaultController(publisher);
        String apiBind = env("DEMO_FAULT_API_BIND", "0.0.0.0");
        int apiPort = positiveInt("DEMO_FAULT_API_PORT", 18080);
        DemoFaultHttpServer api = new DemoFaultHttpServer(apiBind, apiPort, faultController);
        api.start();

        System.out.println("[demo-seeder] protocol=" + protocol + " pid="
                + ProcessHandle.current().pid() + " traceInterval=" + traceIntervalSeconds
                + "s jvmInterval=" + jvmMetricIntervalSeconds + "s");
        if (seedOtlp) {
            System.out.println("[demo-seeder] OTLP -> " + otlpEndpoint);
        }
        if (seedSkyWalking) {
            System.out.println("[demo-seeder] SkyWalking gRPC -> " + skyWalkingTarget);
        }
        System.out.println("[demo-seeder] fault API -> http://" + apiBind + ":" + api.port()
                + DemoFaultHttpServer.TRIGGER_PATH);
        System.out.println(kafkaBootstrap.isBlank()
                ? "[demo-seeder] fault trigger disabled until DEMO_CHANGE_KAFKA_BOOTSTRAP_SERVERS is configured"
                : "[demo-seeder] configuration changes -> Kafka " + kafkaBootstrap + "/" + kafkaTopic);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[demo-seeder] shutdown (signal or JVM exit)");
            api.close();
            faultController.close();
        }, "demo-seeder-shutdown"));

        runLoop(
                seedOtlp,
                seedSkyWalking,
                otlpEndpoint,
                skyWalkingTarget,
                traceIntervalSeconds,
                jvmMetricIntervalSeconds,
                faultController);
    }

    private static void runLoop(
            boolean seedOtlp,
            boolean seedSkyWalking,
            String otlpEndpoint,
            String skyWalkingTarget,
            long traceIntervalSeconds,
            long jvmMetricIntervalSeconds,
            DemoFaultController faultController) {
        long sentOtlp = 0L;
        long sentSkyWalking = 0L;
        long sentMetrics = 0L;
        long lastMetricAtMillis = 0L;
        long jvmMetricIntervalMillis = jvmMetricIntervalSeconds * 1000L;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                long now = System.currentTimeMillis();
                boolean emitMetrics = lastMetricAtMillis == 0L
                        || now - lastMetricAtMillis >= jvmMetricIntervalMillis;
                DemoFaultSnapshot fault = faultController.snapshot();
                DemoConfigurationChange configurationLog =
                        faultController.pendingConfigurationLog();

                if (seedOtlp) {
                    DemoTraceBatch batch = OtlpTraceFixture.nextTraceBatch(fault);
                    requireSuccess("OTLP trace", OtlpTraceFixture.postTraceBatch(otlpEndpoint, batch));
                    requireSuccess("OTLP log", OtlpLogFixture.postLogs(otlpEndpoint, batch));
                    if (configurationLog != null) {
                        requireSuccess(
                                "OTLP configuration log",
                                OtlpLogFixture.postConfigurationLog(otlpEndpoint, configurationLog));
                    }
                    if (emitMetrics) {
                        requireSuccess("OTLP metric", OtlpTraceFixture.postMetrics(otlpEndpoint));
                    }
                    sentOtlp++;
                    if (sentOtlp == 1 || sentOtlp % 20 == 0) {
                        System.out.println("[demo-seeder] OTLP batches sent=" + sentOtlp
                                + " profile=" + (fault.active() ? "CACHE_TTL_ZERO" : "NORMAL"));
                    }
                }

                if (seedSkyWalking) {
                    SkyWalkingDemoSeeder.seedOnce(
                            skyWalkingTarget, fault, configurationLog, emitMetrics);
                    sentSkyWalking++;
                    if (sentSkyWalking == 1 || sentSkyWalking % 20 == 0) {
                        System.out.println("[demo-seeder] SkyWalking batches sent="
                                + sentSkyWalking + " profile="
                                + (fault.active() ? "CACHE_TTL_ZERO" : "NORMAL"));
                    }
                }

                if (configurationLog != null) {
                    faultController.acknowledgeConfigurationLog(configurationLog);
                }
                if (emitMetrics) {
                    lastMetricAtMillis = now;
                    sentMetrics++;
                    if (sentMetrics == 1 || sentMetrics % 10 == 0) {
                        System.out.println("[demo-seeder] JVM metric batches sent=" + sentMetrics);
                    }
                }
                Thread.sleep(traceIntervalSeconds * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.err.println("[demo-seeder] seed failed: " + e.getMessage());
                try {
                    Thread.sleep(traceIntervalSeconds * 1000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        System.out.println("[demo-seeder] interrupted, exiting");
    }

    private static void requireSuccess(String label, int status) {
        if (status < 200 || status >= 300) {
            throw new IllegalStateException(label + " returned HTTP " + status);
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }

    private static long positiveLong(String name, long fallback) {
        String raw = env(name, Long.toString(fallback)).trim();
        long value = Long.parseLong(raw);
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static int positiveInt(String name, int fallback) {
        long value = positiveLong(name, fallback);
        if (value > 65_535L) {
            throw new IllegalArgumentException(name + " must be <= 65535");
        }
        return (int) value;
    }
}
