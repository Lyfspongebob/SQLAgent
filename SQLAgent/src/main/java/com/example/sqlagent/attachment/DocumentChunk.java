package com.example.sqlagent.attachment;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(
        name = "document_chunks",
        indexes = {
                @Index(
                        name = "idx_chunk_user_session",
                        columnList = "user_id,session_id"
                ),
                @Index(
                        name = "idx_chunk_attachment",
                        columnList = "attachment_id"
                )
        }
)
public class DocumentChunk {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "attachment_id", nullable = false)
    private Long attachmentId;

    @Column(nullable = false, length = 512)
    private String locator;

    @Column(length = 80)
    private String type;

    @Column(length = 500)
    private String title;

    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @Column(name = "char_count", nullable = false)
    private int charCount;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime = LocalDateTime.now();
}
