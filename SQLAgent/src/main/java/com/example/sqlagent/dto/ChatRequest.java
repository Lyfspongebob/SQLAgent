package com.example.sqlagent.dto;

import lombok.Data;

@Data
public class ChatRequest {
    private String message;
    private Long sessionId;
    private Boolean useKnowledgeBase;
    private Boolean useSqlAgent;
    private Long dataSourceConfigId;
}
