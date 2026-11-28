package com.example.sqlagent.chat;

import cn.dev33.satoken.stp.StpUtil;
import com.example.sqlagent.dto.ApiResponse;
import com.example.sqlagent.dto.ChatRequest;
import com.example.sqlagent.dto.ChatResponse;
import com.example.sqlagent.dto.ChatMessageResponse;
import com.example.sqlagent.dto.AgentStepResponse;
import com.example.sqlagent.error.AppException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    @Autowired
    private ChatService chatService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    @Qualifier("agentExecutor")
    private Executor agentExecutor;

    @PostMapping("/send")
    public ResponseEntity<ApiResponse<ChatResponse>> sendMessage(
            @Valid @RequestBody ChatRequest request
    ) {
        ChatResponse response = chatService.sendMessage(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping(value = "/send/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessageStream(
            @Valid @RequestBody ChatRequest request
    ) {
        long userId = StpUtil.getLoginIdAsLong();
        SseEmitter emitter = new SseEmitter(10 * 60_000L);

        agentExecutor.execute(() -> {
            try {
                ChatResponse response = chatService.sendMessageForUser(
                        userId,
                        request,
                        step -> sendEvent(emitter, "step", step)
                );
                sendEvent(emitter, "result", response);
                emitter.complete();
            } catch (Exception exception) {
                sendEvent(emitter, "error", errorPayload(exception));
                emitter.complete();
            }
        });
        return emitter;
    }

    @GetMapping("/history/{sessionId}")
    public ResponseEntity<ApiResponse<List<ChatMessageResponse>>> getHistory(
            @PathVariable Long sessionId) {
        List<ChatMessageResponse> messages = chatService.getSessionHistory(sessionId);
        return ResponseEntity.ok(ApiResponse.success(messages));
    }

    private void sendEvent(SseEmitter emitter, String name, Object payload) {
        try {
            emitter.send(
                    SseEmitter.event()
                            .name(name)
                            .data(
                                    objectMapper.writeValueAsString(payload),
                                    MediaType.TEXT_PLAIN
                            )
            );
        } catch (Exception ignored) {
            // 浏览器取消请求后无需继续推送。
        }
    }

    private Map<String, Object> errorPayload(Exception exception) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", exception.getMessage());
        if (exception instanceof AppException appException) {
            payload.put("errorCode", appException.errorCode().name());
            payload.put("details", appException.details());
        } else {
            payload.put("errorCode", "INTERNAL_SERVER_ERROR");
            payload.put("details", Map.of());
        }
        return payload;
    }
}
