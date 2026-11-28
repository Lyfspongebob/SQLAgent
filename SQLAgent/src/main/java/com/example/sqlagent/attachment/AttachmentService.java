package com.example.sqlagent.attachment;

import cn.dev33.satoken.stp.StpUtil;
import com.example.sqlagent.attachment.document.DocumentArtifactCodec;
import com.example.sqlagent.attachment.document.DocumentContextBuilder;
import com.example.sqlagent.attachment.document.DocumentParsingService;
import com.example.sqlagent.attachment.document.ParsedDocument;
import com.example.sqlagent.chat.ChatSession;
import com.example.sqlagent.chat.ChatSessionRepository;
import com.example.sqlagent.dto.AttachmentResponse;
import com.example.sqlagent.error.ConflictException;
import com.example.sqlagent.error.ErrorCode;
import com.example.sqlagent.error.ForbiddenException;
import com.example.sqlagent.error.NotFoundException;
import com.example.sqlagent.error.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class AttachmentService {
    private static final Logger log =
            LoggerFactory.getLogger(AttachmentService.class);

    private static final Map<String, String> TEXT_CONTENT_TYPES = Map.ofEntries(
            Map.entry("txt", "text/plain"),
            Map.entry("md", "text/markdown"),
            Map.entry("markdown", "text/markdown"),
            Map.entry("csv", "text/csv"),
            Map.entry("json", "application/json"),
            Map.entry("jsonl", "application/x-ndjson"),
            Map.entry("ndjson", "application/x-ndjson"),
            Map.entry("sql", "application/sql"),
            Map.entry("log", "text/plain"),
            Map.entry("yaml", "application/yaml"),
            Map.entry("yml", "application/yaml"),
            Map.entry("xml", "application/xml")
    );

    private final ChatSessionRepository chatSessionRepository;
    private final ChatAttachmentRepository attachmentRepository;
    private final AttachmentStorage storage;
    private final AttachmentProperties properties;
    private final DocumentParsingService documentParsingService;
    private final DocumentArtifactCodec documentArtifactCodec;
    private final DocumentContextBuilder documentContextBuilder;
    private final DocumentIndexService documentIndexService;

    public AttachmentService(
            ChatSessionRepository chatSessionRepository,
            ChatAttachmentRepository attachmentRepository,
            AttachmentStorage storage,
            AttachmentProperties properties,
            DocumentParsingService documentParsingService,
            DocumentArtifactCodec documentArtifactCodec,
            DocumentContextBuilder documentContextBuilder,
            DocumentIndexService documentIndexService
    ) {
        this.chatSessionRepository = chatSessionRepository;
        this.attachmentRepository = attachmentRepository;
        this.storage = storage;
        this.properties = properties;
        this.documentParsingService = documentParsingService;
        this.documentArtifactCodec = documentArtifactCodec;
        this.documentContextBuilder = documentContextBuilder;
        this.documentIndexService = documentIndexService;
    }

    public AttachmentResponse upload(Long sessionId, MultipartFile file) {
        long userId = StpUtil.getLoginIdAsLong();
        requireOwnedSession(userId, sessionId);

        long pendingCount = attachmentRepository
                .countByUserIdAndSessionIdAndMessageIdIsNull(userId, sessionId);
        int maxFiles = Math.max(1, properties.getMaxFilesPerMessage());
        if (pendingCount >= maxFiles) {
            throw new ValidationException(
                    ErrorCode.ATTACHMENT_INVALID,
                    "当前会话待发送附件已达到 " + maxFiles + " 个，请先发送或移除已有附件"
            );
        }

        if (file == null || file.isEmpty()) {
            throw new ValidationException(
                    ErrorCode.ATTACHMENT_INVALID,
                    "请选择要上传的文件"
            );
        }

        long maxRequestCandidate = Math.max(
                properties.getMaxFileSizeBytes(),
                properties.getMaxDocumentSizeBytes()
        );
        if (file.getSize() > maxRequestCandidate) {
            throw new ValidationException(
                    ErrorCode.ATTACHMENT_TOO_LARGE,
                    "单个附件不能超过 " + humanSize(maxRequestCandidate)
            );
        }

        final byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException exception) {
            throw new ValidationException(
                    ErrorCode.ATTACHMENT_INVALID,
                    "读取上传文件失败",
                    null,
                    exception
            );
        }

        ClassifiedFile classified = classify(bytes, file.getOriginalFilename());
        long maxBytes = classified.kind().isDocument()
                ? properties.getMaxDocumentSizeBytes()
                : properties.getMaxFileSizeBytes();
        if (file.getSize() > maxBytes) {
            throw new ValidationException(
                    ErrorCode.ATTACHMENT_TOO_LARGE,
                    classified.kind().isDocument()
                            ? "单个文档不能超过 " + humanSize(maxBytes)
                            : "单个图片或文本附件不能超过 " + humanSize(maxBytes)
            );
        }

        String originalName = safeOriginalName(
                file.getOriginalFilename(),
                classified.defaultName()
        );
        String objectId = UUID.randomUUID().toString();
        String storageKey = "users/%d/sessions/%d/%s%s".formatted(
                userId,
                sessionId,
                objectId,
                classified.extension()
        );
        String parsedStorageKey = classified.parsedDocument() == null
                ? null
                : "users/%d/sessions/%d/%s.document.json".formatted(
                        userId,
                        sessionId,
                        objectId
                );
        String manifestJson = classified.parsedDocument() == null
                ? null
                : documentArtifactCodec.writeManifest(
                        classified.parsedDocument()
                );

        try {
            storage.write(storageKey, bytes);
            if (parsedStorageKey != null) {
                storage.write(
                        parsedStorageKey,
                        documentArtifactCodec.write(classified.parsedDocument())
                );
            }
        } catch (RuntimeException exception) {
            storage.delete(storageKey);
            if (parsedStorageKey != null) {
                storage.delete(parsedStorageKey);
            }
            throw exception;
        }

        ChatAttachment attachment = new ChatAttachment();
        attachment.setUserId(userId);
        attachment.setSessionId(sessionId);
        attachment.setOriginalName(originalName);
        attachment.setStorageKey(storageKey);
        attachment.setContentType(classified.contentType());
        attachment.setSizeBytes(bytes.length);
        attachment.setKind(classified.kind());
        attachment.setExtractedText(classified.extractedText());
        attachment.setManifestJson(manifestJson);
        attachment.setParsedStorageKey(parsedStorageKey);
        attachment.setParserVersion(
                classified.parsedDocument() == null
                        ? null
                        : classified.parsedDocument().parserVersion()
        );
        attachment.setCreateTime(LocalDateTime.now());

        try {
            ChatAttachment saved = attachmentRepository.save(attachment);
            if (saved.getKind() == AttachmentKind.TEXT) {
                documentIndexService.indexText(saved);
            } else if (saved.getKind().isDocument()
                    && classified.parsedDocument() != null) {
                documentIndexService.indexDocument(
                        saved,
                        classified.parsedDocument()
                );
            }
            return AttachmentResponse.from(saved);
        } catch (RuntimeException exception) {
            if (attachment.getId() != null) {
                try {
                    documentIndexService.deleteForAttachment(
                            userId,
                            attachment.getId()
                    );
                    attachmentRepository.delete(attachment);
                } catch (RuntimeException ignored) {
                    // 主异常优先返回。
                }
            }
            storage.delete(storageKey);
            if (parsedStorageKey != null) {
                storage.delete(parsedStorageKey);
            }
            throw exception;
        }
    }

    public LoadedAttachments loadForMessage(
            long userId,
            Long sessionId,
            List<Long> attachmentIds
    ) {
        return loadForMessage(
                userId,
                sessionId,
                attachmentIds,
                "",
                true
        );
    }

    public LoadedAttachments loadForMessage(
            long userId,
            Long sessionId,
            List<Long> attachmentIds,
            String userQuery
    ) {
        return loadForMessage(
                userId,
                sessionId,
                attachmentIds,
                userQuery,
                true
        );
    }

    public LoadedAttachments loadForAgentMessage(
            long userId,
            Long sessionId,
            List<Long> attachmentIds
    ) {
        return loadForMessage(
                userId,
                sessionId,
                attachmentIds,
                "",
                false
        );
    }

    public LoadedAttachments loadForMessage(
            long userId,
            Long sessionId,
            List<Long> attachmentIds,
            String userQuery,
            boolean includeDocumentContext
    ) {
        requireOwnedSession(userId, sessionId);

        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return LoadedAttachments.empty();
        }

        List<Long> ids = attachmentIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        if (ids.isEmpty()) {
            return LoadedAttachments.empty();
        }

        int maxFiles = Math.max(1, properties.getMaxFilesPerMessage());
        if (ids.size() > maxFiles) {
            throw new ValidationException(
                    ErrorCode.ATTACHMENT_INVALID,
                    "每条消息最多上传 " + maxFiles + " 个附件"
            );
        }

        Map<Long, ChatAttachment> byId = new HashMap<>();
        attachmentRepository.findByUserIdAndIdInOrderByIdAsc(userId, ids)
                .forEach(item -> byId.put(item.getId(), item));

        if (byId.size() != ids.size()) {
            throw new NotFoundException(
                    ErrorCode.ATTACHMENT_NOT_FOUND,
                    "部分附件不存在"
            );
        }

        List<ChatAttachment> ordered = new ArrayList<>(ids.size());
        long totalBytes = 0;
        int totalTextChars = 0;

        for (Long id : ids) {
            ChatAttachment attachment = byId.get(id);
            if (!Objects.equals(attachment.getSessionId(), sessionId)) {
                throw new ForbiddenException(
                        ErrorCode.ATTACHMENT_ACCESS_DENIED,
                        "附件不属于当前会话"
                );
            }
            if (attachment.getMessageId() != null) {
                throw new ConflictException(
                        ErrorCode.ATTACHMENT_ALREADY_USED,
                        "附件已经发送，不能重复使用"
                );
            }

            totalBytes += attachment.getSizeBytes();
            if (attachment.getExtractedText() != null) {
                totalTextChars += attachment.getExtractedText().length();
            }
            ordered.add(attachment);
        }

        if (totalBytes > properties.getMaxTotalSizeBytes()) {
            throw new ValidationException(
                    ErrorCode.ATTACHMENT_TOO_LARGE,
                    "本条消息的附件总大小不能超过 "
                            + humanSize(properties.getMaxTotalSizeBytes())
            );
        }
        if (totalTextChars > properties.getMaxTotalTextChars()) {
            throw new ValidationException(
                    ErrorCode.ATTACHMENT_INVALID,
                    "本条消息的文本附件内容过长，请减少文件数量或拆分内容"
            );
        }

        List<AttachmentPayload> payloads = new ArrayList<>(ordered.size());
        List<DocumentContextBuilder.DocumentSource> documentSources =
                new ArrayList<>();
        long totalImageBytes = 0;

        for (ChatAttachment attachment : ordered) {
            if (attachment.getKind() == AttachmentKind.IMAGE) {
                byte[] content = storage.read(attachment.getStorageKey());
                if (content.length != attachment.getSizeBytes()) {
                    throw new ValidationException(
                            ErrorCode.ATTACHMENT_STORAGE_FAILED,
                            "附件内容不完整，请重新上传"
                    );
                }
                totalImageBytes += content.length;
                payloads.add(new AttachmentPayload(
                        attachment.getOriginalName(),
                        attachment.getContentType(),
                        attachment.getKind(),
                        content,
                        null
                ));
            } else if (attachment.getKind() == AttachmentKind.TEXT) {
                payloads.add(new AttachmentPayload(
                        attachment.getOriginalName(),
                        attachment.getContentType(),
                        attachment.getKind(),
                        new byte[0],
                        attachment.getExtractedText()
                ));
            } else {
                if (!includeDocumentContext) {
                    continue;
                }
                if (attachment.getParsedStorageKey() == null) {
                    throw new ValidationException(
                            ErrorCode.ATTACHMENT_STORAGE_FAILED,
                            "文档解析产物不存在，请重新上传该附件"
                    );
                }
                byte[] artifactBytes = storage.read(
                        attachment.getParsedStorageKey()
                );
                ParsedDocument parsedDocument =
                        documentArtifactCodec.read(artifactBytes);
                documentSources.add(new DocumentContextBuilder.DocumentSource(
                        attachment.getOriginalName(),
                        parsedDocument
                ));
            }
        }

        if (totalImageBytes > properties.getMaxTotalImageBytes()) {
            throw new ValidationException(
                    ErrorCode.ATTACHMENT_TOO_LARGE,
                    "本条消息的图片总大小不能超过 "
                            + humanSize(properties.getMaxTotalImageBytes())
            );
        }

        String documentContext = includeDocumentContext
                ? documentContextBuilder.build(userQuery, documentSources)
                : "";
        if (!documentContext.isBlank()) {
            payloads.add(new AttachmentPayload(
                    "document-context.txt",
                    "text/plain",
                    AttachmentKind.TEXT,
                    new byte[0],
                    documentContext
            ));
        }

        return new LoadedAttachments(
                List.copyOf(ordered),
                List.copyOf(payloads)
        );
    }

    @Transactional
    public void attachToMessage(long userId, List<ChatAttachment> attachments, Long messageId) {
        if (attachments == null || attachments.isEmpty()) {
            return;
        }

        for (ChatAttachment attachment : attachments) {
            if (!Objects.equals(attachment.getUserId(), userId)) {
                throw new ForbiddenException(
                        ErrorCode.ATTACHMENT_ACCESS_DENIED,
                        "无权使用该附件"
                );
            }
            if (attachment.getMessageId() != null) {
                throw new ConflictException(
                        ErrorCode.ATTACHMENT_ALREADY_USED,
                        "附件已经发送，不能重复使用"
                );
            }
            attachment.setMessageId(messageId);
        }
        attachmentRepository.saveAll(attachments);
    }

    public AttachmentContent getContent(Long attachmentId) {
        long userId = StpUtil.getLoginIdAsLong();
        ChatAttachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new NotFoundException(
                        ErrorCode.ATTACHMENT_NOT_FOUND,
                        "附件不存在"
                ));
        if (!Objects.equals(attachment.getUserId(), userId)) {
            throw new ForbiddenException(
                    ErrorCode.ATTACHMENT_ACCESS_DENIED,
                    "无权访问该附件"
            );
        }
        return new AttachmentContent(
                attachment.getOriginalName(),
                attachment.getContentType(),
                storage.read(attachment.getStorageKey())
        );
    }

    @Transactional
    public void deletePending(Long attachmentId) {
        long userId = StpUtil.getLoginIdAsLong();
        ChatAttachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new NotFoundException(
                        ErrorCode.ATTACHMENT_NOT_FOUND,
                        "附件不存在"
                ));
        if (!Objects.equals(attachment.getUserId(), userId)) {
            throw new ForbiddenException(
                    ErrorCode.ATTACHMENT_ACCESS_DENIED,
                    "无权删除该附件"
            );
        }
        if (attachment.getMessageId() != null) {
            throw new ConflictException(
                    ErrorCode.ATTACHMENT_ALREADY_USED,
                    "已发送的附件不能单独删除"
            );
        }

        attachmentRepository.delete(attachment);
        documentIndexService.deleteForAttachment(userId, attachmentId);
        deleteFilesAfterCommit(List.of(attachment));
    }

    public Map<Long, List<AttachmentResponse>> responsesByMessage(
            long userId,
            List<Long> messageIds
    ) {
        if (messageIds == null || messageIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, List<AttachmentResponse>> grouped = new LinkedHashMap<>();
        attachmentRepository.findByUserIdAndMessageIdIn(userId, messageIds)
                .stream()
                .sorted(Comparator.comparing(ChatAttachment::getId))
                .forEach(item -> grouped
                        .computeIfAbsent(item.getMessageId(), ignored -> new ArrayList<>())
                        .add(AttachmentResponse.from(item)));
        return grouped;
    }

    @Transactional
    public void deleteForSession(long userId, Long sessionId) {
        List<ChatAttachment> attachments =
                attachmentRepository.findByUserIdAndSessionIdOrderByIdAsc(userId, sessionId);
        documentIndexService.deleteForSession(userId, sessionId);
        attachmentRepository.deleteAll(attachments);
        deleteFilesAfterCommit(attachments);
    }

    private void requireOwnedSession(long userId, Long sessionId) {
        if (sessionId == null) {
            throw new ValidationException(
                    ErrorCode.REQUEST_VALIDATION_FAILED,
                    "会话 ID 不能为空"
            );
        }

        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new NotFoundException(
                        ErrorCode.SESSION_NOT_FOUND,
                        "会话不存在"
                ));
        if (!Objects.equals(session.getUserId(), userId)) {
            throw new ForbiddenException(
                    ErrorCode.SESSION_ACCESS_DENIED,
                    "无权操作此会话"
            );
        }
    }

    private void deleteFilesAfterCommit(List<ChatAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return;
        }

        Runnable deleteFiles = () -> attachments.forEach(attachment -> {
            storage.delete(attachment.getStorageKey());
            if (attachment.getParsedStorageKey() != null) {
                storage.delete(attachment.getParsedStorageKey());
            }
        });

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            deleteFiles.run();
                        }
                    }
            );
        } else {
            deleteFiles.run();
        }
    }

    private ClassifiedFile classify(byte[] bytes, String originalName) {
        if (isPng(bytes)) {
            validateImage(bytes);
            return new ClassifiedFile(
                    AttachmentKind.IMAGE,
                    "image/png",
                    ".png",
                    null,
                    "attachment.png",
                    null
            );
        }
        if (isJpeg(bytes)) {
            validateImage(bytes);
            return new ClassifiedFile(
                    AttachmentKind.IMAGE,
                    "image/jpeg",
                    ".jpg",
                    null,
                    "attachment.jpg",
                    null
            );
        }
        if (isGif(bytes)) {
            validateImage(bytes);
            return new ClassifiedFile(
                    AttachmentKind.IMAGE,
                    "image/gif",
                    ".gif",
                    null,
                    "attachment.gif",
                    null
            );
        }

        String extension = extensionOf(originalName);
        boolean documentExtension = isDocumentExtension(extension);
        long typeLimit = documentExtension
                ? properties.getMaxDocumentSizeBytes()
                : properties.getMaxFileSizeBytes();
        if (bytes.length > typeLimit) {
            throw new ValidationException(
                    ErrorCode.ATTACHMENT_TOO_LARGE,
                    documentExtension
                            ? "单个文档不能超过 " + humanSize(typeLimit)
                            : "单个图片或文本附件不能超过 " + humanSize(typeLimit)
            );
        }

        if (extension.equals("pdf")) {
            if (!isPdf(bytes)) {
                throw invalidDocument("文件扩展名为 .pdf，但内容不是有效 PDF");
            }
            ParsedDocument parsedDocument = documentParsingService.parse(
                    AttachmentKind.PDF,
                    bytes,
                    originalName
            );
            return new ClassifiedFile(
                    AttachmentKind.PDF,
                    "application/pdf",
                    ".pdf",
                    null,
                    "attachment.pdf",
                    parsedDocument
            );
        }
        if (extension.equals("docx") || extension.equals("doc")) {
            boolean legacy = extension.equals("doc");
            if (legacy ? !isOle(bytes) : !isZip(bytes)) {
                throw invalidDocument("Word 文件头与扩展名不匹配");
            }
            AttachmentKind kind = AttachmentKind.WORD;
            ParsedDocument parsedDocument = documentParsingService.parse(
                    kind,
                    bytes,
                    originalName
            );
            return new ClassifiedFile(
                    kind,
                    legacy
                            ? "application/msword"
                            : "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "." + extension,
                    null,
                    "attachment." + extension,
                    parsedDocument
            );
        }
        if (extension.equals("xlsx") || extension.equals("xls")) {
            boolean legacy = extension.equals("xls");
            if (legacy ? !isOle(bytes) : !isZip(bytes)) {
                throw invalidDocument("Excel 文件头与扩展名不匹配");
            }
            AttachmentKind kind = AttachmentKind.EXCEL;
            ParsedDocument parsedDocument = documentParsingService.parse(
                    kind,
                    bytes,
                    originalName
            );
            return new ClassifiedFile(
                    kind,
                    legacy
                            ? "application/vnd.ms-excel"
                            : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "." + extension,
                    null,
                    "attachment." + extension,
                    parsedDocument
            );
        }

        String contentType = TEXT_CONTENT_TYPES.get(extension);
        if (contentType == null) {
            throw new ValidationException(
                    ErrorCode.ATTACHMENT_UNSUPPORTED,
                    "暂不支持该文件类型，可上传图片、PDF、Word、Excel 或 UTF-8 文本文件"
            );
        }

        String text = decodeUtf8(bytes);
        if (text.indexOf('\0') >= 0) {
            throw new ValidationException(
                    ErrorCode.ATTACHMENT_UNSUPPORTED,
                    "该文件不是可解析的 UTF-8 文本"
            );
        }

        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }

        int maxChars = Math.max(1, properties.getMaxTextChars());
        if (text.length() > maxChars) {
            text = text.substring(0, maxChars)
                    + "\n\n[附件内容已按配置截断]";
        }

        return new ClassifiedFile(
                AttachmentKind.TEXT,
                contentType,
                "." + extension,
                text,
                "attachment." + extension,
                null
        );
    }

    private void validateImage(byte[] bytes) {
        try (
                ImageInputStream input = ImageIO.createImageInputStream(
                        new ByteArrayInputStream(bytes)
                )
        ) {
            if (input == null) {
                throw imageValidationFailure();
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw imageValidationFailure();
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0
                        || width > 12_000 || height > 12_000
                        || (long) width * height > 40_000_000L) {
                    throw imageValidationFailure();
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw imageValidationFailure();
        }
    }

    private ValidationException imageValidationFailure() {
        return new ValidationException(
                ErrorCode.ATTACHMENT_INVALID,
                "图片已损坏或分辨率过大"
        );
    }

    private String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new ValidationException(
                    ErrorCode.ATTACHMENT_UNSUPPORTED,
                    "文本附件必须使用 UTF-8 编码",
                    null,
                    exception
            );
        }
    }

    private String safeOriginalName(String originalName, String fallback) {
        String cleaned = StringUtils.cleanPath(
                Objects.requireNonNullElse(originalName, "")
        );
        String filename = StringUtils.getFilename(cleaned);
        if (filename == null || filename.isBlank() || ".".equals(filename)) {
            filename = fallback;
        }

        filename = filename.replaceAll("[\\p{Cntrl}]", "_");
        if (filename.length() > 255) {
            int dot = filename.lastIndexOf('.');
            String extension = dot > 0 ? filename.substring(dot) : "";
            filename = filename.substring(0, 255 - extension.length()) + extension;
        }
        return filename;
    }

    private String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        String base = StringUtils.getFilename(StringUtils.cleanPath(filename));
        if (base == null) {
            return "";
        }
        int dot = base.lastIndexOf('.');
        return dot < 0 || dot == base.length() - 1
                ? ""
                : base.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private boolean isDocumentExtension(String extension) {
        return Set.of("pdf", "doc", "docx", "xls", "xlsx")
                .contains(extension);
    }

    private boolean isPng(byte[] bytes) {
        byte[] signature = {
                (byte) 0x89, 0x50, 0x4E, 0x47,
                0x0D, 0x0A, 0x1A, 0x0A
        };
        return startsWith(bytes, signature);
    }

    private boolean isJpeg(byte[] bytes) {
        return bytes != null && bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF;
    }

    private boolean isGif(byte[] bytes) {
        return bytes != null && bytes.length >= 6
                && bytes[0] == 'G'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == '8'
                && (bytes[4] == '7' || bytes[4] == '9')
                && bytes[5] == 'a';
    }

    private boolean isPdf(byte[] bytes) {
        byte[] signature = {'%', 'P', 'D', 'F', '-'};
        return startsWith(bytes, signature);
    }

    private boolean isZip(byte[] bytes) {
        return bytes != null
                && bytes.length >= 4
                && bytes[0] == 'P'
                && bytes[1] == 'K'
                && (
                    (bytes[2] == 3 && bytes[3] == 4)
                    || (bytes[2] == 5 && bytes[3] == 6)
                    || (bytes[2] == 7 && bytes[3] == 8)
                );
    }

    private boolean isOle(byte[] bytes) {
        byte[] signature = {
                (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1
        };
        return startsWith(bytes, signature);
    }

    private ValidationException invalidDocument(String message) {
        return new ValidationException(ErrorCode.ATTACHMENT_INVALID, message);
    }

    private boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes == null || bytes.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (bytes[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private String humanSize(long bytes) {
        if (bytes >= 1024 * 1024) {
            return Math.max(1, Math.round(bytes / (1024.0 * 1024.0))) + " MB";
        }
        return Math.max(1, Math.round(bytes / 1024.0)) + " KB";
    }

    private record ClassifiedFile(
            AttachmentKind kind,
            String contentType,
            String extension,
            String extractedText,
            String defaultName,
            ParsedDocument parsedDocument
    ) {
    }

    public record LoadedAttachments(
            List<ChatAttachment> entities,
            List<AttachmentPayload> payloads
    ) {
        public LoadedAttachments {
            entities = entities == null ? List.of() : List.copyOf(entities);
            payloads = payloads == null ? List.of() : List.copyOf(payloads);
        }

        public static LoadedAttachments empty() {
            return new LoadedAttachments(List.of(), List.of());
        }
    }

    public record AttachmentContent(
            String name,
            String contentType,
            byte[] bytes
    ) {
    }
}
