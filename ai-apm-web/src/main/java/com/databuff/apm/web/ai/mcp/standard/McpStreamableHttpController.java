package com.databuff.apm.web.ai.mcp.standard;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class McpStreamableHttpController {

    private final McpJsonRpcService jsonRpcService;

    public McpStreamableHttpController(McpJsonRpcService jsonRpcService) {
        this.jsonRpcService = jsonRpcService;
    }

    @PostMapping(
            value = "/mcp",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> post(@RequestBody Map<String, Object> body) {
        if (!body.containsKey("id") && body.get("method") instanceof String) {
            return ResponseEntity.accepted().build();
        }
        return ResponseEntity.ok(jsonRpcService.handle(body));
    }

    @GetMapping("/mcp")
    public ResponseEntity<Void> get() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .allow(HttpMethod.POST)
                .build();
    }
}
