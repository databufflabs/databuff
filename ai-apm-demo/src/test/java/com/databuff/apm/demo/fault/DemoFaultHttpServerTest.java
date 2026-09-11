package com.databuff.apm.demo.fault;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

class DemoFaultHttpServerTest {

    @Test
    void servesTheFixedIdempotentFaultApi() throws Exception {
        ChangeEventPublisher publisher = new ChangeEventPublisher() {
            @Override
            public void publish(DemoConfigurationChange change) {
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String topic() {
                return "buffops.demo.alerts";
            }
        };
        DemoFaultController controller = new DemoFaultController(publisher);
        DemoFaultHttpServer server = new DemoFaultHttpServer("127.0.0.1", 0, controller);
        server.start();
        try {
            HttpClient client = HttpClient.newHttpClient();
            URI trigger = URI.create("http://127.0.0.1:" + server.port()
                    + DemoFaultHttpServer.TRIGGER_PATH);
            HttpRequest post = HttpRequest.newBuilder(trigger)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            HttpResponse<String> started = client.send(post, HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> reused = client.send(post, HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> current = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port()
                                    + DemoFaultHttpServer.CURRENT_PATH))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(started.statusCode()).isEqualTo(201);
            assertThat(started.body()).contains("\"state\":\"ACTIVE\"")
                    .contains("\"durationSeconds\":180")
                    .contains("\"topic\":\"buffops.demo.alerts\"");
            assertThat(reused.statusCode()).isEqualTo(200);
            assertThat(reused.body()).contains("\"reused\":true");
            assertThat(current.statusCode()).isEqualTo(200);
            assertThat(current.body()).contains("\"state\":\"ACTIVE\"");
        } finally {
            server.close();
            controller.close();
        }
    }
}
