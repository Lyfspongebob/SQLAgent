package com.example.sqlagent.agent.tool;

import com.example.sqlagent.agent.KnowledgeBaseResult;
import com.example.sqlagent.agent.KnowledgeBaseService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeBaseSearchTool implements AgentTool {
    private final KnowledgeBaseService knowledgeBaseService;
    private final ObjectMapper objectMapper;

    public KnowledgeBaseSearchTool(
            KnowledgeBaseService knowledgeBaseService,
            ObjectMapper objectMapper
    ) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "search_knowledge_base";
    }

    @Override
    public String description() {
        return "搜索应用知识库中的制度、SQL 教学材料或术语说明。"
                + "用户上传的会话附件应使用会话文档工具。";
    }

    @Override
    public ObjectNode parameters(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode query = properties.putObject("query");
        query.put("type", "string");
        query.put("description", "知识库检索问题");
        schema.putArray("required").add("query");
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
                    "知识库检索参数不完整",
                    "缺少 query"
            );
        }

        try {
            KnowledgeBaseResult result = knowledgeBaseService.retrieve(query);
            context.state().setFromKnowledgeBase(result.found());
            context.state().setKnowledgeBaseContent(result.context());

            ObjectNode response = objectMapper.createObjectNode();
            response.put("found", result.found());
            response.put("context", result.context());
            ArrayNode sources = response.putArray("sources");
            result.sources().forEach(source -> {
                ObjectNode item = sources.addObject();
                item.put("document", source.documentName());
                item.put("text", source.text());
                item.put("score", source.score());
            });

            return AgentToolResult.success(
                    objectMapper.writeValueAsString(response),
                    result.found()
                            ? "知识库命中相关材料"
                            : "知识库未找到相关内容",
                    result.found()
                            ? "命中 " + result.sources().size() + " 个来源"
                            : "可以继续使用其他工具"
            );
        } catch (Exception exception) {
            context.state().setKnowledgeBaseError(exception.getMessage());
            context.state().setFromKnowledgeBase(false);
            return AgentToolResult.failure(
                    "知识库检索失败：" + exception.getMessage(),
                    "知识库检索失败",
                    exception.getMessage()
            );
        }
    }
}
