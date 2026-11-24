package com.example.sqlagent.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class SQLAgentService2FingerprintTest {

    private SQLAgentService2 service;

    @BeforeEach
    void setUp() {
        service = new SQLAgentService2(
                mock(DeepSeekService.class),
                mock(KnowledgeBaseService.class),
                mock(com.example.sqlagent.datasource.DynamicDataSourceManager.class),
                mock(com.example.sqlagent.datasource.DataSourceConfigRepository.class)
        );
    }

    @Test
    void unorderedRowsWithDifferentOrderHaveSameFingerprint() {
        Map<String, Object> row1 = row(
                "id", 1,
                "name", "Alice",
                "score", 90
        );

        Map<String, Object> row2 = row(
                "id", 2,
                "name", "Bob",
                "score", 80
        );

        String fingerprintA = service.resultFingerprint(
                List.of(row1, row2),
                false,
                false
        );

        String fingerprintB = service.resultFingerprint(
                List.of(row2, row1),
                false,
                false
        );

        assertEquals(fingerprintA, fingerprintB);
        assertEquals(64, fingerprintA.length());
    }

    @Test
    void orderedRowsWithDifferentOrderHaveDifferentFingerprints() {
        Map<String, Object> row1 = row(
                "id", 1,
                "name", "Alice"
        );

        Map<String, Object> row2 = row(
                "id", 2,
                "name", "Bob"
        );

        String fingerprintA = service.resultFingerprint(
                List.of(row1, row2),
                true,
                false
        );

        String fingerprintB = service.resultFingerprint(
                List.of(row2, row1),
                true,
                false
        );

        assertNotEquals(fingerprintA, fingerprintB);
    }

    @Test
    void numericallyEquivalentValuesHaveSameFingerprint() {
        Map<String, Object> integerRow = row(
                "amount", 10
        );

        Map<String, Object> decimalRow = row(
                "amount", 10.00
        );

        String integerFingerprint = service.resultFingerprint(
                List.of(integerRow),
                false,
                false
        );

        String decimalFingerprint = service.resultFingerprint(
                List.of(decimalRow),
                false,
                false
        );

        assertEquals(integerFingerprint, decimalFingerprint);
    }

    @Test
    void nullAndEmptyStringHaveDifferentFingerprints() {
        Map<String, Object> nullRow = row("value", null);
        Map<String, Object> emptyRow = row("value", "");

        String nullFingerprint = service.resultFingerprint(
                List.of(nullRow),
                false,
                false
        );

        String emptyFingerprint = service.resultFingerprint(
                List.of(emptyRow),
                false,
                false
        );

        assertNotEquals(nullFingerprint, emptyFingerprint);
    }

    @Test
    void booleanAndStringHaveDifferentFingerprints() {
        Map<String, Object> booleanRow = row("value", true);
        Map<String, Object> stringRow = row("value", "true");

        String booleanFingerprint = service.resultFingerprint(
                List.of(booleanRow),
                false,
                false
        );

        String stringFingerprint = service.resultFingerprint(
                List.of(stringRow),
                false,
                false
        );

        assertNotEquals(booleanFingerprint, stringFingerprint);
    }

    @Test
    void truncatedFlagChangesFingerprint() {
        Map<String, Object> row = row(
                "id", 1,
                "name", "Alice"
        );

        String notTruncated = service.resultFingerprint(
                List.of(row),
                false,
                false
        );

        String truncated = service.resultFingerprint(
                List.of(row),
                false,
                true
        );

        assertNotEquals(notTruncated, truncated);
    }

    @Test
    void differentRowCountsHaveDifferentFingerprints() {
        Map<String, Object> row1 = row("id", 1);
        Map<String, Object> row2 = row("id", 2);

        String oneRow = service.resultFingerprint(
                List.of(row1),
                false,
                false
        );

        String twoRows = service.resultFingerprint(
                List.of(row1, row2),
                false,
                false
        );

        assertNotEquals(oneRow, twoRows);
    }

    @Test
    void duplicateRowsAreNotCollapsed() {
        Map<String, Object> row = row(
                "id", 1,
                "name", "Alice"
        );

        String oneRow = service.resultFingerprint(
                List.of(row),
                false,
                false
        );

        String duplicatedRows = service.resultFingerprint(
                List.of(row, row),
                false,
                false
        );

        assertNotEquals(oneRow, duplicatedRows);
    }

    @Test
    void emptyResultHasValidSha256Fingerprint() {
        String fingerprint = service.resultFingerprint(
                List.of(),
                false,
                false
        );

        assertTrue(fingerprint.matches("[0-9a-f]{64}"));
    }

    private Map<String, Object> row(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("keyValues 必须成对出现");
        }

        Map<String, Object> row = new LinkedHashMap<>();

        for (int index = 0; index < keyValues.length; index += 2) {
            row.put(
                    String.valueOf(keyValues[index]),
                    keyValues[index + 1]
            );
        }

        return row;
    }
}