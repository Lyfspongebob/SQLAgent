package com.example.sqlagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KnowledgeBaseServiceTest {

    private static final String RETRIEVAL_URL =
            "https://open.bigmodel.cn/api/llm-application/open/knowledge/retrieve";
    private static final String CHAT_URL =
            "https://open.bigmodel.cn/api/paas/v4/chat/completions";

    private MockRestServiceServer server;
    private KnowledgeBaseService service;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        service = new KnowledgeBaseService(restTemplate, new ObjectMapper());

        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        ReflectionTestUtils.setField(service, "knowledgeBaseId", "kb-1");
        ReflectionTestUtils.setField(service, "apiUrl", CHAT_URL);
        ReflectionTestUtils.setField(service, "retrievalUrl", RETRIEVAL_URL);
        ReflectionTestUtils.setField(service, "model", "glm-4.6");
        ReflectionTestUtils.setField(service, "topK", 5);
        ReflectionTestUtils.setField(service, "topN", 20);
        ReflectionTestUtils.setField(service, "recallMethod", "mixed");
    }

    @Test
    void retrieveUsesKnowledgeApiAndBuildsContext() {
        server.expect(requestTo(RETRIEVAL_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.knowledge_ids[0]").value("kb-1"))
                .andExpect(jsonPath("$.query").value("刘宇飞是谁？"))
                .andExpect(jsonPath("$.top_k").value(5))
                .andRespond(withSuccess("""
                        {
                          "code": 200,
                          "message": "请求成功",
                          "data": [
                            {
                              "text": "刘宇飞高中在新余一中读书",
                              "score": 0.93,
                              "metadata": {"doc_name": "学生信息.docx"}
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        KnowledgeBaseResult result = service.retrieve("刘宇飞是谁？");

        assertTrue(result.found());
        assertEquals(1, result.sources().size());
        assertTrue(result.context().contains("刘宇飞高中在新余一中读书"));
        assertTrue(result.context().contains("学生信息.docx"));
        server.verify();
    }

    @Test
    void answerUsesOnlyRetrievedContext() {
        KnowledgeBaseResult result = new KnowledgeBaseResult(
                true,
                "【片段 1｜文档：学生信息.docx】\n刘宇飞高中在新余一中读书",
                List.of(new KnowledgeBaseResult.Source(
                        "学生信息.docx", "刘宇飞高中在新余一中读书", 0.93))
        );

        server.expect(requestTo(CHAT_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.messages[0].content",
                        containsString("刘宇飞高中在新余一中读书")))
                .andExpect(jsonPath("$.messages[1].content").value("刘宇飞是谁？"))
                .andRespond(withSuccess("""
                        {
                          "choices": [
                            {
                              "message": {
                                "role": "assistant",
                                "content": "刘宇飞高中就读于新余一中。"
                              }
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        String answer = service.answer("刘宇飞是谁？", result);

        assertEquals("刘宇飞高中就读于新余一中。", answer);
        server.verify();
    }

    @Test
    void emptyKnowledgeResultDoesNotCallModel() {
        KnowledgeBaseResult result = service.retrieve("");

        assertFalse(result.found());
        assertTrue(result.sources().isEmpty());
        server.verify();
    }

    @Test
    void businessErrorIsNotSilentlyTreatedAsNoResult() {
        server.expect(requestTo(RETRIEVAL_URL))
                .andRespond(withSuccess("""
                        {"code": 401, "message": "API key invalid"}
                        """, MediaType.APPLICATION_JSON));

        KnowledgeBaseService.KnowledgeBaseException error = assertThrows(
                KnowledgeBaseService.KnowledgeBaseException.class,
                () -> service.retrieve("刘宇飞是谁？")
        );

        assertTrue(error.getMessage().contains("API key invalid"));
        server.verify();
    }
}
