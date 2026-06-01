package com.example.sqlagent.chat;

import com.example.sqlagent.dto.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/chat")
public class ChatSessionController {

    @Autowired
    private ChatSessionService chatSessionService;

    /**
     * 创建新会话
     */
    @PostMapping("/session/create")
    public ResponseEntity<ApiResponse<ChatSession>> createSession(@RequestBody(required = false) CreateSessionRequest request) {
        try {
            String title = request != null ? request.getTitle() : null;
            ChatSession session = chatSessionService.createSession(title);
            return ResponseEntity.ok(ApiResponse.success(session));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * 获取用户的所有会话列表
     */
    @GetMapping("/sessions")
    public ResponseEntity<ApiResponse<List<ChatSession>>> getSessions() {
        try {
            List<ChatSession> sessions = chatSessionService.getUserSessions();
            return ResponseEntity.ok(ApiResponse.success(sessions));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * 删除会话
     */
    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<ApiResponse<Void>> deleteSession(@PathVariable Long sessionId) {
        try {
            chatSessionService.deleteSession(sessionId);
            return ResponseEntity.ok(ApiResponse.success(null));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * 更新会话标题
     */
    @PutMapping("/session/{sessionId}")
    public ResponseEntity<ApiResponse<ChatSession>> updateSessionTitle(
            @PathVariable Long sessionId,
            @RequestBody UpdateSessionTitleRequest request) {
        try {
            ChatSession session = chatSessionService.updateSessionTitle(sessionId, request.getTitle());
            return ResponseEntity.ok(ApiResponse.success(session));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * 创建会话请求体
     */
    public static class CreateSessionRequest {
        private String title;

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
    }

    /**
     * 更新标题请求体
     */
    public static class UpdateSessionTitleRequest {
        private String title;

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
    }
}
