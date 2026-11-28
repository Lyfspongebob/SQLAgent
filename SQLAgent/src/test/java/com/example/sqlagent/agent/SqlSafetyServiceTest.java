package com.example.sqlagent.agent;

import com.example.sqlagent.datasource.DynamicDataSourceManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SqlSafetyServiceTest {
    private final SqlSafetyService service = new SqlSafetyService(
            mock(DynamicDataSourceManager.class)
    );

    @Test
    void classifiesInsertWithoutTreatingItAsExecutionError() {
        SqlSafetyService.SqlAnalysis analysis = service.analyze(
                "INSERT INTO users(name) VALUES ('小飞')"
        );

        assertFalse(analysis.readOnly());
        assertFalse(analysis.executable());
        assertTrue(analysis.statementTypes().stream()
                .anyMatch(type -> type.toLowerCase().contains("insert")));
        assertTrue(analysis.reason().contains("不能由只读执行工具运行"));
    }

    @Test
    void acceptsSingleSelectForReadonlyExecution() {
        SqlSafetyService.SqlAnalysis analysis = service.analyze(
                "SELECT user_id, username FROM users"
        );

        assertTrue(analysis.readOnly());
        assertTrue(analysis.executable());
    }

    @Test
    void keepsMultipleStatementsClassifiedButNotDirectlyExecutable() {
        SqlSafetyService.SqlAnalysis analysis = service.analyze(
                "SELECT * FROM users; SELECT * FROM courses;"
        );

        assertTrue(analysis.readOnly());
        assertFalse(analysis.executable());
        assertEquals(2, analysis.statementTypes().size());
    }
}
