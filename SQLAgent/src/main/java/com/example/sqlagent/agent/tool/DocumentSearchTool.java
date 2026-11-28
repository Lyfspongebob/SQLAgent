package com.example.sqlagent.agent.tool;

import com.example.sqlagent.attachment.SessionDocumentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DocumentSearchTool implements AgentTool {
    private final SessionDocumentService documentService;
    private final ObjectMapper objectMapper;

    public DocumentSearchTool(
            SessionDocumentService documentService,
            ObjectMapper objectMapper
    ) {
        this.documentService = documentService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "search_session_documents";
    }

    @Override
    public String description() {
        return "在当前会话文档索引中搜索相关页、章节或表格行块。"
                + "返回 chunk_id、文档名、定位和短预览；需要完整内容时再调用 read_document_chunks。";
    }

    @Override
    public ObjectNode parameters(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        ObjectNode query = properties.putObject("query");
        query.put("type", "string");
        query.put("description", "要检索的内容或用户问题");

        ObjectNode attachmentIds = properties.putObject("attachment_ids");
        attachmentIds.put("type", "array");
        attachmentIds.put("description", "可选，只在这些附件中搜索");
        attachmentIds.putObject("items").put("type", "integer");

        ObjectNode limit = properties.putObject("limit");
        limit.put("type", "integer");
        limit.put("description", "返回数量，默认 6，最大 12");

        ArrayNode required = schema.putArray("required");
        required.add("query");
        return schema;
    }

    @Override
    public AgentToolResult execute(
            AgentToolContext context,
            JsonNode arguments
    ) {
        String query = arguments.path("query").asText("");
        if (query.isBlank()) {
            return AgentToolResult.failure(
                    "{\"error\":\"query 不能为空\"}",
                    "文档检索参数不完整",
                    "模型没有提供检索关键词"
            );
        }

        List<Long> attachmentIds = new ArrayList<>();
        JsonNode idsNode = arguments.path("attachment_ids");
        if (idsNode.isArray()) {
            idsNode.forEach(item -> {
                if (item.canConvertToLong()) {
                    attachmentIds.add(item.asLong());
                }
            });
        }

        int limit = arguments.path("limit").asInt(6);
        List<SessionDocumentService.SearchHit> hits = documentService.search(
                context.userId(),
                context.sessionId(),
                query,
                attachmentIds,
                limit
        );

        try {
            String content = objectMapper.writeValueAsString(hits);
            String detail = hits.stream()
                    .map(hit -> hit.documentName() + " / " + hit.locator())
                    .reduce((left, right) -> left + "；" + right)
                    .orElse("没有命中文档片段");
            return AgentToolResult.success(
                    content,
                    "文档检索命中 " + hits.size() + " 个片段",
                    detail
            );
        } catch (Exception exception) {
            return AgentToolResult.failure(
                    "{\"error\":\"搜索结果序列化失败\"}",
                    "文档检索失败",
                    exception.getMessage()
            );
        }
    }
}
