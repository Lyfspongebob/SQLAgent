package com.example.sqlagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class DeepSeekService {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekService.class);

    @Value("${deepseek.api-key}")
    private String apiKey;

    @Value("${deepseek.api-url}")
    private String apiUrl;

    @Value("${deepseek.model}")
    private String model;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public DeepSeekService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 调用 DeepSeek API 进行对话
     */
    public String chat(List<Map<String, String>> messages, double temperature) {
        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", model);

            ArrayNode messagesArray = requestBody.putArray("messages");
            for (Map<String, String> msg : messages) {
                ObjectNode msgNode = messagesArray.addObject();
                msgNode.put("role", msg.get("role"));
                msgNode.put("content", msg.get("content"));
            }

            requestBody.put("temperature", temperature);
            requestBody.put("max_tokens", 4096);

            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.set("Authorization", "Bearer " + apiKey);
            headers.set("Content-Type", "application/json");

            org.springframework.http.HttpEntity<String> entity =
                    new org.springframework.http.HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);

            String response = restTemplate.postForObject(apiUrl, entity, String.class);
            JsonNode responseJson = objectMapper.readTree(response);

            return responseJson.get("choices").get(0).get("message").get("content").asText();
        } catch (Exception e) {
            log.error("调用DeepSeek API失败: {}", e.getMessage(), e);
            throw new RuntimeException("调用DeepSeek API失败: " + e.getMessage());
        }
    }

    /**
     * 使用默认温度的对话
     */
    public String chat(List<Map<String, String>> messages) {
        return chat(messages, 0.7);
    }

    /**
     * 生成 SQL 语句（高温度用于多投）
     * @param kbResult 知识库查询结果，可为null
     */
    public String generateSQL(String userQuestion, String dbType, String tableSchema, double temperature, String kbResult) {
        List<Map<String, String>> messages = new ArrayList<>();

        Map<String, String> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");

        StringBuilder systemContent = new StringBuilder();
        systemContent.append(String.format(
                "你是一个SQL专家。请根据用户的问题生成对应的%s SQL查询语句。\n" +
                "数据库表结构信息：\n%s\n\n",
                dbType, tableSchema != null ? tableSchema : "未知"));

        // 如果有知识库内容，注入到 system prompt 中
        if (kbResult != null && !kbResult.isEmpty()) {
            systemContent.append("以下是知识库中相关的参考信息，请结合这些信息来理解用户的问题：\n")
                    .append(kbResult)
                    .append("\n\n");
        }

        systemContent.append("请只返回SQL语句，不要包含任何解释或其他文字。如果无法生成，请返回错误信息。");
        systemMsg.put("content", systemContent.toString());
        messages.add(systemMsg);

        Map<String, String> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userQuestion);
        messages.add(userMsg);

        return chat(messages, temperature);
    }

    /**
     * 生成 SQL 语句（无知识库版本，保持向后兼容）
     */
    public String generateSQL(String userQuestion, String dbType, String tableSchema, double temperature) {
        return generateSQL(userQuestion, dbType, tableSchema, temperature, null);
    }

    /**
     * Agent 反馈修正 SQL
     * @param kbResult 知识库查询结果，可为null
     */
    public String fixSQLWithFeedback(String originalQuestion, String dbType, String tableSchema,
                                      String previousSQL, String executionResult, String errorMessage,
                                      String kbResult) {
        List<Map<String, String>> messages = new ArrayList<>();

        Map<String, String> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");

        StringBuilder systemContent = new StringBuilder();
        systemContent.append(String.format(
                "你是一个SQL专家。请根据用户的问题和之前的SQL执行结果，修正SQL语句。\n" +
                "数据库类型：%s\n" +
                "数据库表结构：%s\n\n" +
                "之前的SQL：%s\n" +
                "执行结果：%s\n" +
                "错误信息：%s\n\n",
                dbType, tableSchema != null ? tableSchema : "未知",
                previousSQL, executionResult != null ? executionResult : "无",
                errorMessage != null ? errorMessage : "无"));

        // 如果有知识库内容，注入到 system prompt 中
        if (kbResult != null && !kbResult.isEmpty()) {
            systemContent.append("以下是知识库中相关的参考信息，请结合这些信息来理解用户的问题：\n")
                    .append(kbResult)
                    .append("\n\n");
        }

        systemContent.append("请分析是否需要修正SQL。如果SQL已经正确，请直接返回原SQL。\n" +
                "如果SQL有误，请返回修正后的SQL。\n" +
                "只返回SQL语句，不要包含任何解释。");
        systemMsg.put("content", systemContent.toString());
        messages.add(systemMsg);

        Map<String, String> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", originalQuestion);
        messages.add(userMsg);

        return chat(messages, 0.3); // 修正时使用低温度，保证准确性
    }

    /**
     * Agent 反馈修正 SQL（无知识库版本，保持向后兼容）
     */
    public String fixSQLWithFeedback(String originalQuestion, String dbType, String tableSchema,
                                      String previousSQL, String executionResult, String errorMessage) {
        return fixSQLWithFeedback(originalQuestion, dbType, tableSchema, previousSQL, executionResult, errorMessage, null);
    }

    /**
     * 普通对话（带知识库内容润色）
     * 将知识库内容作为上下文，让 AI 润色后回答用户问题
     */
    public String normalChatWithKnowledge(String userMessage, String knowledgeContent) {
        List<Map<String, String>> messages = new ArrayList<>();

        Map<String, String> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", "你是一个智能SQL助手，可以帮助用户编写SQL查询、分析数据库数据。" +
                "请用中文回答用户的问题。\n\n" +
                "以下是知识库中查询到的相关参考信息，请基于这些信息回答用户的问题，" +
                "如果知识库信息不足以回答，可以结合你自己的知识进行补充：\n" +
                knowledgeContent);
        messages.add(systemMsg);

        Map<String, String> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);

        return chat(messages);
    }

    /**
     * 普通对话
     */
    public String normalChat(String userMessage, String conversationHistory) {
        List<Map<String, String>> messages = new ArrayList<>();

        Map<String, String> systemMsg = new HashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", "你是一个智能SQL助手，可以帮助用户编写SQL查询、分析数据库数据。" +
                "请用中文回答用户的问题。如果用户询问SQL相关问题，请提供详细的解答。");
        messages.add(systemMsg);

        if (conversationHistory != null && !conversationHistory.isEmpty()) {
            Map<String, String> historyMsg = new HashMap<>();
            historyMsg.put("role", "assistant");
            historyMsg.put("content", conversationHistory);
            messages.add(historyMsg);
        }

        Map<String, String> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);

        return chat(messages);
    }
}
