package com.example.sqlagent.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class SQLAgentService2ConsensusTest {

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
    void returnsNullWhenThereAreNoExecutions() {
        SQLAgentService2.Selection selection =
                service.selectByConsensus(List.of(), 3);

        assertNull(selection);
    }

    @Test
    void selectsTwoVotesAgainstOneDifferentResult() {
        SQLAgentService2.ExecutionResult first =
                execution("SELECT id FROM users", "RESULT_A", 20);

        SQLAgentService2.ExecutionResult second =
                execution("SELECT u.id FROM users u", "RESULT_A", 30);

        SQLAgentService2.ExecutionResult third =
                execution("SELECT name FROM users", "RESULT_B", 10);

        SQLAgentService2.Selection selection =
                service.selectByConsensus(
                        List.of(first, second, third),
                        3
                );

        assertNotNull(selection);
        assertEquals("RESULT_A", selection.execution().fingerprint());
        assertEquals(
                SQLAgentService2.Confidence.MEDIUM,
                selection.confidence()
        );
    }

    @Test
    void returnsNullWhenThreeResultsEachHaveOneVote() {
        SQLAgentService2.Selection selection =
                service.selectByConsensus(
                        List.of(
                                execution("SELECT 1", "RESULT_A", 10),
                                execution("SELECT 2", "RESULT_B", 10),
                                execution("SELECT 3", "RESULT_C", 10)
                        ),
                        3
                );

        assertNull(selection);
    }

    @Test
    void returnsNullWhenTwoResultsHaveEqualVotes() {
        SQLAgentService2.Selection selection =
                service.selectByConsensus(
                        List.of(
                                execution("SELECT 1", "RESULT_A", 10),
                                execution("SELECT 2", "RESULT_A", 10),
                                execution("SELECT 3", "RESULT_B", 10),
                                execution("SELECT 4", "RESULT_B", 10)
                        ),
                        4
                );

        assertNull(selection);
    }

    @Test
    void allCandidatesWithSameResultHaveHighConfidence() {
        SQLAgentService2.Selection selection =
                service.selectByConsensus(
                        List.of(
                                execution("SELECT id FROM users", "RESULT_A", 10),
                                execution("SELECT u.id FROM users u", "RESULT_A", 20),
                                execution("SELECT id FROM users ORDER BY id", "RESULT_A", 30)
                        ),
                        3
                );

        assertNotNull(selection);
        assertEquals("RESULT_A", selection.execution().fingerprint());
        assertEquals(
                SQLAgentService2.Confidence.HIGH,
                selection.confidence()
        );
    }

    @Test
    void sameResultWithPartialExecutionFailureHasMediumConfidence() {
        SQLAgentService2.Selection selection =
                service.selectByConsensus(
                        List.of(
                                execution("SELECT id FROM users", "RESULT_A", 10),
                                execution("SELECT u.id FROM users u", "RESULT_A", 20)
                        ),
                        3
                );

        assertNotNull(selection);
        assertEquals(
                SQLAgentService2.Confidence.MEDIUM,
                selection.confidence()
        );
    }

    @Test
    void onlyOneSuccessfulCandidateHasLowConfidence() {
        SQLAgentService2.Selection selection =
                service.selectByConsensus(
                        List.of(
                                execution("SELECT id FROM users", "RESULT_A", 10)
                        ),
                        3
                );

        assertNotNull(selection);
        assertEquals(
                SQLAgentService2.Confidence.LOW,
                selection.confidence()
        );
    }

    @Test
    void fourVotesOutOfFiveHaveHighConfidence() {
        SQLAgentService2.Selection selection =
                service.selectByConsensus(
                        List.of(
                                execution("SELECT 1", "RESULT_A", 10),
                                execution("SELECT 2", "RESULT_A", 10),
                                execution("SELECT 3", "RESULT_A", 10),
                                execution("SELECT 4", "RESULT_A", 10),
                                execution("SELECT 5", "RESULT_B", 10)
                        ),
                        5
                );

        assertNotNull(selection);
        assertEquals(
                SQLAgentService2.Confidence.HIGH,
                selection.confidence()
        );
    }

    @Test
    void selectsShortestSqlInsideWinningCluster() {
        SQLAgentService2.ExecutionResult longSql =
                execution(
                        "SELECT id AS user_identifier FROM users",
                        "RESULT_A",
                        1
                );

        SQLAgentService2.ExecutionResult shortSql =
                execution(
                        "SELECT id FROM users",
                        "RESULT_A",
                        100
                );

        SQLAgentService2.ExecutionResult differentResult =
                execution(
                        "SELECT name FROM users",
                        "RESULT_B",
                        10
                );

        SQLAgentService2.Selection selection =
                service.selectByConsensus(
                        List.of(longSql, shortSql, differentResult),
                        3
                );

        assertNotNull(selection);
        assertEquals(
                "SELECT id FROM users",
                selection.execution().candidate().sql()
        );
    }

    @Test
    void selectsFastestSqlWhenLengthsAreEqual() {
        SQLAgentService2.ExecutionResult slow =
                execution("SELECT id FROM users", "RESULT_A", 50);

        SQLAgentService2.ExecutionResult fast =
                execution("SELECT id FROM users", "RESULT_A", 5);

        SQLAgentService2.ExecutionResult differentResult =
                execution("SELECT name FROM users", "RESULT_B", 10);

        SQLAgentService2.Selection selection =
                service.selectByConsensus(
                        List.of(slow, fast, differentResult),
                        3
                );

        assertNotNull(selection);
        assertEquals(
                5L,
                selection.execution().elapsedMs()
        );
    }

    private SQLAgentService2.ExecutionResult execution(
            String sql,
            String fingerprint,
            long elapsedMs
    ) {
        SQLAgentService2.SafeCandidate candidate =
                new SQLAgentService2.SafeCandidate(
                        sql,
                        sql,
                        "AST_" + Math.abs(sql.hashCode())
                );

        return new SQLAgentService2.ExecutionResult(
                candidate,
                List.of(Map.of("id", 1)),
                false,
                elapsedMs,
                fingerprint
        );
    }
}