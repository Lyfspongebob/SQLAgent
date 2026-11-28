package com.example.sqlagent.attachment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentChunkRepository
        extends JpaRepository<DocumentChunk, Long> {

    List<DocumentChunk> findByUserIdAndSessionIdOrderByAttachmentIdAscIdAsc(
            Long userId,
            Long sessionId
    );

    List<DocumentChunk> findByUserIdAndAttachmentIdOrderByIdAsc(
            Long userId,
            Long attachmentId
    );

    List<DocumentChunk> findByUserIdAndSessionIdAndIdIn(
            Long userId,
            Long sessionId,
            List<Long> ids
    );

    void deleteByUserIdAndAttachmentId(Long userId, Long attachmentId);

    void deleteByUserIdAndSessionId(Long userId, Long sessionId);
}
