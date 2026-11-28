package com.example.sqlagent.attachment.document;

import com.example.sqlagent.attachment.AttachmentKind;

public interface DocumentParser {
    AttachmentKind kind();

    ParsedDocument parse(byte[] bytes, String originalName);
}
