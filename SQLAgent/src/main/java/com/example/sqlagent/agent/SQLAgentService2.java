package com.example.sqlagent.agent;

import cn.dev33.satoken.stp.StpUtil;
import com.example.sqlagent.datasource.DataSourceConfig;
import com.example.sqlagent.datasource.DataSourceConfigRepository;
import com.example.sqlagent.datasource.DynamicDataSourceManager;
import com.example.sqlagent.dto.ChatResponse;
import com.example.sqlagent.error.ErrorCode;
import com.example.sqlagent.error.ForbiddenException;
import com.example.sqlagent.error.QueryExecutionException;
import com.example.sqlagent.error.ValidationException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.temporal.TemporalAccessor;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.Math.clamp;


@Service
public class SQLAgentService2 {
    private static final Logger log =
            LoggerFactory.getLogger(SQLAgentService2.class);

    private static final Pattern FENCED_SQL = Pattern.compile(
            "(?is)```(?:sql)?\\s*(.*?)```"
    );

    private static final Pattern SQL_PREFIX = Pattern.compile(
            "(?is)^\\s*(?:sql|sql语句|查询语句)\\s*:\\s*"
    );

    private static final Pattern BLOCK_COMMENT = Pattern.compile(
            "(?s)/\\*.*?\\*/"
    );

    private static final Pattern LINE_COMMENT = Pattern.compile(
            "(?m)--[^\\r\\n]*"
    );

    /**
     * 解析器已经保证只有 Select 才会继续。
     * 这里额外拦截部分“SELECT 语法但会访问文件或加锁”的构造。
     */
    private static final Pattern FORBIDDEN_SELECT_CONSTRUCT = Pattern.compile(
            "(?is)(?:\\binto\\s+(?:outfile|dumpfile)\\b" +
                    "|\\bload_file\\s*\\(|\\bfor\\s+update\\b" +
                    "|\\block\\s+in\\s+share\\s+mode\\b)"
    );


    private final DeepSeekService deepSeekService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final DynamicDataSourceManager dynamicDataSourceManager;
    private final DataSourceConfigRepository dataSourceConfigRepository;

    @Value("${sql-agent-v2.candidate-count}")
    private int candidateCount;

    @Value("${sql-agent-v2.temperature}")
    private double temperature;

    @Value("${sql-agent-v2.min-consensus}")//一个结果簇至少要有 minConsensus 个独立 SQL 支持
    private int minConsensus;

    @Value("${sql-agent-v2.min-consensus-ratio}")//获胜结果簇至少占成功执行候选的 minConsensueRatio   %
    private double minConsensueRatio;

    /*
        新增：生成稳定性阈值。
        重复 AST 不增加独立结果票数，但如果多数原始样本都生成同一个 AST，
        说明模型对这次 SQL 生成较稳定，可以用于单候选场景的置信度解释。
     */
    @Value("${sql-agent-v2.generation-stability-high:0.8}")
    private double generationStabilityHigh = 0.8;

    @Value("${sql-agent-v2.generation-stability-medium:0.6}")
    private double generationStabilityMedium = 0.6;

    @Value("${sql-agent-v2.query-timeout-seconds}")
    private int queryTimeoutSeconds;

    @Value("${sql-agent-v2.max-rows}")//单词查询最多返回maxRow行
    private int maxRow;

    @Value("${sql-agent-v2.max-cell-chars}")//单个文本单元格最多保留maxCellChars个字符
    private int maxCellChars;

    @Value("${sql-agent-v2.max-schema-chars}")//发给模型的数据库结构文本最大长度maxSchemaChars
    private int maxSchemaChars;

    @Value("${sql-agent-v2.repair-times}")//执行失败后最多进行repairTime次反馈修复
    private int repairTimes;

    public SQLAgentService2(
            DeepSeekService deepSeekService,
            KnowledgeBaseService knowledgeBaseService,
            DynamicDataSourceManager dynamicDataSourceManager,
            DataSourceConfigRepository dataSourceConfigRepository
    ) {
        this.deepSeekService = deepSeekService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.dynamicDataSourceManager = dynamicDataSourceManager;
        this.dataSourceConfigRepository = dataSourceConfigRepository;
    }

    public ChatResponse processChat(
        String userMessage,
        Long sessionId,
        Boolean useKnowledgeBase,
        Boolean useSqlAgent,
        Long dataSourceConfigId
    ){
        ChatResponse response = new ChatResponse();

        KnowledgeBaseResult knowledgeResult = KnowledgeBaseResult.empty();
        String knowledgeError = null;

        if (Boolean.TRUE.equals(useKnowledgeBase)) {
            try{
                knowledgeResult = knowledgeBaseService.retrieve(userMessage);
            } catch (KnowledgeBaseService.KnowledgeBaseException e){
                knowledgeError = e.getMessage();
                log.error("知识库检索失败：{}", knowledgeError);
            }

            response.setFromKnowledgeBase(knowledgeResult.found());
            response.setKnowledgeBaseContent(knowledgeResult.context());
            if(knowledgeError != null){
                response.setKnowledgeBaseError(knowledgeError);
            }
        }

        if (Boolean.TRUE.equals(useSqlAgent)) {
            if(dataSourceConfigId == null){
                response.setSqlExecutionSuccess(false);
                response.setSqlErrorMessage("SQL Agent 模式必须选着数据源");
                response.setErrorCode(ErrorCode.REQUEST_VALIDATION_FAILED.name());
                response.setReply("请选择一个数据源，再发起 SQL 查询");
                return response;
            }

            return processSQLQuery(
                    userMessage,
                    dataSourceConfigId,
                    knowledgeResult,
                    response
            );
        }

        if (knowledgeResult.found()) {
            try {
                response.setReply(
                        knowledgeBaseService.answer(userMessage,knowledgeResult)
                );
            } catch (KnowledgeBaseService.KnowledgeBaseException e) {
                response .setFromKnowledgeBase(false);
                response.setKnowledgeBaseError(e.getMessage());
                response.setReply("知识库已检索到内容，但回答生成失败：" + e.getMessage());
            }
        } else if (Boolean.TRUE.equals(useKnowledgeBase)) {
            if (knowledgeError != null){
                response.setReply("知识库检索失败：" + knowledgeError);
            } else {
                response.setReply("知识库中未检索到与问题相关的内容。");
            }
        } else {
            response.setReply(deepSeekService.normalChat(userMessage, null));
        }

        return response;
    }


