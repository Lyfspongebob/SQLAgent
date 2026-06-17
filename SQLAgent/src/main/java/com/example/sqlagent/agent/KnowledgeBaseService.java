package com.example.sqlagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    @Value("${zhipu.api-key}")
    private String apiKey;

    @Value("${zhipu.knowledge-base-id}")
    private String knowledgeBaseId;

    @Value("${zhipu.api-url}")
    private String apiUrl;

    @Value("${zhipu.model:glm-4-plus}")
    private String model;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public KnowledgeBaseService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询知识库（通过智谱对话 API + tools.retrieval 方式）
     * 智谱会在对话中自动检索知识库并基于知识库内容回答
     *
     * @param query 查询内容
     * @return 智谱 AI 基于知识库的回答，如果失败返回null
     */
    public String queryKnowledgeBase(String query) {
        try {
            // 构建请求体：调用智谱对话 API，通过 tools.retrieval 检索知识库
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", model);

            // messages
            ArrayNode messagesArray = requestBody.putArray("messages");
            ObjectNode userMsg = messagesArray.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", query);

            // tools - retrieval 知识库检索
            ArrayNode toolsArray = requestBody.putArray("tools");
            ObjectNode toolNode = toolsArray.addObject();
            toolNode.put("type", "retrieval");

            ObjectNode retrievalNode = toolNode.putObject("retrieval");
            retrievalNode.put("knowledge_id", knowledgeBaseId);
            retrievalNode.put("prompt_template",
                    "从文档\n\"\"\"\n{{knowledge}}\n\"\"\"\n" +
                    "中找问题\n\"\"\"\n{{question}}\n\"\"\"\n" +
                    "的答案，找到答案就仅使用文档语句回答问题，找不到答案就用自身知识回答并且告诉用户该信息不是来自文档。\n" +
                    "不要复述问题，直接开始回答。");

            // 不启用流式
            requestBody.put("stream", false);

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + apiKey);
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<String> entity =
                    new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);

            log.info("查询知识库: knowledgeId={}, query={}, model={}", knowledgeBaseId, query, model);
            log.debug("知识库请求体: {}", objectMapper.writeValueAsString(requestBody));

            String response = restTemplate.postForObject(apiUrl, entity, String.class);
            log.debug("知识库原始响应: {}", response);

            JsonNode responseJson = objectMapper.readTree(response);

            // 解析智谱对话 API 返回结果
            // 格式: { "choices": [ { "message": { "role": "assistant", "content": "..." } } ] }
            JsonNode choicesNode = responseJson.get("choices");
            if (choicesNode != null && choicesNode.isArray() && !choicesNode.isEmpty()) {
                JsonNode messageNode = choicesNode.get(0).get("message");
                if (messageNode != null && messageNode.has("content")) {
                    String content = messageNode.get("content").asText();
                    log.info("知识库查询成功，回答长度: {}", content.length());
                    return content;
                }
            }

            log.warn("知识库返回数据格式异常: {}", response);
            return null;
        } catch (Exception e) {
            log.error("知识库查询失败: query={}, error={}", query, e.getMessage(), e);
            return null; // 知识库查询失败，返回null让系统调用大模型
        }
    }
}
