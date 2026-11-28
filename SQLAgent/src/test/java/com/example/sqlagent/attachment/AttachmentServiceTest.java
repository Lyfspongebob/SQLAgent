package com.example.sqlagent.attachment;

import com.example.sqlagent.chat.ChatSession;
import com.example.sqlagent.chat.ChatSessionRepository;
import com.example.sqlagent.attachment.document.DocumentArtifactCodec;
import com.example.sqlagent.attachment.document.DocumentContextBuilder;
import com.example.sqlagent.attachment.document.DocumentFormat;
import com.example.sqlagent.attachment.document.DocumentManifest;
import com.example.sqlagent.attachment.document.DocumentParsingService;
import com.example.sqlagent.attachment.document.DocumentUnit;
import com.example.sqlagent.attachment.document.ParsedDocument;
import com.example.sqlagent.error.ConflictException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class AttachmentServiceTest {

    @Mock
    private ChatSessionRepository sessionRepository;

    @Mock
    private ChatAttachmentRepository attachmentRepository;

    private InMemoryAttachmentStorage storage;
    private AttachmentService service;
    private DocumentArtifactCodec artifactCodec;

    @BeforeEach
    void setUp() {
        AttachmentProperties properties = new AttachmentProperties();
        properties.setMaxTotalSizeBytes(1_000_000);
        properties.setMaxTotalImageBytes(1_000_000);
        properties.setMaxTotalTextChars(10_000);

        storage = new InMemoryAttachmentStorage();
        artifactCodec = new DocumentArtifactCodec(new ObjectMapper());
        service = new AttachmentService(
                sessionRepository,
                attachmentRepository,
                storage,
                properties,
                mock(DocumentParsingService.class),
                artifactCodec,
                new DocumentContextBuilder(properties),
                mock(DocumentIndexService.class)
        );

        ChatSession session = new ChatSession();
        session.setId(20L);
        session.setUserId(7L);
        when(sessionRepository.findById(20L)).thenReturn(Optional.of(session));
    }

    @Test
    void loadsOwnedImageAndTextAttachmentsInRequestedOrder() {
        ChatAttachment image = attachment(
                1L,
                "schema.png",
                "image/png",
                AttachmentKind.IMAGE,
                null
        );
        ChatAttachment text = attachment(
                2L,
                "rules.md",
                "text/markdown",
                AttachmentKind.TEXT,
                "只查询本学期课程"
        );

        byte[] imageBytes = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47};
        byte[] textBytes = text.getExtractedText().getBytes(StandardCharsets.UTF_8);
        storage.write(image.getStorageKey(), imageBytes);
        storage.write(text.getStorageKey(), textBytes);
        image.setSizeBytes(imageBytes.length);
        text.setSizeBytes(textBytes.length);

        when(attachmentRepository.findByUserIdAndIdInOrderByIdAsc(
                eq(7L),
                any()
        )).thenReturn(List.of(image, text));

        AttachmentService.LoadedAttachments loaded =
                service.loadForMessage(7L, 20L, List.of(2L, 1L));

        assertEquals(2, loaded.payloads().size());
        assertEquals("rules.md", loaded.payloads().get(0).name());
        assertEquals("只查询本学期课程", loaded.payloads().get(0).textContent());
        assertEquals("schema.png", loaded.payloads().get(1).name());
        assertTrue(loaded.payloads().get(1).isImage());
        assertArrayEquals(imageBytes, loaded.payloads().get(1).bytes());
    }

    @Test
    void rejectsAttachmentAlreadyBoundToMessage() {
        ChatAttachment attachment = attachment(
                3L,
                "used.csv",
                "text/csv",
                AttachmentKind.TEXT,
                "id,name\n1,Alice"
        );
        attachment.setMessageId(99L);

        when(attachmentRepository.findByUserIdAndIdInOrderByIdAsc(
                eq(7L),
                any()
        )).thenReturn(List.of(attachment));

        assertThrows(
                ConflictException.class,
                () -> service.loadForMessage(7L, 20L, List.of(3L))
        );
    }

    @Test
    void buildsBudgetedDocumentContextFromRelevantSections() {
        List<DocumentUnit> units = List.of(
                unit("第 1 页", "数据库账号必须使用只读权限。", "1"),
                unit("第 2 页", "教师信息表包含姓名和院系。", "2"),
                unit("第 3 页", "财务报销流程与本次问题无关。", "3"),
                unit("第 4 页", "课程表包含课程编号。", "4"),
                unit("第 5 页", "数据库权限变更需要审批。", "5"),
                unit("第 6 页", "系统日志保留 30 天。", "6")
        );
        ParsedDocument parsed = new ParsedDocument(
                "test-parser",
                new DocumentManifest(
                        DocumentFormat.PDF,
                        "PDF 共 6 页，包含数据库与教学管理说明。",
                        java.util.Map.of("pages", 6)
                ),
                units
        );

        ChatAttachment document = attachment(
                4L,
                "权限说明.pdf",
                "application/pdf",
                AttachmentKind.PDF,
                null
        );
        document.setParsedStorageKey("test/parsed/4");
        document.setSizeBytes(100);
        storage.write(
                document.getParsedStorageKey(),
                artifactCodec.write(parsed)
        );

        when(attachmentRepository.findByUserIdAndIdInOrderByIdAsc(
                eq(7L),
                any()
        )).thenReturn(List.of(document));

        AttachmentService.LoadedAttachments loaded =
                service.loadForMessage(
                        7L,
                        20L,
                        List.of(4L),
                        "数据库权限有什么要求？"
                );

        assertEquals(1, loaded.payloads().size());
        AttachmentPayload context = loaded.payloads().get(0);
        assertEquals(AttachmentKind.TEXT, context.kind());
        assertEquals("document-context.txt", context.name());
        assertTrue(context.textContent().contains("数据库账号必须使用只读权限"));
        assertTrue(context.textContent().contains("数据库权限变更需要审批"));
        assertFalse(context.textContent().contains("财务报销流程与本次问题无关"));
        assertTrue(context.textContent().length() <= 16_000);
    }

    private ChatAttachment attachment(
            Long id,
            String name,
            String contentType,
            AttachmentKind kind,
            String text
    ) {
        ChatAttachment attachment = new ChatAttachment();
        attachment.setId(id);
        attachment.setUserId(7L);
        attachment.setSessionId(20L);
        attachment.setOriginalName(name);
        attachment.setStorageKey("test/" + id);
        attachment.setContentType(contentType);
        attachment.setKind(kind);
        attachment.setExtractedText(text);
        attachment.setSizeBytes(1);
        return attachment;
    }

    private DocumentUnit unit(String locator, String text, String page) {
        return new DocumentUnit(
                locator,
                "pdf_page",
                locator,
                text,
                Map.of("page", page)
        );
    }

    private static class InMemoryAttachmentStorage implements AttachmentStorage {
        private final Map<String, byte[]> files = new HashMap<>();

        @Override
        public void write(String storageKey, byte[] content) {
            files.put(storageKey, content.clone());
        }

        @Override
        public byte[] read(String storageKey) {
            return files.get(storageKey).clone();
        }

        @Override
        public void delete(String storageKey) {
            files.remove(storageKey);
        }
    }
}
