package com.example.sqlagent.attachment;

import com.example.sqlagent.attachment.document.DocumentArtifactCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SessionDocumentService {
    private static final Pattern HAN_RUN = Pattern.compile("[\\p{IsHan}]+");
    private static final Pattern WORD_RUN = Pattern.compile("[a-z0-9_]{2,}");

    private final ChatAttachmentRepository attachmentRepository;
    private final DocumentIndexService indexService;
    private final AttachmentStorage storage;
    private final DocumentArtifactCodec artifactCodec;
    private final ObjectMapper objectMapper;

    public SessionDocumentService(
            ChatAttachmentRepository attachmentRepository,
            DocumentIndexService indexService,
            AttachmentStorage storage,
            DocumentArtifactCodec artifactCodec,
            ObjectMapper objectMapper
    ) {
        this.attachmentRepository = attachmentRepository;
        this.indexService = indexService;
        this.storage = storage;
        this.artifactCodec = artifactCodec;
        this.objectMapper = objectMapper;
    }

    public List<SessionDocument> listDocuments(long userId, Long sessionId) {
        List<ChatAttachment> attachments = attachmentRepository
                .findByUserIdAndSessionIdOrderByIdAsc(userId, sessionId)
                .stream()
                .filter(this::indexable)
                .toList();
        ensureIndexed(attachments);
        return attachments.stream()
                .map(this::toSessionDocument)
                .toList();
    }

    public String catalog(long userId, Long sessionId) {
        List<SessionDocument> documents = listDocuments(userId, sessionId);
        if (documents.isEmpty()) {
            return "当前会话没有可用于检索的文本或文档附件。";
        }

        StringBuilder catalog = new StringBuilder();
        for (SessionDocument document : documents) {
            catalog.append("- attachment_id=")
                    .append(document.id())
                    .append("，")
                    .append(document.kind())
                    .append("，")
                    .append(document.name());
            if (document.summary() != null && !document.summary().isBlank()) {
                catalog.append("；")
                        .append(compact(document.summary(), 260));
            }
            catalog.append('\n');
        }
        return catalog.toString();
    }

    public String manifest(
            long userId,
            Long sessionId,
            Long attachmentId
    ) {
        ChatAttachment attachment = ownedAttachment(
                userId,
                sessionId,
                attachmentId
        );
        ensureIndexed(List.of(attachment));
        return Objects.requireNonNullElse(
                attachment.getManifestJson(),
                "{\"summary\":\"该附件没有结构化文档清单\"}"
        );
    }

    public List<SearchHit> search(
            long userId,
            Long sessionId,
            String query,
            List<Long> attachmentIds,
            int limit
    ) {
        ensureIndexed(attachmentRepository
                .findByUserIdAndSessionIdOrderByIdAsc(userId, sessionId)
                .stream()
                .filter(this::indexable)
                .toList());
        List<DocumentChunk> chunks = indexService.findSessionChunks(
                userId,
                sessionId
        );
        if (chunks.isEmpty()) {
            return List.of();
        }

        Set<Long> allowedAttachments = attachmentIds == null
                ? Set.of()
                : new HashSet<>(attachmentIds);
        Map<Long, String> names = new HashMap<>();
        attachmentRepository.findByUserIdAndSessionIdOrderByIdAsc(
                        userId,
                        sessionId
                )
                .forEach(item -> names.put(item.getId(), item.getOriginalName()));

        Set<String> tokens = tokens(query);
        int safeLimit = Math.max(1, Math.min(limit, 12));
        List<ScoredChunk> scored = new ArrayList<>();

        for (DocumentChunk chunk : chunks) {
            if (!allowedAttachments.isEmpty()
                    && !allowedAttachments.contains(chunk.getAttachmentId())) {
                continue;
            }
            scored.add(new ScoredChunk(
                    chunk,
                    score(chunk, tokens)
            ));
        }

        List<ScoredChunk> selected = scored.stream()
                .filter(item -> item.score() > 0)
                .sorted(Comparator
                        .comparingInt(ScoredChunk::score)
                        .reversed()
                        .thenComparing(item -> item.chunk().getId()))
                .limit(safeLimit)
                .toList();
        if (selected.isEmpty()) {
            selected = scored.stream()
                    .limit(safeLimit)
                    .toList();
        }

        return selected.stream()
                .map(item -> new SearchHit(
                        item.chunk().getId(),
                        item.chunk().getAttachmentId(),
                        names.getOrDefault(
                                item.chunk().getAttachmentId(),
                                "附件 " + item.chunk().getAttachmentId()
                        ),
                        item.chunk().getLocator(),
                        item.chunk().getTitle(),
                        item.score(),
                        compact(item.chunk().getContent(), 700)
                ))
                .toList();
    }

    public List<ReadChunk> readChunks(
            long userId,
            Long sessionId,
            List<Long> chunkIds,
            Long attachmentId,
            String locator,
            int maxChars
    ) {
        List<DocumentChunk> candidates;
        if (chunkIds != null && !chunkIds.isEmpty()) {
            candidates = indexService.findChunks(
                    userId,
                    sessionId,
                    chunkIds
            );
        } else if (attachmentId != null) {
            candidates = indexService.findAttachmentChunks(
                    userId,
                    attachmentId
            ).stream()
                    .filter(item -> Objects.equals(
                            item.getSessionId(),
                            sessionId
                    ))
                    .filter(item -> locator == null
                            || locator.isBlank()
                            || item.getLocator().contains(locator)
                            || item.getTitle() != null
                            && item.getTitle().contains(locator))
                    .toList();
        } else {
            return List.of();
        }

        int budget = Math.max(1_000, maxChars);
        int used = 0;
        List<ReadChunk> result = new ArrayList<>();
        for (DocumentChunk chunk : candidates) {
            if (used >= budget) {
                break;
            }
            String content = Objects.requireNonNullElse(
                    chunk.getContent(),
                    ""
            );
            String selected = content.length() <= budget - used
                    ? content
                    : content.substring(0, Math.max(1, budget - used))
                            + "\n[片段已截断]";
            used += selected.length();
            result.add(new ReadChunk(
                    chunk.getId(),
                    chunk.getAttachmentId(),
                    chunk.getLocator(),
                    selected
            ));
        }
        return result;
    }

    private ChatAttachment ownedAttachment(
            long userId,
            Long sessionId,
            Long attachmentId
    ) {
        ChatAttachment attachment = attachmentRepository
                .findById(attachmentId)
                .orElseThrow(() -> new com.example.sqlagent.error.NotFoundException(
                        com.example.sqlagent.error.ErrorCode.ATTACHMENT_NOT_FOUND,
                        "附件不存在"
                ));
        if (!Objects.equals(attachment.getUserId(), userId)
                || !Objects.equals(attachment.getSessionId(), sessionId)) {
            throw new com.example.sqlagent.error.ForbiddenException(
                    com.example.sqlagent.error.ErrorCode.ATTACHMENT_ACCESS_DENIED,
                    "无权读取该附件"
            );
        }
        return attachment;
    }

    private boolean indexable(ChatAttachment attachment) {
        return attachment.getKind() == AttachmentKind.TEXT
                || attachment.getKind().isDocument();
    }

    private SessionDocument toSessionDocument(ChatAttachment attachment) {
        String summary = "";
        if (attachment.getManifestJson() != null) {
            try {
                JsonNode manifest = objectMapper.readTree(
                        attachment.getManifestJson()
                );
                summary = manifest.path("summary").asText("");
            } catch (Exception ignored) {
                summary = attachment.getOriginalName();
            }
        } else if (attachment.getKind() == AttachmentKind.TEXT) {
            summary = "文本文件，约 "
                    + Objects.requireNonNullElse(
                            attachment.getExtractedText(),
                            ""
                    ).length()
                    + " 字符";
        }
        return new SessionDocument(
                attachment.getId(),
                attachment.getOriginalName(),
                attachment.getKind(),
                summary,
                attachment.getSizeBytes()
        );
    }

    private void ensureIndexed(List<ChatAttachment> attachments) {
        for (ChatAttachment attachment : attachments) {
            if (!indexService.findAttachmentChunks(
                    attachment.getUserId(),
                    attachment.getId()
            ).isEmpty()) {
                continue;
            }
            try {
                if (attachment.getKind() == AttachmentKind.TEXT) {
                    indexService.indexText(attachment);
                } else if (attachment.getKind().isDocument()
                        && attachment.getParsedStorageKey() != null) {
                    indexService.indexDocument(
                            attachment,
                            artifactCodec.read(storage.read(
                                    attachment.getParsedStorageKey()
                            ))
                    );
                }
            } catch (Exception ignored) {
                // 单份旧附件索引失败不应阻塞其他会话文档。
            }
        }
    }

    private int score(DocumentChunk chunk, Set<String> tokens) {
        if (tokens.isEmpty()) {
            return 1;
        }
        String searchable = (
                Objects.requireNonNullElse(chunk.getLocator(), "")
                        + "\n"
                        + Objects.requireNonNullElse(chunk.getTitle(), "")
                        + "\n"
                        + Objects.requireNonNullElse(chunk.getContent(), "")
        ).toLowerCase(Locale.ROOT);

        int score = 0;
        for (String token : tokens) {
            int count = count(searchable, token);
            if (count > 0) {
                score += Math.min(8, count) * 3;
                if (Objects.requireNonNullElse(chunk.getTitle(), "")
                        .toLowerCase(Locale.ROOT)
                        .contains(token)) {
                    score += 12;
                }
                if (Objects.requireNonNullElse(chunk.getLocator(), "")
                        .toLowerCase(Locale.ROOT)
                        .contains(token)) {
                    score += 10;
                }
            }
        }
        return score;
    }

    private Set<String> tokens(String query) {
        Set<String> tokens = new LinkedHashSet<>();
        String text = Objects.requireNonNullElse(query, "")
                .toLowerCase(Locale.ROOT);

        Matcher han = HAN_RUN.matcher(text);
        while (han.find()) {
            String run = han.group();
            if (run.length() <= 8) {
                tokens.add(run);
            }
            for (int index = 0; index + 2 <= run.length(); index++) {
                tokens.add(run.substring(index, index + 2));
            }
        }

        Matcher words = WORD_RUN.matcher(text);
        while (words.find()) {
            tokens.add(words.group());
        }
        return tokens;
    }

    private int count(String text, String token) {
        int count = 0;
        int index = 0;
        while (count < 8 && (index = text.indexOf(token, index)) >= 0) {
            count++;
            index += Math.max(1, token.length());
        }
        return count;
    }

    private String compact(String text, int maxChars) {
        String cleaned = Objects.requireNonNullElse(text, "")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.length() <= maxChars
                ? cleaned
                : cleaned.substring(0, maxChars) + "...";
    }

    public record SessionDocument(
            Long id,
            String name,
            AttachmentKind kind,
            String summary,
            long sizeBytes
    ) {
    }

    public record SearchHit(
            Long chunkId,
            Long attachmentId,
            String documentName,
            String locator,
            String title,
            int score,
            String preview
    ) {
    }

    public record ReadChunk(
            Long chunkId,
            Long attachmentId,
            String locator,
            String content
    ) {
    }

    private record ScoredChunk(DocumentChunk chunk, int score) {
    }
}
