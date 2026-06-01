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
    public boolean testConnection(String dbType, String host, Integer port, String databaseName,
                                   String username, String password) {
        String jdbcUrl = buildJdbcUrlForTest(dbType, host, port, databaseName);
        String driverClass = getDriverClassName(dbType);

        try {
            Class.forName(driverClass);
            try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password)) {
                return conn.isValid(5);
            }
        } catch (Exception e) {
            return false;
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
     */
    public List<Map<String, Object>> executeQuery(DataSourceConfig config, String sql) {
        DataSource ds = getDataSource(config);
        List<Map<String, Object>> results = new ArrayList<>();

        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();

            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.put(metaData.getColumnLabel(i), rs.getObject(i));
                }
                results.add(row);
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