    /*
        实现只读 SQL 安全网关
        整个流程（cleanSql → 去注释 → 解析 AST → 规范化 → 算指纹）
        规范化后再哈希  可避免大小写不同导致 同一SQL语义/结构 得到的哈希不同
        最后使得 同一SQL语义/结构 得到一样的指纹（astFingerprint）
    */
    SafeCandidate parseAndValidate(String rawSql) {
        String sql = cleanSql(rawSql);
        if (sql.isBlank()) {
            throw new SqlValidationException("模型返回了空 SQL ");
        }

        try {
            //使用 JSqlParser 库，将一段可能包含多条 SQL 语句的字符串，解析成一个可遍历的 Java 对象集合（Statements）
            Statements statements = CCJSqlParserUtil.parseStatements(sql);

            if (statements.getStatements() == null || statements.getStatements().size() != 1) {
                throw new SqlValidationException("只允许一条 SQL 语句");
            }

            Statement statement = statements.getStatements().get(0);

            //判断语句是不是SELECT，只读语句
            if (!(statement instanceof Select)) {
                throw new SqlValidationException("只允许只读 SELECT 查询");
            }

            /*
                SQL 安全校验逻辑
                    1.清洗 sql 绕过防御：先去掉注释、压缩空白，防止用 /* / 、换行等手法把危险关键字拆开绕过检测
                    2.黑名单拦截：用正则拦住明确不该出现的危险构造（写文件、锁表、外部访问）
                    3.失败即拒绝：一旦命中直接抛异常，宁可误杀也不放行
                compactSql 是最后清洗两次的 sql
                再进行 FORBIDDEN_SELECT_CONSTRUCT.matcher(...).find() 正则检查危险关键字
            */
            String compactSql = removeCommentsForSafety(sql);
            if (FORBIDDEN_SELECT_CONSTRUCT.matcher(compactSql).find()){
                throw new SqlValidationException("SQL 包含禁止的写文件、锁表或外部访问构造");
            }

            /*
                把解析后的 SQL 语法树对象（statement）重新转成字符串，
                并做规范化处理（压缩空白、去首尾空格），
                到一个"标准化 AST 文本"用于后续比较或校验。
            */
            String normalizedAst = statement.toString()
                    .replaceAll("\\s+"," ")
                    .trim();

            /*
                对上一步得到的标准化 SQL 文本（normalizedAst）
                计算 SHA-256 哈希
                生成一个固定长度、不可逆的"指纹"字符串，用于后续的缓存、去重或审计比对
            */
            String astFingerprint = sha256(normalizedAst);

            return new SafeCandidate(sql, normalizedAst, astFingerprint);
        } catch (SqlValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new SqlValidationException(
                    "SQL 语法校验失败：" + safeMessage(e),
                    e
            );
        }
    }

    //清洗 生SQL：把大模型 返回的"可能带 Markdown 格式、带前缀说明"的文本，清洗成一段纯净的 SQL 字符串
    private String cleanSql(String rawSql) {
        if (rawSql == null) {
            return "";
        }

        String sql = rawSql.trim();

        Matcher fenced = FENCED_SQL.matcher(sql);
        if (fenced.find()) {
            sql = fenced.group(1).trim();
        }

        sql = SQL_PREFIX.matcher(sql).replaceFirst("").trim();

        // 不要删除反引号。`order`.`id` 是合法且必要的 SQL 标识符。
        return sql;
    }

    //再次清洗 SQL 去掉注释、压缩空白
    private String removeCommentsForSafety(String sql) {
        String withoutBlockComments = BLOCK_COMMENT.matcher(sql).replaceAll(" ");
        String withoutLineComments =
                LINE_COMMENT.matcher(withoutBlockComments).replaceAll(" ");

        return withoutLineComments.replaceAll("\\s+", " ").trim();
    }


