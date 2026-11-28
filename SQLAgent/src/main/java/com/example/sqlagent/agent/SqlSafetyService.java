package com.example.sqlagent.agent;

import com.example.sqlagent.datasource.DataSourceConfig;
import com.example.sqlagent.datasource.DynamicDataSourceManager;
import com.example.sqlagent.error.ErrorCode;
import com.example.sqlagent.error.QueryExecutionException;
import com.example.sqlagent.error.ValidationException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.time.temporal.TemporalAccessor;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class SqlSafetyService {
    private static final Pattern FORBIDDEN_SELECT_CONSTRUCT = Pattern.compile(
            "(?is)(?:\\binto\\s+(?:outfile|dumpfile)\\b"
                    + "|\\bload_file\\s*\\(|\\bfor\\s+update\\b"
                    + "|\\block\\s+in\\s+share\\s+mode\\b)"
    );
    private static final Pattern BLOCK_COMMENT =
            Pattern.compile("(?s)/\\*.*?\\*/");
    private static final Pattern LINE_COMMENT =
            Pattern.compile("(?m)--[^\\r\\n]*");

    private final DynamicDataSourceManager dataSourceManager;

    @Value("${agent.query-timeout-seconds:15}")
    private int queryTimeoutSeconds = 15;

    @Value("${agent.max-query-rows:200}")
    private int maxQueryRows = 200;

    @Value("${agent.max-result-cell-chars:4000}")
    private int maxCellChars = 4_000;

    public SqlSafetyService(DynamicDataSourceManager dataSourceManager) {
        this.dataSourceManager = dataSourceManager;
    }

    public SqlAnalysis analyze(String rawSql) {
        String sql = clean(rawSql);
        if (sql.isBlank()) {
            throw validation("SQL 不能为空");
        }

        try {
            Statements statements = CCJSqlParserUtil.parseStatements(sql);
            if (statements.getStatements() == null
                    || statements.getStatements().isEmpty()) {
                throw validation("没有解析到 SQL 语句");
            }

            List<String> types = new ArrayList<>();
            boolean allReadOnly = true;
            for (Statement statement : statements.getStatements()) {
                types.add(statement.getClass().getSimpleName());
                if (!(statement instanceof Select)) {
                    allReadOnly = false;
                }
            }

            String compact = removeComments(sql);
            boolean forbidden = FORBIDDEN_SELECT_CONSTRUCT
                    .matcher(compact)
                    .find();
            boolean readOnly = allReadOnly && !forbidden;
            boolean executable = statements.getStatements().size() == 1
                    && readOnly;

            String reason;
            if (forbidden) {
                reason = "包含写文件、锁表或其他禁止的 SELECT 构造";
            } else if (!allReadOnly) {
                reason = "包含非 SELECT 语句，可以生成和展示，但不能由只读执行工具运行";
            } else if (statements.getStatements().size() != 1) {
                reason = "多条语句需要拆分后逐条处理";
            } else {
                reason = "单条只读 SELECT，可以安全执行";
            }

            return new SqlAnalysis(
                    sql,
                    types,
                    readOnly,
                    executable,
                    reason
            );
        } catch (ValidationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw validation("SQL 语法校验失败：" + exception.getMessage());
        }
    }

    public ExecutionResult executeReadOnly(
            DataSourceConfig config,
            String rawSql
    ) {
        SqlAnalysis analysis = analyze(rawSql);
        if (!analysis.executable()) {
            throw validation(analysis.reason());
        }

        DataSource dataSource = dataSourceManager.getDataSource(config);
        int safeRows = Math.max(1, Math.min(maxQueryRows, 1_000));
        int safeCellChars = Math.max(100, Math.min(maxCellChars, 20_000));
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean truncated = false;
        long startedAt = System.nanoTime();

        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(
                    analysis.sql(),
                    ResultSet.TYPE_FORWARD_ONLY,
                    ResultSet.CONCUR_READ_ONLY
            )) {
                statement.setQueryTimeout(
                        Math.max(1, Math.min(queryTimeoutSeconds, 120))
                );
                statement.setMaxRows(safeRows + 1);

                try (ResultSet resultSet = statement.executeQuery()) {
                    ResultSetMetaData metadata = resultSet.getMetaData();
                    int columnCount = metadata.getColumnCount();
                    while (resultSet.next()) {
                        if (rows.size() >= safeRows) {
                            truncated = true;
                            break;
                        }
                        Map<String, Object> row = new LinkedHashMap<>();
                        Set<String> usedNames = new HashSet<>();
                        for (int index = 1; index <= columnCount; index++) {
                            String name = uniqueName(
                                    metadata.getColumnLabel(index),
                                    usedNames
                            );
                            row.put(
                                    name,
                                    normalizeValue(
                                            resultSet.getObject(index),
                                            safeCellChars
                                    )
                            );
                        }
                        rows.add(row);
                    }
                }
            }
            connection.rollback();
        } catch (SQLException exception) {
            throw new QueryExecutionException(
                    ErrorCode.QUERY_EXECUTION_FAILED,
                    "只读查询执行失败：" + exception.getMessage(),
                    exception
            );
        }

        return new ExecutionResult(
                analysis.sql(),
                List.copyOf(rows),
                truncated,
                (System.nanoTime() - startedAt) / 1_000_000L
        );
    }

    private String clean(String rawSql) {
        if (rawSql == null) {
            return "";
        }
        String sql = rawSql.trim();
        if (sql.startsWith("```")) {
            int firstLine = sql.indexOf('\n');
            int end = sql.lastIndexOf("```");
            if (firstLine >= 0 && end > firstLine) {
                sql = sql.substring(firstLine + 1, end);
            }
        }
        return sql.trim();
    }

    private String removeComments(String sql) {
        return LINE_COMMENT.matcher(
                BLOCK_COMMENT.matcher(sql).replaceAll(" ")
        ).replaceAll(" ").replaceAll("\\s+", " ").trim();
    }

    private String uniqueName(String preferred, Set<String> used) {
        String base = preferred == null || preferred.isBlank()
                ? "column"
                : preferred;
        String name = base;
        int suffix = 2;
        while (!used.add(name)) {
            name = base + "_" + suffix++;
        }
        return name;
    }

    private Object normalizeValue(Object value, int limit) {
        if (value == null
                || value instanceof Number
                || value instanceof Boolean) {
            return value;
        }
        if (value instanceof String text) {
            return truncate(text, limit);
        }
        if (value instanceof byte[] bytes) {
            return "[binary " + bytes.length + " bytes]";
        }
        if (value instanceof Blob blob) {
            try {
                return "[blob " + blob.length() + " bytes]";
            } catch (SQLException ignored) {
                return "[blob]";
            }
        }
        if (value instanceof java.sql.Date
                || value instanceof java.sql.Time
                || value instanceof java.sql.Timestamp
                || value instanceof TemporalAccessor
                || value instanceof UUID) {
            return value.toString();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return truncate(String.valueOf(value), limit);
    }

    private String truncate(String text, int limit) {
        return text.length() <= limit
                ? text
                : text.substring(0, limit) + "...";
    }

    private ValidationException validation(String message) {
        return new ValidationException(
                ErrorCode.SQL_VALIDATION_FAILED,
                message
        );
    }

    public record SqlAnalysis(
            String sql,
            List<String> statementTypes,
            boolean readOnly,
            boolean executable,
            String reason
    ) {
        public SqlAnalysis {
            statementTypes = statementTypes == null
                    ? List.of()
                    : List.copyOf(statementTypes);
        }
    }

    public record ExecutionResult(
            String sql,
            List<Map<String, Object>> rows,
            boolean truncated,
            long elapsedMs
    ) {
    }
}
