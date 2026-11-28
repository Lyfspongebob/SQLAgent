package com.example.sqlagent.config;

import com.example.sqlagent.dto.AgentStepResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class JacksonConfigurationTest {
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void serializesAgentStepTimestamps() throws Exception {
        AgentStepResponse step = AgentStepResponse.builder()
                .id("step-1")
                .type("tool")
                .title("检查数据库结构")
                .status("completed")
                .summary("完成")
                .startedAt("2026-11-27 21:30:00")
                .completedAt("2026-11-27 21:30:01")
                .build();

        String json = objectMapper.writeValueAsString(List.of(step));

        assertTrue(json.contains("检查数据库结构"));
        assertTrue(json.contains("startedAt"));
    }
}