    /*
        实现只读执行器
        拿到数据源 → 夹紧行数/字符上限 →
        只读连接 + 手动事务 → 预编译（只进只读、超时、多取 1 行）→
        流式遍历（列名兜底、去重、值归一化截断）→ 命中上限则标记截断 →
        回滚 → 计算耗时与结果指纹 → 封装成不可变的 ExecutionResult 返回。
    */
    private ExecutionResult executeReadOnly(
            DataSourceConfig config,
            SafeCandidate candidate,
            boolean orderSensitive
    ){
        DataSource dataSource = dynamicDataSourceManager.getDataSource(config);
        int safeMaxRows = clamp(maxRow, 1, 10_000); //行数上限
        int safeCellChar = clamp(maxCellChars, 100, 100_000); //单元格字符上限

        List<Map<String, Object>> rows = new ArrayList<>();
        boolean truncated = false;
        long start = System.nanoTime();

        try (Connection connection = dataSource.getConnection()){
            connection.setReadOnly(true);  //JDBC兜底
            connection.setAutoCommit(false); //目标库只使用只读账号

            try (PreparedStatement statement = connection.prepareStatement(
                    candidate.sql(),
                    ResultSet.TYPE_FORWARD_ONLY, //只进结果集，省内存
                    ResultSet.CONCUR_READ_ONLY  //只读并发模式
            )) {
                statement.setQueryTimeout(clamp(queryTimeoutSeconds, 1, 300));
                statement.setMaxRows(safeMaxRows + 1); //多取一行判断是否被拦截

                try (ResultSet resultSet = statement.executeQuery()){
                    ResultSetMetaData metaData = resultSet.getMetaData();
                    int columnCount = metaData.getColumnCount();

                    while (resultSet.next()) {
                        if (rows.size() >= safeMaxRows) {
                            truncated = true;
                            break;  //达到上限标记阶段返回
                        }

                        Map<String, Object> row = new LinkedHashMap<>(); //保持列顺序

                        for (int index = 1; index <= columnCount; index++) {
                            // 列名优先级：label -> name -> "column_N"
                            String columnName = firstNonBlank(   // 列名兜底
                                    metaData.getColumnLabel(index),
                                    metaData.getColumnName(index),
                                    "column_" + index
                            );

                            // 列名去重
                            String uniqueName = uniqueColumnName(row,columnName);

                            // 将生值归一化，返回安全可序列化的值，并截断到safeCellCars字符
                            Object rawValue = resultSet.getObject(index);
                            Object safeValue = normalizeJdbcValue(rawValue,safeCellChar);

                            row.put(uniqueName, safeValue);
                        }

                        rows.add(row);
                    }
                }
            }

            // 显式回滚，释放事务资源、结束事务快照，防止连接归还池时残留事务状态
            connection.rollback();


        } catch (SQLException e) {
            throw new CandidateExecutionException("只读SQL执行失败："+safeMessage(e), e);
        }

        long elapseMs = (System.nanoTime() - start) / 1_000_000L;

        String fingerprint = rows.isEmpty()
                ? "EMPTY:" + candidate.astFingerprint()
                : resultFingerprint(rows, orderSensitive, truncated);

        return new ExecutionResult(
                candidate,
                List.copyOf(rows),
                truncated,
                elapseMs,
                fingerprint
        );
    }


    /*
        生成稳定指纹结果
     */
    String resultFingerprint(
            List<Map<String, Object>> rows,
            boolean orderSensitive,
            boolean truncated

    ){
        List<String> rowFingerprints = new ArrayList<>(rows.size());

        for (Map<String, Object> row : rows) { // 遍历每一行
            List<String> values = new ArrayList<>(row.size());

            for (Object value : row.values()) {
                values.add(canonicalValue(value));
            }

            //生成单行指纹
            rowFingerprints.add(
                    values.size() + ":" + String.join("\u001F", values)
            );
        }
        // 顺序不敏感对指纹字符串排序
        if (!orderSensitive) {
            Collections.sort(rowFingerprints);
        }

        // 拼装最终payload
        String payload = "rows=" + rows.size()  // 行数，区分 空结果 和 有结果
                + "|truncated=" + truncated     // 同样前100行，被截断和没被截断语义不同，需要区别
                + "|data=" + String.join("\u001E", rowFingerprints); //用ASCII码连接所有指纹

        // 哈希
        return sha256(payload);
    }

    // 把任意类型转换成带类型前缀的规范字符串
    private String canonicalValue(Object value) {
        if (value == null) {
            return "z:";
        }

        if (value instanceof Number number) {
            String text = number.toString();
            try {
                return "n:"
                        + new BigDecimal(text)
                        .stripTrailingZeros()
                        .toPlainString();
            } catch (NumberFormatException ignored) {
                return "n:" + text;
            }
        }

        if (value instanceof Boolean bool) {
            return "b:" + bool;
        }

        if (value instanceof CharSequence text) {
            String actual = text.toString();
            return "s:" + actual.length() + ":" + actual;
        }

        String actual = String.valueOf(value);
        return "o:" + value.getClass().getName()
                + ":" + actual.length()
                + ":" + actual;
    }

    private boolean requiresOrder(String question) {
        // 空值防御
        if (question == null || question.isBlank()) {
            return false;
        }

        // 正则匹配
        return Pattern.compile(
                "(?i)(排序|顺序|升序|降序|前\\s*\\d+|top\\s*\\d+" +
                        "|最高|最低|最大|最小|最近|最新|最早" +
                        "|最多|最少|order\\s+by|asc\\b|desc\\b)"
        ).matcher(question).find();
    }


