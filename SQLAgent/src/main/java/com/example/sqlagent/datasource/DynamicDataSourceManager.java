package com.example.sqlagent.datasource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DynamicDataSourceManager {

    private final Map<Long, HikariDataSource> dataSourceCache = new ConcurrentHashMap<>();

    /**
     * 创建或获取数据源
     */
    public DataSource getDataSource(DataSourceConfig config) {
        HikariDataSource ds = dataSourceCache.get(config.getId());
        if (ds == null || ds.isClosed()) {
            synchronized (this) {
                ds = dataSourceCache.get(config.getId());
                if (ds == null || ds.isClosed()) {
                    ds = createDataSource(config);
                    dataSourceCache.put(config.getId(), ds);
                }
            }
        }
        return ds;
    }

    /**
     * 创建 HikariCP 数据源
     */
    private HikariDataSource createDataSource(DataSourceConfig config) {
        HikariConfig hikariConfig = new HikariConfig();
        String jdbcUrl = buildJdbcUrl(config);
        hikariConfig.setJdbcUrl(jdbcUrl);
        hikariConfig.setUsername(config.getUsername());
        hikariConfig.setPassword(config.getPassword());
        hikariConfig.setDriverClassName(getDriverClassName(config.getDbType()));
        hikariConfig.setMaximumPoolSize(5);
        hikariConfig.setMinimumIdle(1);
        hikariConfig.setConnectionTimeout(30000);
        hikariConfig.setIdleTimeout(600000);
        hikariConfig.setMaxLifetime(1800000);
        return new HikariDataSource(hikariConfig);
    }

    /**
     * 构建 JDBC URL
     */
    private String buildJdbcUrl(DataSourceConfig config) {
        return switch (config.getDbType().toLowerCase()) {
            case "mysql" -> String.format("jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true",
                    config.getHost(), config.getPort(), config.getDatabaseName());
            case "postgresql" -> String.format("jdbc:postgresql://%s:%d/%s",
                    config.getHost(), config.getPort(), config.getDatabaseName());
            case "sqlserver" -> String.format("jdbc:sqlserver://%s:%d;databaseName=%s",
                    config.getHost(), config.getPort(), config.getDatabaseName());
            default -> throw new IllegalArgumentException("不支持的数据库类型: " + config.getDbType());
        };
    }

    /**
     * 获取驱动类名
     */
    private String getDriverClassName(String dbType) {
        return switch (dbType.toLowerCase()) {
            case "mysql" -> "com.mysql.cj.jdbc.Driver";
            case "postgresql" -> "org.postgresql.Driver";
            case "sqlserver" -> "com.microsoft.sqlserver.jdbc.SQLServerDriver";
            default -> throw new IllegalArgumentException("不支持的数据库类型: " + dbType);
        };
    }

    /**
     * 测试数据库连接
     */
    public String testConnection(String dbType, String host, Integer port, String databaseName,
                                   String username, String password) {
        String jdbcUrl = buildJdbcUrlForTest(dbType, host, port, databaseName);
        String driverClass = getDriverClassName(dbType);

        try {
            Class.forName(driverClass);
            try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password)) {
                if (conn.isValid(5)) {
                    return null; // 连接成功
                }
                return "数据库连接超时或不可用";
            }
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg != null) {
                if (msg.contains("Access denied") || msg.contains("access denied")) {
                    return "用户名或密码错误";
                } else if (msg.contains("Unknown database") || msg.contains("unknown database")) {
                    return "数据库 '" + databaseName + "' 不存在";
                } else if (msg.contains("Unknown host") || msg.contains("unknown host")) {
                    return "无法解析主机地址 '" + host + "'，请检查主机地址是否正确";
                } else if (msg.contains("Connection refused") || msg.contains("connect timed out") || msg.contains("CommunicationsException")) {
                    return "无法连接到 " + host + ":" + port + "，请确保：\n" +
                           "1. 数据库允许远程连接（MySQL 用户需有 '%' 权限）\n" +
                           "2. 数据库所在服务器的防火墙已放行 " + port + " 端口\n" +
                           "3. 数据库服务正在运行\n" +
                           "4. 如果数据库在本地，需要使用内网穿透工具（如 ngrok）暴露到公网";
                }
            }
            return "连接失败: " + msg;
        }
    }

    private String buildJdbcUrlForTest(String dbType, String host, Integer port, String databaseName) {
        return switch (dbType.toLowerCase()) {
            case "mysql" -> String.format("jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=Asia/Shanghai&connectTimeout=5000",
                    host, port, databaseName);
            case "postgresql" -> String.format("jdbc:postgresql://%s:%d/%s?connectTimeout=5",
                    host, port, databaseName);
            case "sqlserver" -> String.format("jdbc:sqlserver://%s:%d;databaseName=%s;loginTimeout=5",
                    host, port, databaseName);
            default -> throw new IllegalArgumentException("不支持的数据库类型: " + dbType);
        };
    }

    /**
     * 执行 SQL 查询并返回结果
     * 支持多条语句（以分号分隔），全部执行并收集所有结果
     */
    public List<Map<String, Object>> executeQuery(DataSourceConfig config, String sql) {
        DataSource ds = getDataSource(config);
        List<Map<String, Object>> results = new ArrayList<>();

        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {

            // 使用 execute() 替代 executeQuery()，兼容 SELECT 和非查询语句
            boolean hasResultSet = stmt.execute(sql);

            // 循环处理多条语句的所有结果
            while (true) {
                if (hasResultSet) {
                    // SELECT 查询，获取结果集
                    try (ResultSet rs = stmt.getResultSet()) {
                        ResultSetMetaData metaData = rs.getMetaData();
                        int columnCount = metaData.getColumnCount();
                        while (rs.next()) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            for (int i = 1; i <= columnCount; i++) {
                                row.put(metaData.getColumnLabel(i), rs.getObject(i));
                            }
                            results.add(row);
                        }
                    }
                } else {
                    // UPDATE/INSERT/DELETE，记录影响行数
                    int updateCount = stmt.getUpdateCount();
                    if (updateCount >= 0) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("affected_rows", updateCount);
                        results.add(row);
                    }
                }

                // 检查是否还有更多结果（多条语句的情况）
                // getMoreResults() 返回 true 表示下一个结果是 ResultSet
                // getUpdateCount() 返回 -1 表示没有更多结果
                if (stmt.getMoreResults()) {
                    hasResultSet = true;
                } else if (stmt.getUpdateCount() == -1) {
                    break; // 没有更多结果了
                } else {
                    hasResultSet = false;
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("SQL执行失败: " + e.getMessage());
        }

        return results;
    }


    /**
     * 执行 SQL 更新（INSERT, UPDATE, DELETE）并返回影响行数
     */
    public int executeUpdate(DataSourceConfig config, String sql) {
        DataSource ds = getDataSource(config);
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            return stmt.executeUpdate(sql);
        } catch (SQLException e) {
            throw new RuntimeException("SQL执行失败: " + e.getMessage());
        }
    }

    /**
     * 移除缓存的数据源
     */
    public void removeDataSource(Long configId) {
        HikariDataSource ds = dataSourceCache.remove(configId);
        if (ds != null && !ds.isClosed()) {
            ds.close();
        }
    }
}
