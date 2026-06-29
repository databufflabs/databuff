package com.databuff.apm.web.ai.mcp.standard;

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
    public Map<String, Object> post(@RequestBody Map<String, Object> body) {
        return jsonRpcService.handle(body);
    }

    @GetMapping("/mcp")
    public ResponseEntity<Map<String, String>> get() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Map.of("error", "Use POST with application/json for JSON-RPC requests"));
    }
}
