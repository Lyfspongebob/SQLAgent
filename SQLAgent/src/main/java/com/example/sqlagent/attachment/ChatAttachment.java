package com.example.sqlagent.attachment;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(
        name = "chat_attachments",
        indexes = {
                @Index(name = "idx_attachment_user_session", columnList = "user_id,session_id"),
                @Index(name = "idx_attachment_message", columnList = "message_id")
        }
)
public class ChatAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "message_id")
    private Long messageId;

    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    @Column(name = "storage_key", nullable = false, unique = true, length = 512)
    private String storageKey;

    @Column(name = "content_type", nullable = false, length = 120)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AttachmentKind kind;

    @Column(name = "extracted_text", columnDefinition = "LONGTEXT")
    private String extractedText;

    @Column(name = "manifest_json", columnDefinition = "LONGTEXT")
    private String manifestJson;

    @Column(name = "parsed_storage_key", length = 512)
    private String parsedStorageKey;

    @Column(name = "parser_version", length = 32)
    private String parserVersion;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime = LocalDateTime.now();
}
