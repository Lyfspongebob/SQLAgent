package com.example.sqlagent.agent.tool;

public record AgentToolResult(
        String content,
        String summary,
        String detail,
        boolean success
) {
    public static AgentToolResult success(
            String content,
            String summary,
            String detail
    ) {
        return new AgentToolResult(content, summary, detail, true);
    }

    public static AgentToolResult failure(
            String content,
            String summary,
            String detail
    ) {
        return new AgentToolResult(content, summary, detail, false);
    }
}
