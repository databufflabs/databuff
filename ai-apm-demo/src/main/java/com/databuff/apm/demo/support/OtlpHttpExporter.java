package com.databuff.apm.demo.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

final class OtlpHttpExporter {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private OtlpHttpExporter() {
    }

    static int postProtobuf(String ingestBaseUrl, String path, byte[] body) throws Exception {
        String url = ingestBaseUrl.replaceAll("/$", "") + path;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-protobuf")
                .header("Authorization", "Basic YWRtaW5AZXhhbXBsZS5jb206T3Blbk9ic2VydmVAMjAyNg==")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<Void> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
        return response.statusCode();
    }
}
