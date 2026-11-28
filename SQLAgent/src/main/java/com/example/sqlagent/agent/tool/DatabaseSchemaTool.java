package com.example.sqlagent.agent.tool;

import com.example.sqlagent.agent.DatabaseSchemaService;
import com.example.sqlagent.datasource.DataSourceConfig;
import com.example.sqlagent.datasource.DataSourceConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

@Component
public class DatabaseSchemaTool implements AgentTool {
    private final DataSourceConfigRepository configRepository;
    private final DatabaseSchemaService schemaService;

    public DatabaseSchemaTool(
            DataSourceConfigRepository configRepository,
            DatabaseSchemaService schemaService
    ) {
        this.configRepository = configRepository;
        this.schemaService = schemaService;
    }

    @Override
    public String name() {
        return "inspect_database_schema";
    }

    @Override
    public String description() {
        return "读取当前用户所选数据源的真实表、字段和类型。"
                + "可以传 table_name 只查看一张表，也可以不传查看全部表。";
    }

    @Override
    public ObjectNode parameters(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode tableName = properties.putObject("table_name");
        tableName.put("type", "string");
        tableName.put("description", "可选，表名或视图名；不传则读取全部");
        schema.putArray("required");
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
                    "无法读取数据库结构",
                    "SQL Agent 模式需要先选择数据源"
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
                    "读取数据库结构失败",
                    "数据源不属于当前用户"
            );
        }

        String tableName = arguments.path("table_name").asText(null);
        try {
            String schema = schemaService.describeSchema(config, tableName);
            String summary = tableName == null || tableName.isBlank()
                    ? "读取数据库结构"
                    : "读取表 " + tableName + " 的结构";
            return AgentToolResult.success(
                    schema,
                    summary,
                    "数据源：" + config.getName()
                            + "；返回 " + schema.length() + " 字符"
            );
        } catch (Exception exception) {
            return AgentToolResult.failure(
                    "读取数据库结构失败：" + exception.getMessage(),
                    "读取数据库结构失败",
                    exception.getMessage()
            );
        }
    }
}
