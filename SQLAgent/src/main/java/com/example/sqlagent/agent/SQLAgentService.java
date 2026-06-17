package com.example.sqlagent.agent;

import com.example.sqlagent.datasource.DataSourceConfig;
import com.example.sqlagent.datasource.DataSourceConfigRepository;
import com.example.sqlagent.datasource.DynamicDataSourceManager;
import com.example.sqlagent.dto.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SQLAgentService {

    private static final Logger log = LoggerFactory.getLogger(SQLAgentService.class);

    @Autowired
    private DeepSeekService deepSeekService;

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private DynamicDataSourceManager dynamicDataSourceManager;

    @Autowired
    private DataSourceConfigRepository dataSourceConfigRepository;

    @Value("${sql-agent.max-retry-times:3}")
    private int maxRetryTimes;

    @Value("${sql-agent.vote-count:10}")
    private int voteCount;

    @Value("${sql-agent.temperature:0.8}")
    private double temperature;

    /**
     * 处理聊天请求
     * 流程：
     * 1. 如果启用知识库，先通过智谱对话 API + tools.retrieval 查询知识库
     *    （智谱会自动检索知识库并基于知识库内容回答，返回的是 AI 润色后的回答）
     * 2. 如果启用 SQL Agent，将知识库回答作为上下文注入到 SQL 生成的 prompt 中
     * 3. 如果只启用知识库（无 SQL Agent），直接使用智谱返回的回答
     */
    public ChatResponse processChat(String userMessage, Long sessionId,
                                     Boolean useKnowledgeBase, Boolean useSqlAgent,
                                     Long dataSourceConfigId) {

        ChatResponse response = new ChatResponse();

        // 1. 知识库查询（如果启用）
        String kbResult = null;
        if (Boolean.TRUE.equals(useKnowledgeBase)) {
            kbResult = knowledgeBaseService.queryKnowledgeBase(userMessage);
            if (kbResult != null && !kbResult.isEmpty()) {
                response.setFromKnowledgeBase(true);
                response.setKnowledgeBaseContent(kbResult);
                log.info("知识库查询到相关内容，长度: {}", kbResult.length());
            } else {
                log.info("知识库未查询到相关内容");
            }
        }

        // 2. SQL Agent 模式（如果启用且配置了数据源）
        if (Boolean.TRUE.equals(useSqlAgent) && dataSourceConfigId != null) {
            return processSQLQuery(userMessage, dataSourceConfigId, kbResult, response);
        }

        // 3. 普通对话模式
        //    知识库已由智谱 AI 自动润色回答，直接使用
        if (kbResult != null) {
            response.setReply(kbResult);
        } else {
            String reply = deepSeekService.normalChat(userMessage, null);
            response.setReply(reply);
        }
        return response;
    }

    /**
     * 处理 SQL 查询（包含 Agent 反馈机制 + SQL 多投定优机制）
     * @param kbResult 知识库查询结果，可能为null
     */
    private ChatResponse processSQLQuery(String userQuestion, Long dataSourceConfigId,
                                          String kbResult, ChatResponse response) {
        DataSourceConfig config = dataSourceConfigRepository.findById(dataSourceConfigId)
                .orElseThrow(() -> new RuntimeException("数据源配置不存在"));

        // 获取数据库表结构信息
        String tableSchema = getTableSchema(config);

        // ========== 机制一：SQL 多投定优机制 ==========
        // 使用高 Temperature 生成多个候选 SQL
        List<String> candidateSQLs = new ArrayList<>();
        for (int i = 0; i < voteCount; i++) {
            try {
                String rawSql = deepSeekService.generateSQL(userQuestion, config.getDbType(), tableSchema, temperature, kbResult);
                String sql = cleanSQL(rawSql);
                if (sql != null && !sql.trim().isEmpty()) {
                    candidateSQLs.add(sql.trim());
                }
            } catch (Exception e) {
                // 个别生成失败忽略
            }
        }

        if (candidateSQLs.isEmpty()) {
            response.setReply("无法生成SQL语句，请重试或修改问题描述。");
            response.setSqlExecutionSuccess(false);
            response.setSqlErrorMessage("所有候选SQL生成失败");
            return response;
        }

        // 执行所有候选 SQL 并收集结果
        Map<String, SQLVoteResult> voteResults = new LinkedHashMap<>();
        for (String sql : candidateSQLs) {
            try {
                List<Map<String, Object>> results = dynamicDataSourceManager.executeQuery(config, sql);
                String resultKey = resultsToString(results);
                voteResults.merge(sql, new SQLVoteResult(results, null, true),
                        (existing, incoming) -> {
                            existing.voteCount++;
                            return existing;
                        });
            } catch (Exception e) {
                voteResults.merge(sql, new SQLVoteResult(null, e.getMessage(), false),
                        (existing, incoming) -> {
                            existing.voteCount++;
                            return existing;
                        });
            }
        }

        // 投票选择：找出执行成功且结果最多的 SQL
        SQLVoteResult bestResult = voteResults.values().stream()
                .filter(r -> r.success)
                .max(Comparator.comparingInt(r -> r.voteCount))
                .orElse(null);

        if (bestResult != null) {
            // 找到最佳 SQL
            String bestSQL = voteResults.entrySet().stream()
                    .filter(e -> e.getValue() == bestResult)
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(candidateSQLs.get(0));

            response.setSqlGenerated(bestSQL);
            response.setSqlResults(bestResult.results);
            response.setSqlExecutionSuccess(true);

            // 生成自然语言回复（如果知识库有内容，也一并传入）
            String reply = generateReplyFromResults(userQuestion, bestSQL, bestResult.results, kbResult);
            response.setReply(reply);
        } else {
            // ========== 机制二：Agent 反馈机制 ==========
            // 所有候选 SQL 都失败了，进入 Agent 反馈修正流程
            response = agentFeedbackProcess(userQuestion, config, tableSchema, candidateSQLs, response, kbResult);
        }

        return response;
    }

    /**
     * Agent 反馈机制：不断修正 SQL 直到成功或达到最大重试次数
     */
    private ChatResponse agentFeedbackProcess(String userQuestion, DataSourceConfig config,
                                               String tableSchema, List<String> failedSQLs,
                                               ChatResponse response, String kbResult) {
        String currentSQL = cleanSQL(failedSQLs.get(0)); // 取第一个失败的 SQL 作为起点，先清洗
        String lastError = null;

        for (int attempt = 0; attempt < maxRetryTimes; attempt++) {
            try {
                // 尝试执行当前 SQL
                List<Map<String, Object>> results = dynamicDataSourceManager.executeQuery(config, currentSQL);

                // 执行成功
                response.setSqlGenerated(currentSQL);
                response.setSqlResults(results);
                response.setSqlExecutionSuccess(true);

                String reply = generateReplyFromResults(userQuestion, currentSQL, results, kbResult);
                response.setReply(reply);
                return response;

            } catch (Exception e) {
                lastError = e.getMessage();

                // 将错误信息反馈给模型，让模型修正 SQL
                try {
                    String fixedSQL = deepSeekService.fixSQLWithFeedback(
                            userQuestion, config.getDbType(), tableSchema,
                            currentSQL, null, lastError, kbResult);

                    if (fixedSQL != null && !fixedSQL.trim().isEmpty()
                            && !fixedSQL.equals(currentSQL)) {
                        currentSQL = cleanSQL(fixedSQL.trim());
                    }
                } catch (Exception fixError) {
                    // 修正失败，继续尝试
                }
            }
        }

        // 所有重试都失败
        response.setSqlGenerated(currentSQL);
        response.setSqlExecutionSuccess(false);
        response.setSqlErrorMessage(lastError);
        response.setReply("经过" + maxRetryTimes + "次尝试后仍无法生成正确的SQL。\n" +
                "最后一次尝试的SQL：\n```sql\n" + currentSQL + "\n```\n" +
                "错误信息：" + lastError);
        return response;
    }

    /**
     * 获取数据库表结构信息
     */
    private String getTableSchema(DataSourceConfig config) {
        StringBuilder schema = new StringBuilder();
        try (Connection conn = dynamicDataSourceManager.getDataSource(config).getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();

            // 获取所有表
            try (ResultSet tables = metaData.getTables(null, null, "%", new String[]{"TABLE"})) {
                while (tables.next()) {
                    String tableName = tables.getString("TABLE_NAME");
                    schema.append("表名: ").append(tableName).append("\n");

                    // 获取表的列信息
                    try (ResultSet columns = metaData.getColumns(null, null, tableName, "%")) {
                        while (columns.next()) {
                            String colName = columns.getString("COLUMN_NAME");
                            String colType = columns.getString("TYPE_NAME");
                            int colSize = columns.getInt("COLUMN_SIZE");
                            schema.append("  - ").append(colName)
                                    .append(" (").append(colType)
                                    .append("(").append(colSize).append("))\n");
                        }
                    }
                    schema.append("\n");
                }
            }
        } catch (Exception e) {
            schema.append("无法获取表结构: ").append(e.getMessage());
        }
        return schema.toString();
    }

    /**
     * 将结果集转为字符串用于投票比较
     */
    private String resultsToString(List<Map<String, Object>> results) {
        if (results == null || results.isEmpty()) return "empty";
        return results.stream()
                .map(Map::toString)
                .collect(Collectors.joining(";"));
    }

    /**
     * 根据 SQL 执行结果生成自然语言回复
     */
    private String generateReplyFromResults(String question, String sql, List<Map<String, Object>> results, String kbResult) {
        StringBuilder reply = new StringBuilder();

        // 如果有知识库内容，先显示知识库标记
        if (kbResult != null && !kbResult.isEmpty()) {
            reply.append("📚 **知识库参考**\n\n");
        }

        reply.append("✅ SQL执行成功！\n\n");
        reply.append("**生成的SQL：**\n```sql\n").append(sql).append("\n```\n\n");

        if (results == null || results.isEmpty()) {
            reply.append("**查询结果：** 没有找到匹配的数据。");
        } else {
            reply.append("**查询结果：** 共找到 ").append(results.size()).append(" 条记录\n\n");

            // 显示前10条结果
            int displayCount = Math.min(results.size(), 10);
            for (int i = 0; i < displayCount; i++) {
                Map<String, Object> row = results.get(i);
                reply.append("**记录 ").append(i + 1).append("：**\n");
                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    reply.append("- ").append(entry.getKey()).append(": ")
                            .append(entry.getValue()).append("\n");
                }
                reply.append("\n");
            }

            if (results.size() > 10) {
                reply.append("... 还有 ").append(results.size() - 10).append(" 条记录\n");
            }
        }

        return reply.toString();
    }

    /**
     * 清洗 AI 生成的 SQL，去除多余内容
     * 1. 去除 markdown 代码块标记（```sql, ```, ` 等）
     * 2. 去除 "SQL:" 等前缀
     * 3. 去除首尾空白
     * 保留完整的多条语句（以分号分隔），全部执行
     */
    private String cleanSQL(String rawSQL) {
        if (rawSQL == null || rawSQL.trim().isEmpty()) {
            return rawSQL;
        }

        String sql = rawSQL.trim();

        // 1. 去除 markdown 代码块标记
        sql = sql.replaceAll("(?i)```sql\\s*", "");
        sql = sql.replaceAll("```", "");
        sql = sql.replaceAll("`", "");

        // 2. 去除 "SQL:"、"sql:" 等前缀
        sql = sql.replaceAll("(?i)^\\s*(sql|SQL|sql语句|SQL语句|查询语句)[:\\s]*", "");

        // 3. 去除首尾空白
        sql = sql.trim();

        return sql;
    }

    /**
     * SQL 投票结果内部类
     */
    private static class SQLVoteResult {

        List<Map<String, Object>> results;
        String errorMessage;
        boolean success;
        int voteCount;

        SQLVoteResult(List<Map<String, Object>> results, String errorMessage, boolean success) {
            this.results = results;
            this.errorMessage = errorMessage;
            this.success = success;
            this.voteCount = 1;
        }
    }
}
