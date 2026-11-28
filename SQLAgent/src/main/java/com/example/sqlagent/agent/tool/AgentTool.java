package com.example.sqlagent.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public interface AgentTool {
    String name();

    String description();

    ObjectNode parameters(ObjectMapper objectMapper);

    AgentToolResult execute(AgentToolContext context, JsonNode arguments);
}
