package com.example.sqlagent.agent.tool;

import com.example.sqlagent.agent.SqlSafetyService;
import com.example.sqlagent.datasource.DataSourceConfig;
import com.example.sqlagent.datasource.DataSourceConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ExecuteReadonlySqlTool implements AgentTool {
    private final DataSourceConfigRepository configRepository;
    private final SqlSafetyService safetyService;
    private final ObjectMapper objectMapper;

    @Value("${agent.max-tool-result-rows:50}")
    private int maxToolResultRows = 50;

    public ExecuteReadonlySqlTool(
            DataSourceConfigRepository configRepository,
            SqlSafetyService safetyService,
            ObjectMapper objectMapper
    ) {
        this.configRepository = configRepository;
        this.safetyService = safetyService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return "execute_readonly_sql";
    }

    @Override
    public String description() {
        return "在用户数据源上执行单条只读 SELECT。"
                + "只能执行 SELECT；INSERT/UPDATE/DELETE/DDL 永远会被拒绝。";
    }

    @Override
    public ObjectNode parameters(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode sql = properties.putObject("sql");
        sql.put("type", "string");
        sql.put("description", "单条只读 SELECT SQL");
        schema.putArray("required").add("sql");
        return schema;
    }

    @Override
    public AgentToolResult execute(
            AgentToolContext context,
            JsonNode arguments
    ) {
        if (context.dataSourceConfigId() == null) {
            return AgentToolResult.failure(
                    "{\"error\":\"没有选择数据源\"}",
                    "无法执行 SQL",
                    "当前消息没有数据源上下文"
            );
        }

        DataSourceConfig config = configRepository
                .findByIdAndUserId(
                        context.dataSourceConfigId(),
                        context.userId()
                )
                .orElse(null);
        if (config == null) {
            return AgentToolResult.failure(
                    "{\"error\":\"数据源不存在或无权使用\"}",
                    "SQL 执行失败",
                    "数据源不属于当前用户"
            );
        }

        String sql = arguments.path("sql").asText("");
        try {
            SqlSafetyService.ExecutionResult execution =
                    safetyService.executeReadOnly(config, sql);
            ObjectNode result = objectMapper.createObjectNode();
            result.put("sql", execution.sql());
            result.put("truncated", execution.truncated());
            result.put("elapsed_ms", execution.elapsedMs());
            result.put("rows_total", execution.rows().size());
            List<java.util.Map<String, Object>> modelRows =
                    execution.rows().stream()
                            .limit(Math.max(1, maxToolResultRows))
                            .toList();
            result.put("rows_returned_to_model", modelRows.size());
            result.set(
                    "rows",
                    objectMapper.valueToTree(modelRows)
            );

            context.state().setSqlGenerated(execution.sql());
            context.state().setSqlResults(execution.rows());
            context.state().setSqlExecutionSuccess(true);
            context.state().setSqlErrorMessage(null);
            context.state().setLastValidatedSql(execution.sql());
            context.state().setLastStatementType("Select");
            context.state().setReadOnly(true);

            return AgentToolResult.success(
                    objectMapper.writeValueAsString(result),
                    "只读查询执行成功",
                    "返回 " + execution.rows().size() + " 行"
                            + (execution.truncated() ? "，结果已截断" : "")
            );
        } catch (Exception exception) {
            context.state().setSqlExecutionSuccess(false);
            context.state().setSqlErrorMessage(exception.getMessage());
            context.state().setErrorCode("QUERY_EXECUTION_FAILED");
            return AgentToolResult.failure(
                    "SQL 执行被拒绝或失败：" + exception.getMessage(),
                    "只读查询未执行",
                    exception.getMessage()
            );
        }
    }
}
