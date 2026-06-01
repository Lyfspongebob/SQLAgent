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
    private SQLAgentService sqlAgentService;

    /**
     * 发送消息并获取回复
     */
    public ChatResponse sendMessage(ChatRequest request) {
        long userId = StpUtil.getLoginIdAsLong();

        // 保存用户消息
        ChatMessage userMessage = new ChatMessage();
        userMessage.setUserId(userId);
        userMessage.setSessionId(request.getSessionId());
        userMessage.setContent(request.getMessage());
        userMessage.setRole("user");
        chatMessageRepository.save(userMessage);

        // 处理消息
        ChatResponse response = sqlAgentService.processChat(
                request.getMessage(),
                request.getSessionId(),
                request.getUseKnowledgeBase(),
                request.getUseSqlAgent(),
                request.getDataSourceConfigId()
        );

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

        return response;
    }

    /**
     * 获取会话历史
     */
    public List<ChatMessage> getSessionHistory(Long sessionId) {
        long userId = StpUtil.getLoginIdAsLong();
        return chatMessageRepository.findByUserIdAndSessionIdOrderByCreateTimeAsc(userId, sessionId);
    }

    /**
     * 获取用户的所有会话列表（按时间倒序）
     */
    public List<ChatMessage> getUserSessions() {
        long userId = StpUtil.getLoginIdAsLong();
        return chatMessageRepository.findByUserIdOrderByCreateTimeDesc(userId);
    }
}
