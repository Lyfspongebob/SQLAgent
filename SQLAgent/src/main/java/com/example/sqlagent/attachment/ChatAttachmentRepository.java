package com.example.sqlagent.attachment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ChatAttachmentRepository extends JpaRepository<ChatAttachment, Long> {
    List<ChatAttachment> findByUserIdAndIdInOrderByIdAsc(Long userId, Collection<Long> ids);

    List<ChatAttachment> findByUserIdAndMessageIdIn(Long userId, Collection<Long> messageIds);

    List<ChatAttachment> findByUserIdAndSessionIdOrderByIdAsc(Long userId, Long sessionId);

    long countByUserIdAndSessionIdAndMessageIdIsNull(Long userId, Long sessionId);
}