    /*
        生成并AST去重候选
     */
    private CandidatePool generateCandidates(
        // 下面四个都是生成SQL需要的上下文
        String question,  // 用户自然语言问题
        DataSourceConfig config,  // 目标数据源配置
        String schemaText,   // 数据库 schema 文本 （表/列描述）
        String knowledgeContext // 知识库上下文
    ) {

       int requestedCount = clamp(candidateCount, 3, 7); // 配置的候选数 3~7

       // 新增：AST 去重后的独立候选；重复 AST 只记录在 astSamples 中
       List<SafeCandidate> uniqueCandidates = new ArrayList<>();
       // 新增：记录每个 AST 被多少个原始样本生成，用于计算生成稳定性
       Map<String, Integer> astSamples = new LinkedHashMap<>();

       int parsedCount = 0;

       // 失败计数
       int generationFailures = 0;

       // 循环生成
        for (int index = 0; index < requestedCount; index++) {
            try {
                String rawSql = deepSeekService.generateSQL(
                        question,
                        config.getDbType(),
                        schemaText,
                        temperature,
                        knowledgeContext
                );

                SafeCandidate candidate = parseAndValidate(rawSql);
                parsedCount++;

                int previousCount = astSamples.getOrDefault(
                        candidate.astFingerprint(),
                        0
                );

                astSamples.put(
                        candidate.astFingerprint(),
                        previousCount + 1
                );

                if (previousCount > 0) {
                    log.info(
                            "候选 AST 重复：index={}，ast={}，sameAstSamples={}",
                            index,
                            candidate.astFingerprint(),
                            previousCount + 1
                    );
                    continue;
                }

                uniqueCandidates.add(candidate);

                log.info(
                        "生成唯一 AST 候选：index={}，ast={}，sqlLength={}",
                        index,
                        candidate.astFingerprint(),
                        candidate.sql().length()
                );

            } catch (Exception e) {
                generationFailures++;

                log.warn(
                        "候选 SQL 生成或校验失败：index={}，error={}",
                        index,
                        safeMessage(e)
                );
            }
        }

        CandidatePool pool = new CandidatePool(
                requestedCount,
                parsedCount,
                generationFailures,
                uniqueCandidates,
                astSamples
        );

        log.info(
                "候选生成完成：requested={}，parsed={}，unique={}，" +
                        "duplicates={}，failures={}，dominantAstSamples={}，" +
                        "generationStability={}%",
                pool.requestedCount(),
                pool.parsedCount(),
                pool.uniqueCandidates().size(),
                pool.duplicateAstCount(),
                pool.generationFailureCount(),
                pool.dominantAstSamples(),
                Math.round(pool.generationStability() * 10_000.0) / 100.0
        );

        return pool;
    }

    /*
        按结果指纹聚类和选择
        结果簇至少要有约定的独立 AST 支持数；
        最高簇必须严格高于第二簇；
        没有明确共识就返回 null，由上层要求澄清；
        完全相同 AST 已经在前面去重；
        平票不再按照插入顺序取第一条。
     */
    Selection selectByConsensus(
            List<ExecutionResult> executions,
            CandidatePool pool
    ) {
        // 没有任何执行结果或唯一候选，不参与共识选择
        if (executions.isEmpty() || pool.uniqueCandidates().isEmpty()) {
            return null;
        }

        int uniqueCandidateCount = pool.uniqueCandidates().size();

        // 按结果指纹分组：相同结果归入同一簇
        Map<String, List<ExecutionResult>> grouped = new LinkedHashMap<>();

        for (ExecutionResult execution : executions) {
            grouped.computeIfAbsent(
                    execution.fingerprint(),
                    key -> new ArrayList<>()
            ).add(execution);
        }

        // 将 Map 转换为可排序的结果簇列表
        List<ResultCluster> clusters = new ArrayList<>();
        for (Map.Entry<String, List<ExecutionResult>> entry : grouped.entrySet()) {
            clusters.add(new ResultCluster(
                    entry.getKey(),
                    List.copyOf(entry.getValue())
            ));
        }

        clusters.sort(this::compareClusters);

        ResultCluster top = clusters.get(0);
        ResultCluster second = clusters.size() > 1 ? clusters.get(1) : null;

        /*
            新增：单结果簇不再只看执行数量。
            - 多个唯一 AST 全部执行且结果一致：高
            - 只有一个唯一 AST，但多数原始样本重复生成它：按生成稳定性判断
            - 只有一个有效样本且没有重复证据：仍然低
         */
        if (clusters.size() == 1) {
            ExecutionResult best = chooseShortestFastest(top.executions());

            Confidence confidence;
            String reason;

            if (executions.size() >= 2
                    && executions.size() == uniqueCandidateCount) {
                if (pool.generationFailureCount() == 0) {
                    confidence = Confidence.HIGH;
                    reason = "全部唯一候选执行成功且结果一致";
                } else {
                    confidence = Confidence.MEDIUM;
                    reason = "唯一候选结果一致，但仍有候选生成或校验失败";
                }
            } else if (uniqueCandidateCount == 1
                    && executions.size() == 1
                    && pool.duplicateAstCount() > 0) {

                /*
                    重复 AST 仍不增加独立结果票，
                    但可以作为“生成稳定性”证据解释置信度。
                 */
                if (pool.generationFailureCount() == 0
                        && pool.generationStability()
                        >= generationStabilityHigh) {
                    confidence = Confidence.HIGH;
                    reason = "多数原始样本稳定生成同一 AST，且该唯一候选执行成功";
                } else if (pool.generationStability()
                        >= generationStabilityMedium) {
                    confidence = Confidence.MEDIUM;
                    reason = "原始样本对同一 AST 较稳定，但存在部分生成失败";
                } else {
                    confidence = Confidence.LOW;
                    reason = "重复 AST 比例不高，独立结果证据有限";
                }
            } else if (executions.size() >= 2) {
                confidence = Confidence.MEDIUM;
                reason = "成功执行的唯一候选结果一致，但部分候选执行失败";
            } else {
                confidence = Confidence.LOW;
                reason = "只有一个独立候选执行成功，无法形成结果共识";
            }

            return createSelection(
                    best,
                    confidence,
                    reason,
                    pool,
                    executions.size()
            );
        }

        // 多结果簇：重复 AST 不会增加票数，仍以独立候选执行结果为准
        int requiredSupport = Math.max(
                clamp(minConsensus, 2, uniqueCandidateCount),
                (int) Math.ceil(executions.size() * minConsensueRatio)
        );

        if (top.executions().size() < requiredSupport
                || top.executions().size() <= second.executions().size()) {
            log.warn(
                    "候选结果无明确共识：top={}，second={}，executions={}",
                    top.executions().size(),
                    second.executions().size(),
                    executions.size()
            );
            return null;
        }

        ExecutionResult best = chooseShortestFastest(top.executions());

        double resultCoverage =
                (double) top.executions().size() / executions.size();

        double executionCoverage =
                (double) executions.size() / uniqueCandidateCount;

        boolean fullyCovered =
                pool.generationFailureCount() == 0
                        && Math.abs(executionCoverage - 1.0) < 1.0e-9;

        // 多结果簇中只有结果覆盖和执行覆盖都较高时才标为高
        Confidence confidence =
                fullyCovered && resultCoverage >= 0.8
                        ? Confidence.HIGH
                        : Confidence.MEDIUM;

        String reason =
                "最高结果簇 " + top.executions().size()
                        + " 票，次高结果簇 " + second.executions().size()
                        + " 票；结果覆盖率 "
                        + Math.round(resultCoverage * 10_000.0) / 100.0
                        + "%";

        return createSelection(
                best,
                confidence,
                reason,
                pool,
                executions.size()
        );
    }

