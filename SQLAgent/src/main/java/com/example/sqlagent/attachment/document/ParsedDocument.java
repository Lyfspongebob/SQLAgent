package com.example.sqlagent.attachment.document;

import java.util.List;

public record ParsedDocument(
        String parserVersion,
        DocumentManifest manifest,
        List<DocumentUnit> units
) {
    public ParsedDocument {
        units = units == null ? List.of() : List.copyOf(units);
    }
}
