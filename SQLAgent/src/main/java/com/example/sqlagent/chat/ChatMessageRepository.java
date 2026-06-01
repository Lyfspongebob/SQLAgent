package com.example.sqlagent.chat;

import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface ChatMessageRepository extends CrudRepository<ChatMessage, Long> {
    List<ChatMessage> findByUserIdAndSessionIdOrderByCreateTimeAsc(Long userId, Long sessionId);
    List<ChatMessage> findByUserIdOrderByCreateTimeDesc(Long userId);
}
