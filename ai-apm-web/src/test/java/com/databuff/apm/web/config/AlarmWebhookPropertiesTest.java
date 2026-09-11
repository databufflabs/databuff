package com.databuff.apm.web.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AlarmWebhookPropertiesTest {

    @TempDir
    Path tempDir;

    @Test
    void bindsArbitraryHeaderNamesWithoutAChannelSpecificTokenProperty() {
        new ApplicationContextRunner()
                .withUserConfiguration(MonitorConfiguration.class)
                .withPropertyValues(
                        "apm.alarm.webhook.url=https://example.test/hook",
                        "apm.alarm.webhook.headers[X-BuffOps-Token]=bo-test",
                        "apm.alarm.webhook.headers[Authorization]=Bearer generic-test")
                .run(context -> {
                    AlarmWebhookProperties properties =
                            context.getBean(AlarmWebhookProperties.class);
                    assertThat(properties.headers())
                            .containsEntry("X-BuffOps-Token", "bo-test")
                            .containsEntry("Authorization", "Bearer generic-test");
                });
    }

    @Test
    void readsTheReceiverTokenFromAReadOnlyMountedFile() throws Exception {
        Path token = tempDir.resolve("databuff.token");
        Files.writeString(token, "bo_test_secret\n");
        new ApplicationContextRunner()
                .withUserConfiguration(MonitorConfiguration.class)
                .withPropertyValues(
                        "apm.alarm.webhook.url=https://example.test/hook",
                        "apm.alarm.webhook.token-file=" + token)
                .run(context -> {
                    AlarmWebhookProperties properties =
                            context.getBean(AlarmWebhookProperties.class);
                    assertThat(properties.headers())
                            .containsEntry("X-BuffOps-Token", "bo_test_secret");
                });
    }
}
