package com.databuff.apm.demo.fault;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/** Minimal local control API for the single fixed demo scenario. */
public final class DemoFaultHttpServer implements AutoCloseable {

    public static final String TRIGGER_PATH =
            "/api/v1/demo/fault-runs/service-b-order-cache-ttl-zero";
    public static final String CURRENT_PATH = "/api/v1/demo/fault-runs/current";
    private static final int MAX_BODY_BYTES = 4096;

    private final HttpServer server;
    private final DemoFaultController controller;

    public DemoFaultHttpServer(String bindAddress, int port, DemoFaultController controller)
            throws IOException {
        this.controller = controller;
        this.server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        this.server.createContext(TRIGGER_PATH, this::trigger);
        this.server.createContext(CURRENT_PATH, this::current);
        this.server.createContext("/health", this::health);
        this.server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "demo-fault-http");
            thread.setDaemon(true);
            return thread;
        }));
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void trigger(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            send(exchange, 405, "{\"error\":\"method not allowed\"}", true);
            return;
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            send(exchange, 413, "{\"error\":\"request body too large\"}", false);
            return;
        }
        String request = new String(body, StandardCharsets.UTF_8).trim();
        if (!request.isEmpty() && !"{}".equals(request)) {
            send(exchange, 400,
                    "{\"error\":\"this fixed scenario accepts only an empty JSON object\"}",
                    false);
            return;
        }
        try {
            DemoFaultController.TriggerResult result = controller.trigger();
            send(exchange, result.reused() ? 200 : 201, result.toJson(), false);
        } catch (DemoFaultController.FaultStartException e) {
            send(exchange, 503, errorJson(e.getMessage()), false);
        }
    }

    private void current(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            send(exchange, 405, "{\"error\":\"method not allowed\"}", true);
            return;
        }
        send(exchange, 200, controller.currentJson(), false);
    }

    private void health(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            send(exchange, 405, "{\"error\":\"method not allowed\"}", true);
            return;
        }
        send(exchange, 200, "{\"status\":\"UP\"}", false);
    }

    private static void send(HttpExchange exchange, int status, String json, boolean allowHeader)
            throws IOException {
        byte[] response = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (allowHeader) {
            exchange.getResponseHeaders().set("Allow", "GET, POST");
        }
        exchange.sendResponseHeaders(status, response.length);
        try (var output = exchange.getResponseBody()) {
            output.write(response);
        }
    }

    private static String errorJson(String message) {
        StringBuilder json = new StringBuilder("{\"error\":");
        DemoConfigurationChange.quoted(json, message == null ? "fault start failed" : message);
        return json.append('}').toString();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
