package com.example.sqlagent.agent.tool;

import com.example.sqlagent.attachment.SessionDocumentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DocumentListTool implements AgentTool {
    private final SessionDocumentService documentService;
    private final ObjectMapper objectMapper;

    public DocumentListTool(
            SessionDocumentService documentService,
            ObjectMapper objectMapper
    ) {
        this.documentService = documentService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "list_session_documents";
    }

    @Override
    public String description() {
        return "列出当前会话中可用于检索和读取的文档或文本附件。";
    }

    @Override
    public ObjectNode parameters(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        schema.putArray("required");
        return schema;
    }

    @Override
    public AgentToolResult execute(
            AgentToolContext context,
            JsonNode arguments
    ) {
        List<SessionDocumentService.SessionDocument> documents =
                documentService.listDocuments(
                        context.userId(),
                        context.sessionId()
                );
        try {
            String content = objectMapper.writeValueAsString(documents);
            return AgentToolResult.success(
                    content,
                    "发现 " + documents.size() + " 个会话文档",
                    documents.stream()
                            .map(item -> item.name())
                            .reduce((left, right) -> left + "、" + right)
                            .orElse("无可用文档")
            );
        } catch (Exception exception) {
            return AgentToolResult.failure(
                    "{\"error\":\"文档目录序列化失败\"}",
                    "读取文档目录失败",
                    exception.getMessage()
            );
        }
    }
}
