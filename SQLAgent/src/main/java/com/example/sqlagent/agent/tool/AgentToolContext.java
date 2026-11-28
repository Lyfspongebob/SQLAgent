package com.example.sqlagent.agent.tool;

public record AgentToolContext(
        long userId,
        Long sessionId,
        Long dataSourceConfigId,
        AgentExecutionState state
) {
}
