package com.example.sqlagent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageResponse {
    private Long id;
    private Long sessionId;
    private String role;
    private String content;
    private String sqlGenerated;
    private List<Map<String, Object>> sqlResults;
    private String sqlResultText;
    private String sqlResultFormat;
    private Boolean sqlExecutionSuccess;
    private Boolean fromKnowledgeBase;
    private List<AttachmentResponse> attachments;
    private List<AgentStepResponse> agentSteps;
    private LocalDateTime createTime;
}
