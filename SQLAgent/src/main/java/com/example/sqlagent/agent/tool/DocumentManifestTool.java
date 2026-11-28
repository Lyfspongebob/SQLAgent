package com.example.sqlagent.agent.tool;

import com.example.sqlagent.attachment.SessionDocumentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

@Component
public class DocumentManifestTool implements AgentTool {
    private final SessionDocumentService documentService;

    public DocumentManifestTool(SessionDocumentService documentService) {
        this.documentService = documentService;
    }

    @Override
    public String name() {
        return "get_session_document_manifest";
    }

    @Override
    public String description() {
        return "读取某份会话文档的格式清单，包括页数、章节、工作表、范围和解析限制。";
    }

    @Override
    public ObjectNode parameters(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode attachmentId = properties.putObject("attachment_id");
        attachmentId.put("type", "integer");
        attachmentId.put("description", "会话文档的 attachment_id");
        schema.putArray("required").add("attachment_id");
        return schema;
    }

    @Override
    public AgentToolResult execute(
            AgentToolContext context,
            JsonNode arguments
    ) {
        if (!arguments.hasNonNull("attachment_id")) {
            return AgentToolResult.failure(
                    "{\"error\":\"attachment_id 不能为空\"}",
                    "读取文档清单参数不完整",
                    "缺少 attachment_id"
            );
        }

        Long attachmentId = arguments.path("attachment_id").asLong();
        try {
            String manifest = documentService.manifest(
                    context.userId(),
                    context.sessionId(),
                    attachmentId
            );
            return AgentToolResult.success(
                    manifest,
                    "已读取文档清单",
                    "attachment_id=" + attachmentId
            );
        } catch (Exception exception) {
            return AgentToolResult.failure(
                    "读取文档清单失败：" + exception.getMessage(),
                    "读取文档清单失败",
                    exception.getMessage()
            );
        }
    }
}
