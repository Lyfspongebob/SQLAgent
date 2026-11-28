package com.example.sqlagent.attachment;

public enum AttachmentKind {
    IMAGE,
    TEXT,
    PDF,
    WORD,
    EXCEL;

    public boolean isDocument() {
        return this == PDF || this == WORD || this == EXCEL;
    }
}
