package com.example.sqlagent.attachment.document;

import java.util.ArrayList;
import java.util.List;

final class DocumentParserSupport {
    static final String PARSER_VERSION = "document-parser-2026.11.1";

    private DocumentParserSupport() {
    }

    static String clean(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace('\u0000', ' ')
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    static List<String> chunks(String text, int maxChars) {
        String cleaned = clean(text);
        if (cleaned.isBlank()) {
            return List.of();
        }

        int limit = Math.max(500, maxChars);
        if (cleaned.length() <= limit) {
            return List.of(cleaned);
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < cleaned.length() && chunks.size() < 100) {
            int end = Math.min(cleaned.length(), start + limit);
            if (end < cleaned.length()) {
                int boundary = Math.max(
                        cleaned.lastIndexOf('\n', end),
                        cleaned.lastIndexOf('。', end)
                );
                if (boundary > start + limit / 2) {
                    end = boundary + 1;
                }
            }
            chunks.add(cleaned.substring(start, end).trim());
            start = end;
        }
        return chunks;
    }

    static String truncate(String value, int maxChars) {
        String cleaned = clean(value);
        if (cleaned.length() <= maxChars) {
            return cleaned;
        }
        return cleaned.substring(0, Math.max(1, maxChars)) + "\n[内容已截断]";
    }

    static String singleLine(String value, int maxChars) {
        String cleaned = clean(value).replace('\n', ' ');
        if (cleaned.length() <= maxChars) {
            return cleaned;
        }
        return cleaned.substring(0, Math.max(1, maxChars)) + "...";
    }
}
