package com.example.sqlagent.chat;

import cn.dev33.satoken.stp.StpUtil;
import com.example.sqlagent.agent.SQLAgentService;
import com.example.sqlagent.dto.ChatRequest;
import com.example.sqlagent.dto.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ChatService {

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private ChatSessionRepository chatSessionRepository;

    @Autowired
    private SQLAgentService sqlAgentService;

    /**
     * 发送消息并获取回复
     */
    public ChatResponse sendMessage(ChatRequest request) {
        long userId = StpUtil.getLoginIdAsLong();

        // 先处理消息（调用 SQL Agent 或 AI），避免 JPA 连接被 OSIV 占用
        ChatResponse response = sqlAgentService.processChat(
                request.getMessage(),
                request.getSessionId(),
                request.getUseKnowledgeBase(),
                request.getUseSqlAgent(),
                request.getDataSourceConfigId()
        );

        // 保存用户消息
        ChatMessage userMessage = new ChatMessage();
        userMessage.setUserId(userId);
        userMessage.setSessionId(request.getSessionId());
        userMessage.setContent(request.getMessage());
        userMessage.setRole("user");
        chatMessageRepository.save(userMessage);

        // 如果是该会话的第一条消息，自动更新会话标题
        List<ChatMessage> existingMessages = chatMessageRepository
                .findByUserIdAndSessionIdOrderByCreateTimeAsc(userId, request.getSessionId());
        if (existingMessages.size() == 1) {
            // 只有刚插入的这一条消息，说明是首条消息
            String title = request.getMessage().length() > 30
                    ? request.getMessage().substring(0, 30) + "..."
                    : request.getMessage();
            chatSessionRepository.findById(request.getSessionId()).ifPresent(session -> {
                session.setTitle(title);
                session.setUpdateTime(java.time.LocalDateTime.now());
                chatSessionRepository.save(session);
            });
        }

        // 保存 AI 回复
        ChatMessage assistantMessage = new ChatMessage();
        assistantMessage.setUserId(userId);
        assistantMessage.setSessionId(request.getSessionId());
        assistantMessage.setContent(response.getReply());
        assistantMessage.setRole("assistant");
        assistantMessage.setSqlGenerated(response.getSqlGenerated());
        assistantMessage.setSqlResults(response.getSqlResults() != null ?
                response.getSqlResults().toString() : null);
        assistantMessage.setSqlSuccess(response.getSqlExecutionSuccess());
        assistantMessage.setFromKnowledgeBase(response.getFromKnowledgeBase());
        chatMessageRepository.save(assistantMessage);

        // 更新会话的更新时间
        chatSessionRepository.findById(request.getSessionId()).ifPresent(session -> {
            session.setUpdateTime(java.time.LocalDateTime.now());
            chatSessionRepository.save(session);
        });

        return response;
    }

    /**
     * 获取会话历史
     */
    public List<ChatMessage> getSessionHistory(Long sessionId) {
        long userId = StpUtil.getLoginIdAsLong();
        return chatMessageRepository.findByUserIdAndSessionIdOrderByCreateTimeAsc(userId, sessionId);
    }
}
