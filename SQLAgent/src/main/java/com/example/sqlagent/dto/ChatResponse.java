package com.example.sqlagent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {
    private String reply;
    private String sqlGenerated;
    private List<Map<String, Object>> sqlResults;
    private Boolean sqlExecutionSuccess;
    private String sqlErrorMessage;
    private String errorCode;
    private Boolean fromKnowledgeBase;
    private String knowledgeBaseContent;
    private String knowledgeBaseError;
}
