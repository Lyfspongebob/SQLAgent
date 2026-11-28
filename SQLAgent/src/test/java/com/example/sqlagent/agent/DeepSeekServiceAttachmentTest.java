package com.example.sqlagent.agent;

import com.example.sqlagent.attachment.AttachmentKind;
import com.example.sqlagent.attachment.AttachmentPayload;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeepSeekServiceAttachmentTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = org.mockito.Mockito.mock(RestTemplate.class);
    private DeepSeekService service;

    @BeforeEach
    void setUp() {
        service = new DeepSeekService(restTemplate, objectMapper);
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        ReflectionTestUtils.setField(
                service,
                "apiUrl",
                "https://example.test/chat/completions"
        );
        ReflectionTestUtils.setField(service, "model", "vision-test-model");
    }

    @Test
    void sendsImageAsDataUrlAndTextFileAsBoundedContext() throws Exception {
        when(restTemplate.postForObject(
                eq("https://example.test/chat/completions"),
                any(HttpEntity.class),
                eq(String.class)
        )).thenReturn("""
                {"choices":[{"message":{"content":"已分析附件"}}]}
                """);

        byte[] imageBytes = "fake-image".getBytes(StandardCharsets.UTF_8);
        List<AttachmentPayload> attachments = List.of(
                new AttachmentPayload(
                        "schema.png",
                        "image/png",
                        AttachmentKind.IMAGE,
                        imageBytes,
                        null
                ),
                new AttachmentPayload(
                        "rules.md",
                        "text/markdown",
                        AttachmentKind.TEXT,
                        "rules".getBytes(StandardCharsets.UTF_8),
                        "统计时排除旁听学生"
                )
        );

        String reply = service.normalChat("分析这些附件", null, attachments);

        assertEquals("已分析附件", reply);
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<HttpEntity<String>> captor =
                org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForObject(
                eq("https://example.test/chat/completions"),
                captor.capture(),
                eq(String.class)
        );

        JsonNode request = objectMapper.readTree(captor.getValue().getBody());
        JsonNode content = request.path("messages").path(1).path("content");

        assertTrue(content.isArray());
        assertEquals("text", content.path(0).path("type").asText());
        assertTrue(content.path(0).path("text").asText()
                .contains("统计时排除旁听学生"));
        assertTrue(content.path(0).path("text").asText()
                .contains("<attachment name=\"rules.md\">"));
        assertEquals("image_url", content.path(1).path("type").asText());
        assertTrue(content.path(1).path("image_url").path("url").asText()
                .startsWith("data:image/png;base64,"));
        assertTrue(request.path("messages").path(0).path("content").asText()
                .contains("不可信数据"));
    }
}
