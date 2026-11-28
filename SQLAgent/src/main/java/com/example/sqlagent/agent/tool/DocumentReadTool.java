package com.example.sqlagent.agent.tool;

import com.example.sqlagent.attachment.SessionDocumentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DocumentReadTool implements AgentTool {
    private final SessionDocumentService documentService;
    private final ObjectMapper objectMapper;

    @Value("${agent.document-read-max-chars:16000}")
    private int maxChars = 16_000;

    public DocumentReadTool(
            SessionDocumentService documentService,
            ObjectMapper objectMapper
    ) {
        this.documentService = documentService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "read_document_chunks";
    }

    @Override
    public String description() {
        return "读取当前会话文档的完整内容片段。优先传 search_session_documents 返回的 chunk_ids；"
                + "也可以传 attachment_id 和 locator 模糊定位。";
    }

    @Override
    public ObjectNode parameters(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        ObjectNode chunkIds = properties.putObject("chunk_ids");
        chunkIds.put("type", "array");
        chunkIds.put("description", "搜索结果中的 chunk_id 列表");
        chunkIds.putObject("items").put("type", "integer");

        ObjectNode attachmentId = properties.putObject("attachment_id");
        attachmentId.put("type", "integer");
        attachmentId.put("description", "可选，按附件读取");

        ObjectNode locator = properties.putObject("locator");
        locator.put("type", "string");
        locator.put("description", "可选，页码、章节或行号定位，例如“第 3 页”");

        ObjectNode maxCharacters = properties.putObject("max_chars");
        maxCharacters.put("type", "integer");
        maxCharacters.put("description", "本次读取的最大字符数");

        schema.putArray("required");
        return schema;
    }

    @Override
    public AgentToolResult execute(
            AgentToolContext context,
            JsonNode arguments
    ) {
        List<Long> chunkIds = new ArrayList<>();
        JsonNode ids = arguments.path("chunk_ids");
        if (ids.isArray()) {
            ids.forEach(item -> {
                if (item.canConvertToLong()) {
                    chunkIds.add(item.asLong());
                }
            });
        }

        Long attachmentId = arguments.hasNonNull("attachment_id")
                ? arguments.path("attachment_id").asLong()
                : null;
        String locator = arguments.path("locator").asText(null);
        int requestedChars = arguments.path("max_chars").asInt(maxChars);

        List<SessionDocumentService.ReadChunk> chunks =
                documentService.readChunks(
                        context.userId(),
                        context.sessionId(),
                        chunkIds,
                        attachmentId,
                        locator,
                        Math.min(Math.max(requestedChars, 1_000), maxChars)
                );

        if (chunks.isEmpty()) {
            return AgentToolResult.failure(
                    "{\"error\":\"没有找到匹配的文档片段\"}",
                    "未读取到文档内容",
                    "请先搜索或检查 attachment_id 与 locator"
            );
        }

        try {
            ObjectNode content = objectMapper.createObjectNode();
            ArrayNode array = content.putArray("chunks");
            chunks.forEach(chunk -> {
                ObjectNode item = array.addObject();
                item.put("chunk_id", chunk.chunkId());
                item.put("attachment_id", chunk.attachmentId());
                item.put("locator", chunk.locator());
                item.put("content", chunk.content());
            });
            return AgentToolResult.success(
                    objectMapper.writeValueAsString(content),
                    "读取 " + chunks.size() + " 个文档片段",
                    chunks.stream()
                            .map(SessionDocumentService.ReadChunk::locator)
                            .reduce((left, right) -> left + "；" + right)
                            .orElse("")
            );
        } catch (Exception exception) {
            return AgentToolResult.failure(
                    "{\"error\":\"文档内容序列化失败\"}",
                    "读取文档失败",
                    exception.getMessage()
            );
        }
    }
}