    /*
        新增：统一构造 Selection，避免置信度证据字段在多个分支中重复拼装。
     */
    private Selection createSelection(
            ExecutionResult execution,
            Confidence confidence,
            String reason,
            CandidatePool pool,
            int successfulExecutions
    ) {
        return new Selection(
                execution,
                confidence,
                reason,
                pool.requestedCount(),
                pool.parsedCount(),
                pool.uniqueCandidates().size(),
                pool.duplicateAstCount(),
                successfulExecutions,
                pool.dominantAstSamples(),
                pool.generationStability()
        );
    }

    /*
        兼容入口：保留原测试使用的 int 参数版本。
        生产流程使用上面的 CandidatePool 版本，能够识别重复 AST。
     */
    Selection selectByConsensus(
            List<ExecutionResult> executions,
            int generatedCandidateCount
    ) {
        int safeCount = Math.max(1, generatedCandidateCount);
        List<SafeCandidate> legacyCandidates = new ArrayList<>();
        Map<String, Integer> legacySamples = new LinkedHashMap<>();

        for (int index = 0; index < safeCount; index++) {
            String fingerprint = "LEGACY_AST_" + index;
            legacyCandidates.add(new SafeCandidate(
                    "SELECT " + index,
                    "SELECT " + index,
                    fingerprint
            ));
            legacySamples.put(fingerprint, 1);
        }

        CandidatePool compatibilityPool = new CandidatePool(
                safeCount,
                safeCount,
                0,
                legacyCandidates,
                legacySamples
        );

        return selectByConsensus(executions, compatibilityPool);
    }

    // 簇排序规则
    private int compareClusters(ResultCluster left, ResultCluster right) {

        // 第一优先级 票数降序
        int bySupport = Integer.compare(
                right.executions().size(),
                left.executions().size()
        );
        if (bySupport != 0) {
            return bySupport;
        }

        // 第二优先级 平均耗时升序
        int byTime = Long.compare(
                averageElapsed(left.executions()),
                averageElapsed(right.executions())
        );
        if (byTime != 0) {
            return byTime;
        }

        // 第三优先级 指纹字典序升序  打破完全平票的确定性。保证排序结果完全确定
        return left.fingerprint().compareTo(right.fingerprint());
    }

    // 求平均耗时
    private long averageElapsed(List<ExecutionResult> executions) {
        return executions.stream()
                .mapToLong(ExecutionResult::elapsedMs)
                .sum()
                / Math.max(1, executions.size());
    }

    // 簇内选代表
    private ExecutionResult chooseShortestFastest(List<ExecutionResult> executions) {
        return executions.stream()
                .min((left, right) -> {
                    // 第一优先级 SQL长度 短者优先
                    int byLength = Integer.compare(
                            left.candidate().sql().length(),
                            right.candidate().sql().length()
                    );
                    if (byLength != 0) {
                        return byLength;
                    }

                    // 第二优先级 耗时 快者优先
                    int byTime = Long.compare(
                            left.elapsedMs(),
                            right.elapsedMs()
                    );
                    if (byTime != 0) {
                        return byTime;
                    }

                    // 第三优先级 AST指纹字典序 打破平局，保证完全确定序
                    return left.candidate().astFingerprint().compareTo(right.fingerprint());
                })
                .orElseThrow();
    }


