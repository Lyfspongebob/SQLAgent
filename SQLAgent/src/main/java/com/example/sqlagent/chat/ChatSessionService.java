package com.example.sqlagent.chat;

import cn.dev33.satoken.stp.StpUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ChatSessionService {

    @Autowired
    private ChatSessionRepository chatSessionRepository;

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    /**
     * 创建新会话
     */
    public ChatSession createSession(String title) {
        long userId = StpUtil.getLoginIdAsLong();
        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setTitle(title != null && !title.isEmpty() ? title : "新对话");
        session.setCreateTime(LocalDateTime.now());
        session.setUpdateTime(LocalDateTime.now());
        return chatSessionRepository.save(session);
    }

    /**
     * 获取用户的所有会话列表
     */
    public List<ChatSession> getUserSessions() {
        long userId = StpUtil.getLoginIdAsLong();
        return chatSessionRepository.findByUserIdOrderByUpdateTimeDesc(userId);
    }

    /**
     * 删除会话及其所有消息
     */
    @Transactional
    public void deleteSession(Long sessionId) {
        long userId = StpUtil.getLoginIdAsLong();
        // 删除会话下的所有消息
        List<ChatMessage> messages = chatMessageRepository.findByUserIdAndSessionIdOrderByCreateTimeAsc(userId, sessionId);
        chatMessageRepository.deleteAll(messages);
        // 删除会话
        chatSessionRepository.deleteByUserIdAndId(userId, sessionId);
    }

    /**
     * 更新会话标题
     */
    public ChatSession updateSessionTitle(Long sessionId, String title) {
        long userId = StpUtil.getLoginIdAsLong();
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("会话不存在"));
        if (!session.getUserId().equals(userId)) {
            throw new RuntimeException("无权操作此会话");
        }
        session.setTitle(title);
        session.setUpdateTime(LocalDateTime.now());
        return chatSessionRepository.save(session);
    }
}
