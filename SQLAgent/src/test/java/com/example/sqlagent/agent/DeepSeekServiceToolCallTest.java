package com.example.sqlagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeepSeekServiceToolCallTest {
    @Test
    void sendsToolDefinitionsAndParsesToolCalls() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        RestTemplate restTemplate = mock(RestTemplate.class);
        DeepSeekService service = new DeepSeekService(
                restTemplate,
                objectMapper
        );
        ReflectionTestUtils.setField(service, "apiKey", "test");
        ReflectionTestUtils.setField(
                service,
                "apiUrl",
                "https://example.test/chat"
        );
        ReflectionTestUtils.setField(service, "model", "test-model");

        when(restTemplate.postForObject(
                eq("https://example.test/chat"),
                any(HttpEntity.class),
                eq(String.class)
        )).thenReturn("""
                {
                  "choices": [{
                    "message": {
                      "content": null,
                      "tool_calls": [{
                        "id": "call-1",
                        "type": "function",
                        "function": {
                          "name": "inspect_database_schema",
                          "arguments": "{\\"table_name\\":\\"users\\"}"
                        }
                      }]
                    }
                  }]
                }
                """);

        List<Map<String, Object>> tools = List.of(Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "inspect_database_schema",
                        "description", "inspect schema",
                        "parameters", Map.of("type", "object")
                )
        ));

        DeepSeekService.AgentCompletion completion =
                service.completeWithTools(
                        List.of(Map.of("role", "user", "content", "test")),
                        tools,
                        0.2
                );

        assertNull(completion.content());
        assertEquals(1, completion.toolCalls().size());
        assertEquals(
                "inspect_database_schema",
                completion.toolCalls().get(0).name()
        );

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<HttpEntity<String>> captor =
                org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForObject(
                eq("https://example.test/chat"),
                captor.capture(),
                eq(String.class)
        );
        JsonNode request = objectMapper.readTree(captor.getValue().getBody());
        assertTrue(request.path("tools").isArray());
        assertEquals(
                "inspect_database_schema",
                request.path("tools").path(0)
                        .path("function").path("name").asText()
        );
    }
}
