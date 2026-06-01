package com.example.sqlagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

@Service
public class KnowledgeBaseService {

    @Value("${zhipu.api-key}")
    private String apiKey;

    @Value("${zhipu.knowledge-base-id}")
    private String knowledgeBaseId;

    @Value("${zhipu.api-url}")
    private String apiUrl;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public KnowledgeBaseService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 查询知识库
     * @param query 查询内容
     * @return 知识库返回的结果，如果没有找到相关内容返回null
     */
    public String queryKnowledgeBase(String query) {
        try {
            // 智谱清言知识库API调用
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("knowledge_id", knowledgeBaseId);
            requestBody.put("query", query);
            requestBody.put("top_k", 5);

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + apiKey);
            headers.set("Content-Type", "application/json");

            HttpEntity<String> entity =
                    new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);

            String response = restTemplate.postForObject(apiUrl, entity, String.class);
            JsonNode responseJson = objectMapper.readTree(response);

            // 解析知识库返回结果
            JsonNode dataNode = responseJson.get("data");
            if (dataNode != null && dataNode.isArray() && !dataNode.isEmpty()) {
                StringBuilder result = new StringBuilder();
                for (JsonNode item : dataNode) {
                    if (item.has("content")) {
                        result.append(item.get("content").asText()).append("\n");
                    }
                }
                return result.toString().trim();
            }

            return null; // 知识库没有找到相关内容
        } catch (Exception e) {
            // 知识库查询失败，返回null让系统调用大模型
            return null;
        }
    }
}
