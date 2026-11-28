package com.example.sqlagent.agent;

import com.example.sqlagent.agent.tool.*;
import com.example.sqlagent.attachment.SessionDocumentService;
import com.example.sqlagent.dto.AgentStepResponse;
import com.example.sqlagent.dto.ChatResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentRunnerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void executesModelRequestedToolThenReturnsFinalAnswer() {
        DeepSeekService deepSeekService = mock(DeepSeekService.class);
        SessionDocumentService documentService =
                mock(SessionDocumentService.class);
        when(documentService.catalog(7L, 20L))
                .thenReturn("- attachment_id=1，PDF，手册.pdf");
        when(deepSeekService.userMessageWithAttachments(
                anyString(),
                anyList()
        )).thenAnswer(invocation -> Map.of(
                "role", "user",
                "content", invocation.getArgument(0)
        ));

        List<String> executed = new ArrayList<>();
        AgentTool listTool = new TestListTool(objectMapper, executed);
        AgentToolRegistry registry = new AgentToolRegistry(List.of(listTool));
        AgentRunner runner = new AgentRunner(
                deepSeekService,
                registry,
                documentService,
                objectMapper
        );

        when(deepSeekService.completeWithTools(
                anyList(),
                anyList(),
                anyDouble()
        )).thenReturn(
                new DeepSeekService.AgentCompletion(
                        null,
                        List.of(new DeepSeekService.AgentToolCall(
                                "call-1",
                                "list_session_documents",
                                "{}",
                                "function"
                        ))
                ),
                new DeepSeekService.AgentCompletion(
                        "当前会话有一份手册。",
                        List.of()
                )
        );

        List<AgentStepResponse> streamed = new ArrayList<>();
        ChatResponse response = runner.run(
                7L,
                20L,
                "有哪些附件？",
                false,
                false,
                null,
                List.of(),
                List.of(),
                streamed::add
        );

        assertEquals("当前会话有一份手册。", response.getReply());
        assertEquals(List.of("list_session_documents"), executed);
        assertTrue(response.getAgentSteps().stream()
                .anyMatch(step -> "tool".equals(step.getType())
                        && "completed".equals(step.getStatus())));
        assertFalse(streamed.isEmpty());
        verify(deepSeekService, times(2)).completeWithTools(
                anyList(),
                anyList(),
                anyDouble()
        );
    }

    private static class TestListTool implements AgentTool {
        private final ObjectMapper objectMapper;
        private final List<String> executed;

        private TestListTool(
                ObjectMapper objectMapper,
                List<String> executed
        ) {
            this.objectMapper = objectMapper;
            this.executed = executed;
        }

        @Override
        public String name() {
            return "list_session_documents";
        }

        @Override
        public String description() {
            return "test";
        }

        @Override
        public ObjectNode parameters(ObjectMapper mapper) {
            ObjectNode schema = mapper.createObjectNode();
            schema.put("type", "object");
            return schema;
        }

        @Override
        public AgentToolResult execute(
                AgentToolContext context,
                JsonNode arguments
        ) {
            executed.add(name());
            return AgentToolResult.success(
                    "[]",
                    "测试工具完成",
                    "test"
            );
        }
    }
}
