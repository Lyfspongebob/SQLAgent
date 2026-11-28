package com.example.sqlagent.chat;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "chat_messages")
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(name = "session_id")
    private Long sessionId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false)
    private String role; // "user" or "assistant"

    @Column(name = "sql_generated", columnDefinition = "TEXT")
    private String sqlGenerated;

    @Column(name = "sql_results", columnDefinition = "TEXT")
    private String sqlResults;

    @Column(name = "sql_success")
    private Boolean sqlSuccess;

    @Column(name = "from_knowledge_base")
    private Boolean fromKnowledgeBase;

    @Column(name = "agent_trace", columnDefinition = "LONGTEXT")
    private String agentTrace;

    @Column(name = "create_time")
    private LocalDateTime createTime = LocalDateTime.now();
}
