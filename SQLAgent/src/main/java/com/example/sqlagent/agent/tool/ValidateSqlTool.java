package com.example.sqlagent.agent.tool;

import com.example.sqlagent.agent.SqlSafetyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

@Component
public class ValidateSqlTool implements AgentTool {
    private final SqlSafetyService safetyService;
    private final ObjectMapper objectMapper;

    public ValidateSqlTool(
            SqlSafetyService safetyService,
            ObjectMapper objectMapper
    ) {
        this.safetyService = safetyService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "validate_sql";
    }

    @Override
    public String description() {
        return "解析并分类 SQL，返回语句类型、是否只读、是否可由执行工具运行。"
                + "INSERT/UPDATE/DELETE 可以用于生成和展示，但不会被判定为可执行只读查询。";
    }

    @Override
    public ObjectNode parameters(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode sql = properties.putObject("sql");
        sql.put("type", "string");
        sql.put("description", "需要校验的完整 SQL");
        schema.putArray("required").add("sql");
        return schema;
    }

    @Override
    public AgentToolResult execute(
            AgentToolContext context,
            JsonNode arguments
    ) {
        String sql = arguments.path("sql").asText("");
        try {
            SqlSafetyService.SqlAnalysis analysis = safetyService.analyze(sql);
            ObjectNode result = objectMapper.createObjectNode();
            result.put("read_only", analysis.readOnly());
            result.put("executable", analysis.executable());
            result.put("reason", analysis.reason());
            ArrayNode types = result.putArray("statement_types");
            analysis.statementTypes().forEach(types::add);

            context.state().setLastValidatedSql(analysis.sql());
            context.state().setReadOnly(analysis.readOnly());
            context.state().setLastStatementType(
                    String.join(",", analysis.statementTypes())
            );

            return AgentToolResult.success(
                    objectMapper.writeValueAsString(result),
                    analysis.executable()
                            ? "SQL 校验通过，可只读执行"
                            : "SQL 已完成语法与安全分类",
                    analysis.reason()
            );
        } catch (Exception exception) {
            return AgentToolResult.failure(
                    "SQL 校验失败：" + exception.getMessage(),
                    "SQL 校验失败",
                    exception.getMessage()
            );
        }
    }
}
