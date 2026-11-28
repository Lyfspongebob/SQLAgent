package com.example.sqlagent.attachment;

import com.example.sqlagent.attachment.document.DocumentArtifactCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SessionDocumentServiceTest {
    @Test
    void searchesChunksAcrossSessionDocuments() {
        ChatAttachmentRepository attachmentRepository =
                mock(ChatAttachmentRepository.class);
        DocumentIndexService indexService = mock(DocumentIndexService.class);
        AttachmentStorage storage = mock(AttachmentStorage.class);
        ObjectMapper objectMapper = new ObjectMapper();

        SessionDocumentService service = new SessionDocumentService(
                attachmentRepository,
                indexService,
                storage,
                new DocumentArtifactCodec(objectMapper),
                objectMapper
        );

        ChatAttachment usersDoc = attachment(1L, "数据库.pdf", AttachmentKind.PDF);
        ChatAttachment courseDoc = attachment(2L, "课程.xlsx", AttachmentKind.EXCEL);
        when(attachmentRepository.findByUserIdAndSessionIdOrderByIdAsc(7L, 20L))
                .thenReturn(List.of(usersDoc, courseDoc));
        when(indexService.findAttachmentChunks(7L, 1L))
                .thenReturn(List.of(chunk(11L, 1L)));
        when(indexService.findAttachmentChunks(7L, 2L))
                .thenReturn(List.of(chunk(12L, 2L)));

        DocumentChunk usersChunk = chunk(11L, 1L);
        usersChunk.setLocator("第 3 页");
        usersChunk.setTitle("用户表权限");
        usersChunk.setContent("users 表包含 user_id、username 和 password 字段。");
        DocumentChunk courseChunk = chunk(12L, 2L);
        courseChunk.setLocator("工作表「课程」第 1-10 行");
        courseChunk.setTitle("课程目录");
        courseChunk.setContent("课程编号和课程名称。");
        when(indexService.findSessionChunks(7L, 20L))
                .thenReturn(List.of(usersChunk, courseChunk));

        List<SessionDocumentService.SearchHit> hits = service.search(
                7L,
                20L,
                "users 表有哪些字段",
                List.of(),
                5
        );

        assertFalse(hits.isEmpty());
        assertEquals(1L, hits.get(0).attachmentId());
        assertTrue(hits.get(0).preview().contains("user_id"));
    }

    private ChatAttachment attachment(
            Long id,
            String name,
            AttachmentKind kind
    ) {
        ChatAttachment attachment = new ChatAttachment();
        attachment.setId(id);
        attachment.setUserId(7L);
        attachment.setSessionId(20L);
        attachment.setOriginalName(name);
        attachment.setKind(kind);
        attachment.setSizeBytes(100);
        return attachment;
    }

    private DocumentChunk chunk(Long id, Long attachmentId) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setId(id);
        chunk.setUserId(7L);
        chunk.setSessionId(20L);
        chunk.setAttachmentId(attachmentId);
        return chunk;
    }
}
