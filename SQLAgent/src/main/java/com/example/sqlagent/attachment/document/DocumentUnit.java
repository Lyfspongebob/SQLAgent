package com.example.sqlagent.attachment.document;

import java.util.Map;

public record DocumentUnit(
        String locator,
        String type,
        String title,
        String text,
        Map<String, String> metadata
) {
    public DocumentUnit {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
