package com.example.sqlagent.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Blob;
import java.sql.Clob;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class ChatResultCodec {

    private static final Logger log = LoggerFactory.getLogger(ChatResultCodec.class);
    private static final int MAX_LEGACY_RESULT_TEXT = 20_000;
    private static final TypeReference<List<Map<String, Object>>> RESULT_LIST_TYPE =
            new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public ChatResultCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String serialize(List<Map<String, Object>> results) {
        if (results == null) return null;

        try {
            return objectMapper.writeValueAsString(sanitizeRows(results));
        } catch (JsonProcessingException e) {
            log.warn("SQL 结果序列化失败，历史中将不保存结构化结果: {}", e.getMessage());
            return null;
        }
    }

    public DecodedSqlResults deserialize(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return new DecodedSqlResults(null, null, null);
        }

        try {
            List<Map<String, Object>> rows = objectMapper.readValue(rawValue, RESULT_LIST_TYPE);
            return new DecodedSqlResults(rows, null, "json");
        } catch (JsonProcessingException e) {
            return new DecodedSqlResults(null, truncate(rawValue), "legacy_text");
        }
    }

    private List<Map<String, Object>> sanitizeRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> sanitized = new ArrayList<>(rows.size());

        for (Map<String, Object> row : rows) {
            if (row == null) continue;

            Map<String, Object> sanitizedRow = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                sanitizedRow.put(entry.getKey(), sanitizeValue(entry.getValue()));
            }
            sanitized.add(sanitizedRow);
        }

        return sanitized;
    }

    private Object sanitizeValue(Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean) {
            return value;
        }

        if (value instanceof byte[] bytes) {
            return "[binary " + bytes.length + " bytes]";
        }

        if (value instanceof Blob blob) {
            try {
                return "[blob " + blob.length() + " bytes]";
            } catch (Exception e) {
                return "[blob]";
            }
        }

        if (value instanceof Clob clob) {
            try {
                long length = clob.length();
                int readLength = (int) Math.min(length, 4096);
                String text = clob.getSubString(1, readLength);
                return length > readLength ? text + "…" : text;
            } catch (Exception e) {
                return "[clob]";
            }
        }

        if (value instanceof java.sql.Array sqlArray) {
            try {
                return sanitizeValue(sqlArray.getArray());
            } catch (Exception e) {
                return "[array]";
            }
        }

        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                normalized.put(String.valueOf(entry.getKey()), sanitizeValue(entry.getValue()));
            }
            return normalized;
        }

        if (value instanceof Iterable<?> iterable) {
            List<Object> normalized = new ArrayList<>();
            for (Object item : iterable) {
                normalized.add(sanitizeValue(item));
            }
            return normalized;
        }

        if (value instanceof TemporalAccessor
                || value instanceof Date
                || value instanceof UUID) {
            return value.toString();
        }

        return String.valueOf(value);
    }

    private String truncate(String value) {
        if (value.length() <= MAX_LEGACY_RESULT_TEXT) return value;
        return value.substring(0, MAX_LEGACY_RESULT_TEXT) + "…";
    }

    public record DecodedSqlResults(
            List<Map<String, Object>> rows,
            String rawText,
            String format
    ) {
    }
}
