package com.example.sqlagent.chat;

import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface ChatSessionRepository extends CrudRepository<ChatSession, Long> {
    List<ChatSession> findByUserIdOrderByUpdateTimeDesc(Long userId);
    void deleteByUserIdAndId(Long userId, Long id);
}