    /*
        实现主SQL处理流程
     */
    private ChatResponse processSQLQuery(
            String question,                        //用户自然语言问题
            Long dataSourceConfigId,                //目标数据源ID
            KnowledgeBaseResult knowledgeResult,    //知识库检索结果（含context）
            ChatResponse response                   //复用的响应对象，传入并原地修改的对象
    ) {
        long userId = StpUtil.getLoginIdAsLong(); //去当前用登陆用户的ID
        DataSourceConfig config = dataSourceConfigRepository
                .findByIdAndUserId(dataSourceConfigId,userId) // 同时按ID和当前用户过滤
                .orElseThrow(() -> new ForbiddenException(
                        ErrorCode.DATA_SOURCE_ACCESS_DENIED,
                        "数据源不存在或无权使用"
                ));

        // 加载schema与判断顺序敏感性
        String schemaText = loadSchema(config); // 读取目标库的表列结构文本，作为生成SQL的上下文
        boolean orderSensitive = requiresOrder(question); // 判断问题是否含排序语句，决定结果指纹是否对排序敏感

        // 生成候选SQL；返回值包含原始样本、重复 AST 和生成失败统计
        CandidatePool candidatePool = generateCandidates(
                question,
                config,
                schemaText,
                knowledgeResult.context()
        );

        // AST 去重后真正需要执行的候选
        List<SafeCandidate> candidates = candidatePool.uniqueCandidates();

        // 返回已通过安全校验、AST互异的候选列表
        if(candidates.isEmpty()) {
            response.setSqlExecutionSuccess(false);  // 一条候选没有 直接返回失败
            response.setSqlErrorMessage("没有生成通过只读安全校验的 SQL");
            response.setErrorCode(ErrorCode.NO_SAFE_CANDIDATE.name());
            // 新增：说明原始生成、解析成功和失败数量，避免所有失败场景都只给同一句提示
            response.setReply(
                    "候选 SQL 均未通过安全校验。" +
                            "请求生成 " + candidatePool.requestedCount() +
                            " 个，解析成功 " + candidatePool.parsedCount() +
                            " 个，失败 " + candidatePool.generationFailureCount() +
                            " 个。"
            );
            return response;
        }

        // 逐个执行候选（隔离失败）
        List<ExecutionResult> executions = new ArrayList<>();  // 单条失败不影响其他条：成功进 executions
        List<CandidateFailure> failures = new ArrayList<>();    // 失败进 failures

        for (SafeCandidate candidate : candidates) {
            try {
                ExecutionResult execution = executeReadOnly (
                        config,
                        candidate,
                        orderSensitive
                );
                executions.add(execution);

                log.info(
                        "候选执行成功：ast={}，rows={}, elapsedMs={}",
                        candidate.astFingerprint(), //日志用 astFingerprint 而非 SQL 全文,避免敏感 SQL 进日志
                        execution.rows().size(),
                        execution.elapsedMs()
                );
            } catch (Exception e) {
                failures.add(new CandidateFailure(candidate, safeMessage(e)));
                log.warn(
                        "候选执行失败：ast={}, error={}",
                        candidate.astFingerprint(),
                        safeMessage(e)  //错误用 safeMessage 脱敏
                );
            }
        }

        //全部执行失败，进入修复流程
        if (executions.isEmpty()) {
            return repairAndExecute(
                    question,
                    config,
                    schemaText,
                    knowledgeResult,
                    candidates,
                    failures,
                    response
            );
        }

        // 按共识选择；传入 CandidatePool，使选择器同时获得重复 AST 与生成失败信息
        Selection selection = selectByConsensus(
                executions,
                candidatePool
        );

        if (selection == null) {
            response.setSqlExecutionSuccess(false);
            response.setSqlErrorMessage("多个只读候选返回了相互冲突的结果");
            response.setErrorCode(ErrorCode.NO_RESULT_CONSENSUS.name());
            response.setReply(
                    """
                            我执行了多个候选查询，但它们返回的结果不一致，
                                        暂时无法确定哪一种查询口径才是你要的。

                                        请补充以下信息之一：
                                        - 时间范围；
                                        - 统计指标；
                                        - 分组维度；
                                        - 是否需要排序及排序字段。
                    """
            );
            return response;
        }

        // 组装成功响应
        ExecutionResult selected = selection.execution();

        response.setSqlGenerated(selected.candidate().sql());
        response.setSqlResults(selected.rows());
        response.setSqlExecutionSuccess(true);

        // 新增：记录置信度背后的完整候选证据，便于从后端日志排查低/高置信度原因
        log.info(
                "共识选择完成：confidence={}，requestedSamples={}，" +
                        "parsedSamples={}，uniqueCandidates={}，" +
                        "duplicateSamples={}，successfulExecutions={}，" +
                        "generationStability={}%，reason={}",
                selection.confidence(),
                selection.requestedSamples(),
                selection.parsedSamples(),
                selection.uniqueCandidates(),
                selection.duplicateSamples(),
                selection.successfulExecutions(),
                Math.round(selection.generationStability() * 10_000.0) / 100.0,
                selection.reason()
        );

        // 基于 SQL、执行结果、知识上下文、选择置信度，生成自然语言回复
        response.setReply(generateReply(
                selected.candidate().sql(),
                selected,
                knowledgeResult.context(),
                selection
        ));

        return response;
    }


