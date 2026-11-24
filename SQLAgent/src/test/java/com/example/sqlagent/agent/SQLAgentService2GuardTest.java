package com.example.sqlagent.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class SQLAgentService2GuardTest {

    private SQLAgentService2 service;

    @BeforeEach
    void setUp() {
        service = new SQLAgentService2(
                mock(DeepSeekService.class),
                mock(KnowledgeBaseService.class),
                mock(com.example.sqlagent.datasource.DynamicDataSourceManager.class),
                mock(com.example.sqlagent.datasource.DataSourceConfigRepository.class)
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
    }

    @Test
    void acceptsReadOnlySelect() {
        assertDoesNotThrow(
                () -> service.parseAndValidate("SELECT * FROM users")
        );
    }

    @Test
    void acceptsMysqlBackticks() {
        assertDoesNotThrow(
                () -> service.parseAndValidate(
                        "SELECT `user`.`id` FROM `user`"
                )
        );
    }

    @Test
    void rejectsDelete() {
        assertThrows(
                RuntimeException.class,
                () -> service.parseAndValidate("DELETE FROM users")
        );
    }

    @Test
    void rejectsMultipleStatements() {
        assertThrows(
                RuntimeException.class,
                () -> service.parseAndValidate(
                        "SELECT * FROM users; DELETE FROM users"
                )
        );
    }

    @ParameterizedTest(name = "[{index}] 拒绝危险 SQL: {0}")
    @ValueSource(strings = {
            "UPDATE users SET nickname = 'x'",
            "INSERT INTO users(username, password) VALUES ('x', 'y')",
            "DELETE FROM users",
            "DROP TABLE users",
            "ALTER TABLE users ADD COLUMN test_col INT",
            "TRUNCATE TABLE users",
            "CREATE TABLE temp_table(id INT)",
            "GRANT SELECT ON users TO test_user",
            "CALL dangerous_procedure()",
            "SELECT * FROM users; DELETE FROM users",
            "SELECT * FROM users INTO OUTFILE '/tmp/users.txt'",
            "SELECT * FROM users INTO DUMPFILE '/tmp/users.txt'",
            "SELECT LOAD_FILE('/etc/passwd')",
            "SELECT * FROM users FOR UPDATE",
            "SELECT * FROM users LOCK IN SHARE MODE"
    })
    void rejectsWriteOrDangerousSql(String sql) {
        assertThrows(
                RuntimeException.class,
                () -> service.parseAndValidate(sql)
        );
    }

    @Test
    void rejectsBlankSql() {
        assertThrows(
                RuntimeException.class,
                () -> service.parseAndValidate("   ")
        );
    }

    @Test
    void rejectsPlainText() {
        assertThrows(
                RuntimeException.class,
                () -> service.parseAndValidate("帮我查询所有用户")
        );
    }

    @Test
    void acceptsCountQuery() {
        assertDoesNotThrow(
                () -> service.parseAndValidate(
                        "SELECT COUNT(*) AS total FROM users"
                )
        );
    }

    @Test
    void acceptsCteSelect() {
        assertDoesNotThrow(
                () -> service.parseAndValidate(
                        "WITH active_users AS (" +
                                "SELECT * FROM users WHERE active = 1" +
                                ") SELECT * FROM active_users"
                )
        );
    }

    @Test
    void acceptsSingleTrailingSemicolon() {
        assertDoesNotThrow(
                () -> service.parseAndValidate(
                        "SELECT id FROM users;"
                )
        );
    }
}