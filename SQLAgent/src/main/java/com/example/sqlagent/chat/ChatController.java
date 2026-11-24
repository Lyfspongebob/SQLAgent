package com.example.sqlagent.chat;

import com.example.sqlagent.dto.ApiResponse;
import com.example.sqlagent.dto.ChatRequest;
import com.example.sqlagent.dto.ChatResponse;
import com.example.sqlagent.dto.ChatMessageResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    @Autowired
    private ChatService chatService;

    @PostMapping("/send")
    public ResponseEntity<ApiResponse<ChatResponse>> sendMessage(
            @Valid @RequestBody ChatRequest request
    ) {
        ChatResponse response = chatService.sendMessage(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/history/{sessionId}")
    public ResponseEntity<ApiResponse<List<ChatMessageResponse>>> getHistory(
            @PathVariable Long sessionId) {
        List<ChatMessageResponse> messages = chatService.getSessionHistory(sessionId);
        return ResponseEntity.ok(ApiResponse.success(messages));
    }
}