    /*
        实现schema读取
        无法读取 schema 就停止，而不是把“无法获取表结构”发给模型猜测。
     */
    private String loadSchema(DataSourceConfig config) {
        DataSource dataSource =
                dynamicDataSourceManager.getDataSource(config);

        int schemaLimit = clamp(maxSchemaChars, 2_000, 100_000);
        StringBuilder schema = new StringBuilder();

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();

            String catalog = null;
            String schemaPattern = null;

            try {
                catalog = connection.getCatalog();
            } catch (SQLException ignored) {
            }

            try {
                schemaPattern = connection.getSchema();
            } catch (SQLException ignored) {
            }

            int tableCount = 0;

            try (ResultSet tables = metaData.getTables(
                    catalog,
                    schemaPattern,
                    "%",
                    new String[]{"TABLE", "VIEW"}
            )) {
                while (tables.next() && schema.length() < schemaLimit) {
                    tableCount++;

                    String tableName = tables.getString("TABLE_NAME");
                    String tableType = tables.getString("TABLE_TYPE");

                    schema.append("表: ").append(tableName)
                            .append("（").append(tableType).append("）\n");

                    try (ResultSet columns = metaData.getColumns(
                            catalog,
                            schemaPattern,
                            tableName,
                            "%"
                    )) {
                        while (columns.next()
                                && schema.length() < schemaLimit) {
                            schema.append("  - ")
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

                    schema.append("\n");
                }
            }

            if (tableCount == 0) {
                throw new SqlValidationException(
                        "无法从数据源读取表结构，已停止生成 SQL"
                );
            }

            if (schema.length() > schemaLimit) {
                schema.setLength(schemaLimit);
                schema.append("\n... schema 已截断");
            }

            return schema.toString();

        } catch (SQLException e) {
            throw new SqlValidationException(
                    "读取数据库结构失败: " + safeMessage(e),
                    e
            );
        }
    }


    /*
        所有候选都失败时执行反馈修正
     */
    private ChatResponse repairAndExecute(
            String question,
            DataSourceConfig config,
            String schemaText,
            KnowledgeBaseResult knowledgeResult,
            List<SafeCandidate> candidates,
            List<CandidateFailure> failures,
            ChatResponse response
    ) {
        if (candidates.isEmpty()) {
            response.setSqlExecutionSuccess(false);
            response.setSqlErrorMessage("没有可修正的只读 SQL");
            response.setErrorCode(ErrorCode.NO_SAFE_CANDIDATE.name());
            response.setReply("当前没有可安全执行的候选 SQL。");
            return response;
        }

        String currentSql = candidates.get(0).sql();
        String lastError = failures.isEmpty()
                ? "所有候选执行失败"
                : failures.get(0).error();

        int attempts = clamp(repairTimes, 0, 5);

        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                String fixedRaw = deepSeekService.fixSQLWithFeedback(
                        question,
                        config.getDbType(),
                        schemaText,
                        currentSql,
                        null,
                        lastError,
                        knowledgeResult.context()
                );

                SafeCandidate fixed = parseAndValidate(fixedRaw);
                currentSql = fixed.sql();

                ExecutionResult execution = executeReadOnly(
                        config,
                        fixed,
                        requiresOrder(question)
                );

                // 新增：修复阶段同样要填充候选证据字段；只有一次修复成功，因此仍为低置信度
                Selection selection = new Selection(
                        execution,
                        Confidence.LOW,
                        "该 SQL 来自失败后的反馈修正",
                        1,
                        1,
                        1,
                        0,
                        1,
                        1,
                        0.0
                );

                response.setSqlGenerated(fixed.sql());
                response.setSqlResults(execution.rows());
                response.setSqlExecutionSuccess(true);
                response.setReply(generateReply(
                        fixed.sql(),
                        execution,
                        knowledgeResult.context(),
                        selection
                ));
                return response;

            } catch (Exception e) {
                lastError = safeMessage(e);
                log.warn(
                        "SQL 反馈修正失败: attempt={}, error={}",
                        attempt + 1,
                        lastError
                );
            }
        }

        response.setSqlGenerated(currentSql);
        response.setSqlExecutionSuccess(false);
        response.setSqlErrorMessage(lastError);
        response.setErrorCode(ErrorCode.QUERY_EXECUTION_FAILED.name());
        response.setReply("""
            多次生成和修正后仍没有 SQL 能安全执行。

            最后一次 SQL：
            ```sql
            %s
            ```

            错误：
            %s
            """.formatted(currentSql, lastError));

