package com.example.sqlagent.attachment;

import com.example.sqlagent.attachment.document.DocumentUnit;
import com.example.sqlagent.attachment.document.ParsedDocument;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class DocumentIndexService {
    private final DocumentChunkRepository chunkRepository;
    private final ObjectMapper objectMapper;

    public DocumentIndexService(
            DocumentChunkRepository chunkRepository,
            ObjectMapper objectMapper
    ) {
        this.chunkRepository = chunkRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void indexDocument(
            ChatAttachment attachment,
            ParsedDocument document
    ) {
        chunkRepository.deleteByUserIdAndAttachmentId(
                attachment.getUserId(),
                attachment.getId()
        );

        List<DocumentChunk> chunks = new ArrayList<>();
        for (DocumentUnit unit : document.units()) {
            DocumentChunk chunk = baseChunk(attachment);
            chunk.setLocator(unit.locator());
            chunk.setType(unit.type());
            chunk.setTitle(unit.title());
            chunk.setContent(unit.text());
            chunk.setCharCount(unit.text() == null ? 0 : unit.text().length());
            chunk.setMetadataJson(writeMetadata(unit.metadata()));
            chunks.add(chunk);
        }

        if (!chunks.isEmpty()) {
            chunkRepository.saveAll(chunks);
        }
    }

    @Transactional
    public void indexText(ChatAttachment attachment) {
        chunkRepository.deleteByUserIdAndAttachmentId(
                attachment.getUserId(),
                attachment.getId()
        );

        String content = attachment.getExtractedText();
        if (content == null || content.isBlank()) {
            return;
        }

        DocumentChunk chunk = baseChunk(attachment);
        chunk.setLocator("全文");
        chunk.setType("text");
        chunk.setTitle(attachment.getOriginalName());
        chunk.setContent(content);
        chunk.setCharCount(content.length());
        chunk.setMetadataJson("{}");
        chunkRepository.save(chunk);
    }

    @Transactional
    public void deleteForSession(long userId, Long sessionId) {
        chunkRepository.deleteByUserIdAndSessionId(userId, sessionId);
    }

    @Transactional
    public void deleteForAttachment(long userId, Long attachmentId) {
        chunkRepository.deleteByUserIdAndAttachmentId(userId, attachmentId);
    }

    public List<DocumentChunk> findSessionChunks(long userId, Long sessionId) {
        return chunkRepository
                .findByUserIdAndSessionIdOrderByAttachmentIdAscIdAsc(
                        userId,
                        sessionId
                );
    }

    public List<DocumentChunk> findChunks(
            long userId,
            Long sessionId,
            List<Long> ids
    ) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return chunkRepository.findByUserIdAndSessionIdAndIdIn(
                userId,
                sessionId,
                ids
        );
    }

    public List<DocumentChunk> findAttachmentChunks(
            long userId,
            Long attachmentId
    ) {
        return chunkRepository.findByUserIdAndAttachmentIdOrderByIdAsc(
                userId,
                attachmentId
        );
    }

    private DocumentChunk baseChunk(ChatAttachment attachment) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setUserId(attachment.getUserId());
        chunk.setSessionId(attachment.getSessionId());
        chunk.setAttachmentId(attachment.getId());
        chunk.setCreateTime(LocalDateTime.now());
        return chunk;
    }

    private String writeMetadata(java.util.Map<String, String> metadata) {
        try {
            return objectMapper.writeValueAsString(
                    metadata == null ? java.util.Map.of() : metadata
            );
        } catch (Exception ignored) {
            return "{}";
        }
    }
}
