package com.example.sqlagent.agent.tool;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AgentToolRegistry {
    private final Map<String, AgentTool> tools;

    public AgentToolRegistry(List<AgentTool> agentTools) {
        Map<String, AgentTool> registered = new LinkedHashMap<>();
        for (AgentTool tool : agentTools) {
            registered.put(tool.name(), tool);
        }
        this.tools = Map.copyOf(registered);
    }

    public AgentTool get(String name) {
        return tools.get(name);
    }

    public List<AgentTool> all() {
        return List.copyOf(tools.values());
    }
}