        return response;
    }


    /*
        生成最终回复
     */
    private String generateReply(
            String sql,
            ExecutionResult execution,
            String knowledgeContext,
            Selection selection
    ) {
        StringBuilder reply = new StringBuilder();

        if (knowledgeContext != null && !knowledgeContext.isBlank()) {
            reply.append("📚 **知识库参考已参与 SQL 生成**\n\n");
        }

        reply.append("✅ **SQL 执行成功**\n\n");

        /*
            新增：置信度不再只给一个“高/中/低”标签，
            同时展示原始样本、重复 AST、唯一候选和执行覆盖情况。
         */
        reply.append("**置信度：** ")
                .append(confidenceLabel(selection.confidence()))
                .append("（")
                .append(selection.reason())
                .append("）\n\n");

        reply.append("**候选证据：** 请求 ")
                .append(selection.requestedSamples())
                .append(" 个，解析成功 ")
                .append(selection.parsedSamples())
                .append(" 个，AST 去重后 ")
                .append(selection.uniqueCandidates())
                .append(" 个，重复 AST ")
                .append(selection.duplicateSamples())
                .append(" 个，执行成功 ")
                .append(selection.successfulExecutions())
                .append(" 个。\n\n");

        reply.append("**生成稳定性：** 主导 AST 出现 ")
                .append(selection.dominantAstSamples())
                .append(" / ")
                .append(selection.requestedSamples())
                .append(" 次，稳定性 ")
                .append(
                        Math.round(
                                selection.generationStability() * 1_000.0
                        ) / 10.0
                )
                .append("%\n\n");

        // 新增：明确告诉用户重复 AST 不会作为独立结果票，避免误解为“重复五次就是五票”
        if (selection.duplicateSamples() > 0) {
            reply.append(
                    "> 重复 AST 只用于衡量生成稳定性，" +
                            "不会作为多个独立结果票参与共识。\n\n"
            );
        }

        reply.append("**SQL：**\n```sql\n")
                .append(sql)
                .append("\n```\n\n");

        if (execution.rows().isEmpty()) {
            reply.append("**查询结果：** 没有找到匹配的数据。\n");
        } else {
            reply.append("**查询结果：** 返回 ")
                    .append(execution.rows().size())
                    .append(" 条记录");

            if (execution.truncated()) {
                reply.append("，结果已达到展示上限");
            }

            reply.append("\n\n");

            int displayCount = Math.min(execution.rows().size(), 10);

            for (int index = 0; index < displayCount; index++) {
                reply.append("**记录 ").append(index + 1).append("：**\n");

                for (Map.Entry<String, Object> entry
                        : execution.rows().get(index).entrySet()) {
                    reply.append("- ")
                            .append(entry.getKey())
                            .append(": ")
                            .append(entry.getValue())
                            .append("\n");
                }

                reply.append("\n");
            }

            if (execution.rows().size() > displayCount) {
                reply.append("... 还有 ")
                        .append(execution.rows().size() - displayCount)
                        .append(" 条记录\n");
            }
        }

        return reply.toString();
    }

    private String confidenceLabel(Confidence confidence) {
        return switch (confidence) {
            case HIGH -> "高";
            case MEDIUM -> "中";
            case LOW -> "低";
        };
    }


    /*
        以下是补充工具和方法
    */

    //安全的SQL候选
    record SafeCandidate(
            String sql,
            String normalizedAst,
            String astFingerprint
    ) {
    }

    //自定义异常类 SqlValidationException SQL校验异常
    private static class SqlValidationException extends ValidationException {

        //构造方法一：接收一个错误消息字符串，把消息存起来（通过 getMessage() 可以取出）
        public SqlValidationException(String message) {
            super(ErrorCode.SQL_VALIDATION_FAILED, message);
        }

        //构造方法二：接收错误消息 + 原始异常对象（Throwable cause）
        public SqlValidationException(String message, Throwable cause) {
            super(
                    ErrorCode.SQL_VALIDATION_FAILED,
                    message,
                    null,
                    cause
            );
        }
    }

    private String safeMessage(Throwable throwable) {
        if (throwable == null) {
            return "未知错误";
        }

        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
    }


    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    value.getBytes(StandardCharsets.UTF_8)
            );
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    record ExecutionResult(
            SafeCandidate candidate,          // 执行的候选 SQL（含 AST 指纹）
            List<Map<String, Object>> rows,   // 结果行（每行是 列名->值 的有序 Map）
            boolean truncated,                // 是否因行数上限被截断
            long elapsedMs,                   // 执行耗时（毫秒）
            String fingerprint                // 结果指纹，用于等价性比较
    ) {

    }

    private static class CandidateExecutionException
            extends QueryExecutionException {
        public CandidateExecutionException(String message, Throwable cause) {
            super(ErrorCode.QUERY_EXECUTION_FAILED, message, cause);
        }
    }

    // 从多个候选字符串中返回第一个非null且非空白的值，全空则返回兜底“column”
    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "column";
    }

    // 解决结果集中列名重复的问题
    private String uniqueColumnName(
            Map<String, Object> currentRow,
            String preferred
    ) {
        String name = preferred;
        int suffix = 2;

        while (currentRow.containsKey(name)) {
            name = preferred + "_" + suffix++;
        }

        return name;
    }

    // 把JDBC ResultSet.getObject() 返回的任意类型值，转换成安全、可序列化、可控大小的对象
    private Object normalizeJdbcValue(Object value, int cellLimit)
            throws SQLException {
        if (value == null  // 直接放行的类型
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof String) {
            return value instanceof String text
                    ? truncate(text, cellLimit)
                    : value;
        }

        /*
            二进制类 → 只留元信息 byte[] Blob 不返回内容，只返回占位符
            二进制数据无法安全的放进JSON/Map展示，且可能极大，上层只关心有这么一大块二进制
         */
        if (value instanceof byte[] bytes) {
            return "[binary " + bytes.length + " bytes]";
        }
        if (value instanceof Blob blob) {
            return "[blob " + blob.length() + " bytes]";
        }

        // 读前 cellLimit个字符
        if (value instanceof Clob clob) {
            long length = clob.length();
            int readLength = (int) Math.min(length, cellLimit);
            String text = clob.getSubString(1, readLength);
            return length > readLength ? text + "…" : text;
        }

        // 转字符串再截断
        if (value instanceof java.sql.Array array) {
            Object content = array.getArray();
            return truncate(String.valueOf(content), cellLimit);
        }

        if (value instanceof java.sql.Date
                || value instanceof java.sql.Time
                || value instanceof java.sql.Timestamp
                || value instanceof TemporalAccessor
                || value instanceof UUID) {
            return value.toString();
        }

        if (value instanceof java.util.Date date) {
            return date.toInstant().toString();
        }

        return truncate(String.valueOf(value), cellLimit);
    }

    // 字符串超过上限就截断，并加省略号
    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "…";
    }

    // 结果簇
    private record ResultCluster(
            String fingerprint,
            List<ExecutionResult> executions
    ) {
    }

    //结果簇选择
    record Selection(
            ExecutionResult execution,
            Confidence confidence,
            String reason,
            int requestedSamples,
            int parsedSamples,
            int uniqueCandidates,
            int duplicateSamples,
            int successfulExecutions,
            int dominantAstSamples,
            double generationStability
    ){
    }

    // 候选执行失败
    private record CandidateFailure(
            SafeCandidate candidate,
            String error
    ) {
    }

    // 置信度
    enum Confidence {
        HIGH,
        MEDIUM,
        LOW
    }

    // 候选池
    record CandidatePool(
            int requestedCount,
            int parsedCount,
            int generationFailureCount,
            List<SafeCandidate> uniqueCandidates,
            Map<String, Integer> astSamples
    ) {

        CandidatePool {
            uniqueCandidates = uniqueCandidates == null
                    ? List.of()
                    : List.copyOf(uniqueCandidates);

            astSamples = astSamples == null
                    ? Map.of()
                    : Map.copyOf(astSamples);
        }

        int duplicateAstCount() {
            return Math.max(0, parsedCount - uniqueCandidates.size());
        }

        int dominantAstSamples() {
            return astSamples.values().stream()
                    .mapToInt(Integer::intValue)
                    .max()
                    .orElse(0);
        }

        double generationStability() {
            if (requestedCount <= 0) {
                return 0;
            }

            return (double) dominantAstSamples() / requestedCount;
        }
    }
}
