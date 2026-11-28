package com.example.sqlagent.agent;

import com.example.sqlagent.datasource.DataSourceConfig;
import com.example.sqlagent.datasource.DynamicDataSourceManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.Locale;

@Service
public class DatabaseSchemaService {
    private final DynamicDataSourceManager dataSourceManager;

    @Value("${agent.max-schema-chars:30000}")
    private int maxSchemaChars = 30_000;

    public DatabaseSchemaService(
            DynamicDataSourceManager dataSourceManager
    ) {
        this.dataSourceManager = dataSourceManager;
    }

    public String describeSchema(DataSourceConfig config, String tableName) {
        int limit = Math.max(2_000, Math.min(maxSchemaChars, 100_000));
        StringBuilder result = new StringBuilder();
        result.append("数据库类型：")
                .append(config.getDbType())
                .append("\n数据库：")
                .append(config.getDatabaseName())
                .append("\n\n");

        try (Connection connection = dataSourceManager
                .getDataSource(config)
                .getConnection()) {
            String catalog = safeCatalog(connection);
            String schema = safeSchema(connection);
            String tablePattern = tableName == null || tableName.isBlank()
                    ? "%"
                    : tableName;

            int tableCount = 0;
            try (ResultSet tables = connection.getMetaData().getTables(
                    catalog,
                    schema,
                    tablePattern,
                    new String[]{"TABLE", "VIEW"}
            )) {
                while (tables.next() && result.length() < limit) {
                    tableCount++;
                    String name = tables.getString("TABLE_NAME");
                    String type = tables.getString("TABLE_TYPE");
                    result.append("表：").append(name)
                            .append("（").append(type).append("）\n");

                    try (ResultSet columns = connection.getMetaData().getColumns(
                            catalog,
                            schema,
                            name,
                            "%"
                    )) {
                        while (columns.next() && result.length() < limit) {
                            result.append("  - ")
                                    .append(columns.getString("COLUMN_NAME"))
                                    .append(" ")
                                    .append(columns.getString("TYPE_NAME"))
                                    .append("(")
                                    .append(columns.getInt("COLUMN_SIZE"))
                                    .append(")")
                                    .append(columns.getInt("NULLABLE")
                                            == DatabaseMetaData.columnNoNulls
                                            ? " NOT NULL"
                                            : "")
                                    .append("\n");
                        }
                    }
                    result.append('\n');
                }
            }

            if (tableCount == 0) {
                return result + "没有找到匹配的表或视图。";
            }
            if (result.length() > limit) {
                result.setLength(limit);
                result.append("\n[表结构已截断]");
            }
            return result.toString();
        } catch (SQLException exception) {
            throw new com.example.sqlagent.error.QueryExecutionException(
                    com.example.sqlagent.error.ErrorCode.QUERY_EXECUTION_FAILED,
                    "读取数据库结构失败：" + exception.getMessage(),
                    exception
            );
        }
    }

    private String safeCatalog(Connection connection) {
        try {
            return connection.getCatalog();
        } catch (SQLException ignored) {
            return null;
        }
    }

    private String safeSchema(Connection connection) {
        try {
            String schema = connection.getSchema();
            if (schema != null
                    && schema.toLowerCase(Locale.ROOT).equals("public")) {
                return schema;
            }
            return schema;
        } catch (SQLException ignored) {
            return null;
        }
    }
}
