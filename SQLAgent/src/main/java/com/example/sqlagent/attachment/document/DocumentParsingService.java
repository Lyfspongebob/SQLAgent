package com.example.sqlagent.attachment.document;

import com.example.sqlagent.attachment.AttachmentKind;
import com.example.sqlagent.error.ErrorCode;
import com.example.sqlagent.error.ValidationException;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
public class DocumentParsingService {
    private final Map<AttachmentKind, DocumentParser> parsers;

    public DocumentParsingService(List<DocumentParser> documentParsers) {
        Map<AttachmentKind, DocumentParser> registered =
                new EnumMap<>(AttachmentKind.class);
        for (DocumentParser parser : documentParsers) {
            registered.put(parser.kind(), parser);
        }
        this.parsers = Map.copyOf(registered);
    }

    public ParsedDocument parse(
            AttachmentKind kind,
            byte[] bytes,
            String originalName
    ) {
        DocumentParser parser = parsers.get(kind);
        if (parser == null) {
            throw new ValidationException(
                    ErrorCode.ATTACHMENT_UNSUPPORTED,
                    "没有可用的文档解析器"
            );
        }
        return parser.parse(bytes, originalName);
    }
}
