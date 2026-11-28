package com.example.sqlagent.attachment.document;

import java.util.Map;

public record DocumentManifest(
        DocumentFormat format,
        String summary,
        Map<String, Object> statistics
) {
    public DocumentManifest {
        statistics = statistics == null ? Map.of() : Map.copyOf(statistics);
    }
}
