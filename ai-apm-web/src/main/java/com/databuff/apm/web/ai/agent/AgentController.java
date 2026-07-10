package com.databuff.apm.web.ai.agent;

import com.databuff.apm.web.ai.platform.task.ExpertTask;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ai")
public class AgentController {

    private final AgentBrainService agentBrainService;
    private final ChatRequestContextResolver chatRequestContextResolver;

    public AgentController(
            AgentBrainService agentBrainService,
            ChatRequestContextResolver chatRequestContextResolver) {
        this.agentBrainService = agentBrainService;
        this.chatRequestContextResolver = chatRequestContextResolver;
    }

    @PostMapping("/chat")
    public AgentBrainService.ChatSubmitResponse chat(
            HttpServletRequest request,
            @RequestBody AgentBrainService.ChatRequest body) {
        AgentBrainService.ChatRequest chatRequest = chatRequestContextResolver.enrich(request, body);
        if (chatRequest != null && chatRequest.streamEnabled()) {
            throw new AiPlatformChatException(
                    "stream_disabled",
                    400,
                    "Platform chat uses submit + poll; set stream=false");
        }
        return agentBrainService.submitChat(chatRequest);
    }

    @PostMapping("/chat/submit")
    public AgentBrainService.ChatSubmitResponse submit(
            HttpServletRequest request,
            @RequestBody AgentBrainService.ChatRequest body) {
        return agentBrainService.submitChat(chatRequestContextResolver.enrich(request, body));
    }

    @GetMapping("/sessions/count")
    public Map<String, Object> sessionCount() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("total", agentBrainService.countSessions());
        response.put("status", 200);
        response.put("message", "SUCCESS");
        return response;
    }

    @GetMapping("/sessions")
    public Map<String, Object> sessions(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit) {
        return agentBrainService.listSessions(offset, limit);
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public AiSessionStore.MessagePollResponse messages(
            @PathVariable String sessionId,
            @RequestParam(required = false) String afterMessageId) {
        return agentBrainService.pollMessages(sessionId, afterMessageId);
    }

    @PostMapping("/sessions/{sessionId}/abort")
    public AgentBrainService.ChatAbortResponse abort(@PathVariable String sessionId) {
        return agentBrainService.abortChat(sessionId);
    }

    @GetMapping("/tasks/{taskId}")
    public ExpertTask task(@PathVariable String taskId) {
        return agentBrainService.expertTask(taskId)
                .orElseThrow(() -> AiPlatformChatException.taskNotFound(taskId));
    }

    @GetMapping("/sessions/{sessionId}/tasks")
    public List<ExpertTask> sessionTasks(@PathVariable String sessionId) {
        return agentBrainService.sessionExpertTasks(sessionId);
    }

    @ExceptionHandler(AiPlatformChatException.class)
    public ResponseEntity<Map<String, Object>> handlePlatformChatException(AiPlatformChatException exception) {
        return ResponseEntity.status(HttpStatus.valueOf(exception.httpStatus()))
                .body(Map.of(
                        "error", exception.errorCode(),
                        "message", exception.getMessage()));
    }
}
