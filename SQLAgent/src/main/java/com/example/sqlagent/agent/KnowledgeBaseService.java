package com.example.sqlagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.example.sqlagent.error.ErrorCode;
import com.example.sqlagent.error.UpstreamServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);
    private static final int MAX_CONTEXT_LENGTH = 15_000;

    @Value("${zhipu.api-key}")
    private String apiKey;

    @Value("${zhipu.knowledge-base-id}")
    private String knowledgeBaseId;

    @Value("${zhipu.api-url}")
    private String apiUrl;

    @Value("${zhipu.retrieval-url:https://open.bigmodel.cn/api/llm-application/open/knowledge/retrieve}")
    private String retrievalUrl;

    @Value("${zhipu.model:glm-4-plus}")
    private String model;

    @Value("${zhipu.top-k:5}")
    private int topK;

    @Value("${zhipu.top-n:20}")
    private int topN;

    @Value("${zhipu.recall-method:mixed}")
    private String recallMethod;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public KnowledgeBaseService(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 先通过知识库检索 API 获取真实切片，再把切片交给模型生成回答。
     *
     * <p>智谱对话 API 的 tools.retrieval 在部分模型/账号组合下会跳过检索，
     * 因此这里显式调用官方的 knowledge/retrieve 接口，避免模型直接使用自身知识回答。</p>
     */
    public KnowledgeBaseResult retrieve(String query) {
        if (query == null || query.isBlank()) {
            return KnowledgeBaseResult.empty();
        }

        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("request_id", UUID.randomUUID().toString());
            requestBody.put("query", query.trim());
            requestBody.putArray("knowledge_ids").add(knowledgeBaseId);
            requestBody.put("top_k", clamp(topK, 1, 20));
            requestBody.put("top_n", clamp(topN, 1, 100));
            requestBody.put("recall_method", normalizedRecallMethod());
            requestBody.put("rerank_status", 0);

            log.info("检索知识库: knowledgeId={}, queryLength={}", knowledgeBaseId, query.length());
            JsonNode responseJson = postJson(retrievalUrl, requestBody);

            int code = responseJson.path("code").asInt(200);
            if (code != 200) {
                throw new KnowledgeBaseException(
                        "知识库检索失败: " + responseMessage(responseJson, code));
            }

            JsonNode data = responseJson.path("data");
            if (!data.isArray() || data.isEmpty()) {
                log.info("知识库未召回文档片段: knowledgeId={}", knowledgeBaseId);
                return KnowledgeBaseResult.empty();
            }

            List<KnowledgeBaseResult.Source> sources = new ArrayList<>();
            Set<String> seenTexts = new LinkedHashSet<>();
            for (JsonNode item : data) {
                String text = item.path("text").asText("").trim();
                if (text.isEmpty() || !seenTexts.add(text)) {
                    continue;
                }
                String documentName = item.path("metadata").path("doc_name").asText("未命名文档");
                double score = item.path("score").asDouble(0);
                sources.add(new KnowledgeBaseResult.Source(documentName, text, score));
            }

            if (sources.isEmpty()) {
                return KnowledgeBaseResult.empty();
            }

            String context = buildContext(sources);
            log.info("知识库召回完成: knowledgeId={}, sourceCount={}, contextLength={}",
                    knowledgeBaseId, sources.size(), context.length());
            return new KnowledgeBaseResult(true, context, sources);
        } catch (KnowledgeBaseException e) {
            throw e;
        } catch (Exception e) {
            log.error("知识库检索请求失败: knowledgeId={}, error={}", knowledgeBaseId, e.getMessage(), e);
            throw new KnowledgeBaseException("知识库检索请求失败: " + errorMessage(e), e);
        }
    }

    /**
     * 基于检索到的真实片段生成回答。没有召回内容时不会调用模型。
     */
    public String answer(String question, KnowledgeBaseResult knowledgeResult) {
        if (knowledgeResult == null || !knowledgeResult.found()) {
            throw new KnowledgeBaseException("知识库中没有可用于回答该问题的内容");
        }

        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", model);

            ArrayNode messagesArray = requestBody.putArray("messages");
            ObjectNode systemMessage = messagesArray.addObject();
            systemMessage.put("role", "system");
            systemMessage.put("content", """
                    你是知识库问答助手。必须且只能依据给定的知识库片段回答问题。
                    要求：
                    1. 先直接回答用户问题，不要复述检索过程。
                    2. 只使用片段中出现的事实，不要补充外部常识，不要编造。
                    3. 如果片段不足以回答，明确说明“知识库中未找到足够信息”。

                    知识库片段：
                    """ + knowledgeResult.context());

            ObjectNode userMessage = messagesArray.addObject();
            userMessage.put("role", "user");
            userMessage.put("content", question);

            requestBody.put("temperature", 0.1);
            requestBody.put("max_tokens", 1024);
            requestBody.put("stream", false);

            log.info("基于知识库片段生成回答: knowledgeId={}, sourceCount={}",
                    knowledgeBaseId, knowledgeResult.sources().size());
            JsonNode responseJson = postJson(apiUrl, requestBody);

            int code = responseJson.path("code").asInt(200);
            if (code != 200) {
                throw new KnowledgeBaseException(
                        "知识库回答生成失败: " + responseMessage(responseJson, code));
            }

            String content = responseJson.path("choices")
                    .path(0)
                    .path("message")
                    .path("content")
                    .asText("")
                    .trim();
            if (content.isEmpty()) {
                throw new KnowledgeBaseException("知识库回答生成失败: 模型返回内容为空");
            }

            log.info("知识库回答生成完成: knowledgeId={}, answerLength={}",
                    knowledgeBaseId, content.length());
            return content;
        } catch (KnowledgeBaseException e) {
            throw e;
        } catch (Exception e) {
            log.error("知识库回答生成请求失败: knowledgeId={}, error={}",
                    knowledgeBaseId, e.getMessage(), e);
            throw new KnowledgeBaseException("知识库回答生成请求失败: " + errorMessage(e), e);
        }
    }

    private JsonNode postJson(String url, ObjectNode requestBody) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> entity =
                new HttpEntity<>(objectMapper.writeValueAsString(requestBody), headers);
        String response = restTemplate.postForObject(url, entity, String.class);
        if (response == null || response.isBlank()) {
            throw new KnowledgeBaseException("知识库接口返回空响应");
        }
        return objectMapper.readTree(response);
    }

    private String buildContext(List<KnowledgeBaseResult.Source> sources) {
        StringBuilder context = new StringBuilder();
        int index = 1;
        for (KnowledgeBaseResult.Source source : sources) {
            String header = "【片段 " + index + "｜文档：" + source.documentName() + "】\n";
            int remaining = MAX_CONTEXT_LENGTH - context.length() - header.length();
            if (remaining <= 0) {
                break;
            }

            String text = source.text();
            if (text.length() > remaining) {
                text = text.substring(0, remaining) + "…";
            }
            context.append(header).append(text).append("\n\n");
            index++;
        }
        return context.toString().trim();
    }

    private String normalizedRecallMethod() {
        String method = recallMethod == null ? "mixed" : recallMethod.trim().toLowerCase();
        return switch (method) {
            case "embedding", "keyword", "mixed" -> method;
            default -> "mixed";
        };
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String responseMessage(JsonNode responseJson, int code) {
        String message = responseJson.path("message").asText("").trim();
        return message.isEmpty() ? "HTTP/业务码 " + code : message;
    }

    private String errorMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    public static class KnowledgeBaseException extends UpstreamServiceException {
        public KnowledgeBaseException(String message) {
            super(ErrorCode.KNOWLEDGE_BASE_UNAVAILABLE, message);
        }

        public KnowledgeBaseException(String message, Throwable cause) {
            super(ErrorCode.KNOWLEDGE_BASE_UNAVAILABLE, message, cause);
        }
    }
}
