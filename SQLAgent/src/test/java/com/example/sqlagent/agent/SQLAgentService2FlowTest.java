package com.example.sqlagent.agent;

import cn.dev33.satoken.stp.StpUtil;

import com.example.sqlagent.datasource.DataSourceConfig;
import com.example.sqlagent.datasource.DataSourceConfigRepository;
import com.example.sqlagent.datasource.DynamicDataSourceManager;
import com.example.sqlagent.dto.ChatResponse;
import com.example.sqlagent.error.ForbiddenException;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.stubbing.OngoingStubbing;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SQLAgentService2FlowTest {

    private static final String JDBC_URL =
            "jdbc:h2:mem:sqlagent_v2_flow;" +
                    "MODE=MySQL;" +
                    "DB_CLOSE_DELAY=-1;" +
                    "DATABASE_TO_LOWER=TRUE";

    private static final long USER_ID = 1L;
    private static final long DATA_SOURCE_ID = 100L;

    private DeepSeekService deepSeekService;
    private KnowledgeBaseService knowledgeBaseService;
    private DynamicDataSourceManager dynamicDataSourceManager;
    private DataSourceConfigRepository dataSourceConfigRepository;

    private SQLAgentService2 service;
    private JdbcDataSource targetDataSource;
    private DataSourceConfig config;

    @BeforeEach
    void setUp() throws Exception {
        deepSeekService = mock(DeepSeekService.class);
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        dynamicDataSourceManager = mock(DynamicDataSourceManager.class);
        dataSourceConfigRepository =
                mock(DataSourceConfigRepository.class);

        service = new SQLAgentService2(
                deepSeekService,
                knowledgeBaseService,
                dynamicDataSourceManager,
                dataSourceConfigRepository
        );

        ReflectionTestUtils.setField(service, "candidateCount", 3);
        ReflectionTestUtils.setField(service, "temperature", 0.3);
        ReflectionTestUtils.setField(service, "minConsensus", 2);
        ReflectionTestUtils.setField(service, "minConsensueRatio", 0.6);
        ReflectionTestUtils.setField(service, "queryTimeoutSeconds", 5);
        ReflectionTestUtils.setField(service, "maxRow", 20);
        ReflectionTestUtils.setField(service, "maxCellChars", 1000);
        ReflectionTestUtils.setField(service, "maxSchemaChars", 10000);
        ReflectionTestUtils.setField(service, "repairTimes", 1);

        targetDataSource = new JdbcDataSource();
        targetDataSource.setURL(JDBC_URL);
        targetDataSource.setUser("sa");
        targetDataSource.setPassword("");

        config = new DataSourceConfig();
        config.setId(DATA_SOURCE_ID);
        config.setUserId(USER_ID);
        config.setName("H2测试数据源");
        config.setDbType("mysql");
        config.setHost("localhost");
        config.setPort(3306);
        config.setDatabaseName("sqlagent_v2_flow");
        config.setUsername("sa");
        config.setPassword("");

        when(dynamicDataSourceManager.getDataSource(any()))
                .thenReturn(targetDataSource);

        when(dataSourceConfigRepository.findByIdAndUserId(
                DATA_SOURCE_ID,
                USER_ID
        )).thenReturn(Optional.of(config));

        resetEmployeesTable(2);
    }

    @Test
    void allCandidatesWithSameResultReturnHighConfidence() {
        stubCandidates(
                "SELECT id, name FROM employees ORDER BY id",
                "SELECT e.id, e.name FROM employees e ORDER BY e.id",
                "SELECT id, name FROM employees WHERE id > 0 ORDER BY id"
        );

        ChatResponse response = processWithLoggedInUser();

        assertTrue(response.getSqlExecutionSuccess());
        assertNotNull(response.getSqlGenerated());
        assertEquals(2, response.getSqlResults().size());
        assertTrue(response.getReply().contains("高"));
    }

    @Test
    void conflictingResultsDoNotChooseFirstCandidate() {
        stubCandidates(
                "SELECT id FROM employees ORDER BY id",
                "SELECT name FROM employees ORDER BY name",
                "SELECT id + 1 AS id FROM employees ORDER BY id"
        );

        ChatResponse response = processWithLoggedInUser();

        assertFalse(response.getSqlExecutionSuccess());
        assertNull(response.getSqlGenerated());
        assertTrue(response.getReply().contains("不一致"));
    }

    @Test
    void unauthorizedDataSourceDoesNotCallDeepSeek() {
        when(dataSourceConfigRepository.findByIdAndUserId(
                DATA_SOURCE_ID,
                USER_ID
        )).thenReturn(Optional.empty());

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID);

            assertThrows(
                    ForbiddenException.class,
                    () -> service.processChat(
                            "查询员工",
                            1L,
                            false,
                            true,
                            DATA_SOURCE_ID
                    )
            );
        }

        verifyNoInteractions(deepSeekService);
    }

    @Test
    void deleteCandidateIsRejectedAndRowsRemainUnchanged()
            throws Exception {
        stubCandidates(
                "DELETE FROM employees",
                "DELETE FROM employees",
                "DELETE FROM employees"
        );

        ChatResponse response = processWithLoggedInUser();

        assertFalse(response.getSqlExecutionSuccess());
        assertEquals(2, countEmployees());
    }

    @Test
    void duplicateAstIsDeduplicatedBeforeConsensus() {
        stubCandidates(
                "SELECT id FROM employees ORDER BY id",
                "SELECT id FROM employees ORDER BY id",
                "SELECT id FROM employees ORDER BY id"
        );

        ChatResponse response = processWithLoggedInUser();

        assertTrue(response.getSqlExecutionSuccess());
        assertEquals(2, response.getSqlResults().size());
        assertTrue(response.getReply().contains("高"));
        assertTrue(response.getReply().contains("重复 AST"));
    }

    @Test
    void resultIsTruncatedAtConfiguredRowLimit() throws Exception {
        resetEmployeesTable(25);

        stubCandidates(
                "SELECT id FROM employees ORDER BY id",
                "SELECT e.id FROM employees e ORDER BY e.id",
                "SELECT id FROM employees WHERE id > 0 ORDER BY id"
        );

        ChatResponse response = processWithLoggedInUser();

        assertTrue(response.getSqlExecutionSuccess());
        assertEquals(20, response.getSqlResults().size());
        assertTrue(response.getReply().contains("达到展示上限"));
    }

    private ChatResponse processWithLoggedInUser() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdAsLong).thenReturn(USER_ID);

            ChatResponse response = service.processChat(
                    "查询所有员工",
                    1L,
                    false,
                    true,
                    DATA_SOURCE_ID
            );

            verifyNoInteractions(knowledgeBaseService);
            return response;
        }
    }

    private void stubCandidates(String... sqls) {
        OngoingStubbing<String> stubbing = when(
                deepSeekService.generateSQL(
                        anyString(),
                        eq("mysql"),
                        anyString(),
                        eq(0.3),
                        anyString()
                )
        );

        for (String sql : sqls) {
            stubbing = stubbing.thenReturn(sql);
        }
    }

    private void resetEmployeesTable(int rowCount) throws Exception {
        try (Connection connection =
                     targetDataSource.getConnection();
             Statement statement = connection.createStatement()) {

            statement.execute("DROP TABLE IF EXISTS employees");
            statement.execute("""
                    CREATE TABLE employees (
                        id INT PRIMARY KEY,
                        name VARCHAR(100) NOT NULL
                    )
                    """);
        }

        try (Connection connection =
                     targetDataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO employees(id, name) VALUES (?, ?)"
             )) {
            for (int index = 1; index <= rowCount; index++) {
                statement.setInt(1, index);
                statement.setString(2, "Employee-" + index);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private int countEmployees() throws Exception {
        try (Connection connection =
                     targetDataSource.getConnection();
             Statement statement = connection.createStatement();
             var resultSet = statement.executeQuery(
                     "SELECT COUNT(*) FROM employees"
             )) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }
}
