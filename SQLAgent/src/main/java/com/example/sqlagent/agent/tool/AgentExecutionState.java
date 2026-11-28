package com.example.sqlagent.agent.tool;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class AgentExecutionState {
    private String sqlGenerated;
    private List<Map<String, Object>> sqlResults;
    private Boolean sqlExecutionSuccess;
    private String sqlErrorMessage;
    private String errorCode;
    private Boolean fromKnowledgeBase;
    private String knowledgeBaseContent;
    private String knowledgeBaseError;
    private String lastValidatedSql;
    private String lastStatementType;
    private boolean readOnly;
}
