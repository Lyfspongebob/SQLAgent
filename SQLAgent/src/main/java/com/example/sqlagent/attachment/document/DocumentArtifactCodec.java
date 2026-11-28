package com.example.sqlagent.attachment.document;

import com.example.sqlagent.error.ErrorCode;
import com.example.sqlagent.error.ValidationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class DocumentArtifactCodec {
    private final ObjectMapper objectMapper;

    public DocumentArtifactCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public byte[] write(ParsedDocument document) {
        try {
            return objectMapper.writeValueAsBytes(document);
        } catch (Exception exception) {
            throw new ValidationException(
                    ErrorCode.ATTACHMENT_STORAGE_FAILED,
                    "保存文档解析结果失败",
                    null,
                    exception
            );
        }
    }

    public ParsedDocument read(byte[] content) {
        try {
            return objectMapper.readValue(content, ParsedDocument.class);
        } catch (Exception exception) {
            throw new ValidationException(
                    ErrorCode.ATTACHMENT_STORAGE_FAILED,
                    "读取文档解析结果失败",
                    null,
                    exception
            );
        }
    }

    public String writeManifest(ParsedDocument document) {
        try {
            return objectMapper.writeValueAsString(document.manifest());
        } catch (Exception exception) {
            throw new ValidationException(
                    ErrorCode.ATTACHMENT_STORAGE_FAILED,
                    "保存文档清单失败",
                    null,
                    exception
            );
        }
    }
}
