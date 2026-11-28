package com.example.sqlagent.chat;

import cn.dev33.satoken.stp.StpUtil;
import com.example.sqlagent.agent.AgentRunner;
import com.example.sqlagent.attachment.AttachmentService;
import com.example.sqlagent.attachment.ChatAttachment;
import com.example.sqlagent.dto.AgentStepResponse;
import com.example.sqlagent.dto.ChatRequest;
import com.example.sqlagent.dto.ChatResponse;
import com.example.sqlagent.dto.ChatMessageResponse;
import com.example.sqlagent.dto.AttachmentResponse;
import com.example.sqlagent.error.ErrorCode;
import com.example.sqlagent.error.ForbiddenException;
import com.example.sqlagent.error.NotFoundException;
import com.example.sqlagent.error.ValidationException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

@Service
public class ChatService {
    private static final Logger log =
            LoggerFactory.getLogger(ChatService.class);

    @Autowired
    private ChatMessageRepository chatMessageRepository;

    @Autowired
    private ChatSessionRepository chatSessionRepository;

    @Autowired
    private AgentRunner agentRunner;

    @Autowired
    private ChatResultCodec chatResultCodec;

    @Autowired
    private AttachmentService attachmentService;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 发送消息并获取回复
     */
    public ChatResponse sendMessage(ChatRequest request) {
        long userId = StpUtil.getLoginIdAsLong();
        return sendMessageForUser(userId, request, null);
    }

    public ChatResponse sendMessageForUser(
            long userId,
            ChatRequest request,
            Consumer<AgentStepResponse> stepListener
    ) {
        requireOwnedSession(userId, request.getSessionId());

        String message = request.getMessage() == null
                ? ""
                : request.getMessage().trim();
        List<Long> attachmentIds = request.getAttachmentIds() == null
                ? List.of()
                : request.getAttachmentIds().stream()
                        .filter(Objects::nonNull)
                        .distinct()
                        .toList();

        if (message.isBlank() && attachmentIds.isEmpty()) {
            throw new ValidationException(
                    ErrorCode.REQUEST_VALIDATION_FAILED,
                    "消息和附件不能同时为空"
            );
        }

        String prompt = message.isBlank()
                ? "请分析上传的附件，并说明关键信息。"
                : message;

        if (Boolean.TRUE.equals(request.getUseSqlAgent())
                && request.getDataSourceConfigId() == null) {
            throw new ValidationException(
                    ErrorCode.REQUEST_VALIDATION_FAILED,
                    "SQL Agent 模式必须选择数据源"
            );
        }

        AttachmentService.LoadedAttachments attachments =
                attachmentService.loadForAgentMessage(
                        userId,
                        request.getSessionId(),
                        attachmentIds
                );
        List<String> conversationHistory = recentConversation(
                userId,
                request.getSessionId()
        );

        // 先处理消息（调用 SQL Agent 或 AI），避免 JPA 连接被 OSIV 占用
        ChatResponse response = agentRunner.run(
                userId,
                request.getSessionId(),
                prompt,
                Boolean.TRUE.equals(request.getUseKnowledgeBase()),
                Boolean.TRUE.equals(request.getUseSqlAgent()),
                request.getDataSourceConfigId(),
                attachments.payloads(),
                conversationHistory,
                stepListener
        );

        // 保存用户消息
        ChatMessage userMessage = new ChatMessage();
        userMessage.setUserId(userId);
        userMessage.setSessionId(request.getSessionId());
        userMessage.setContent(prompt);
        userMessage.setRole("user");
        userMessage = chatMessageRepository.save(userMessage);
        attachmentService.attachToMessage(
                userId,
                attachments.entities(),
                userMessage.getId()
        );

        // 如果是该会话的第一条消息，自动更新会话标题
        List<ChatMessage> existingMessages = chatMessageRepository
                .findByUserIdAndSessionIdOrderByCreateTimeAsc(userId, request.getSessionId());
        if (existingMessages.size() == 1) {
            // 只有刚插入的这一条消息，说明是首条消息
            String titleSource = titleSource(message, attachments.entities());
            String title = titleSource.length() > 30
                    ? titleSource.substring(0, 30) + "..."
                    : titleSource;
            chatSessionRepository.findById(request.getSessionId()).ifPresent(session -> {
                session.setTitle(title);
                session.setUpdateTime(java.time.LocalDateTime.now());
                chatSessionRepository.save(session);
            });
        }

        // 保存 AI 回复
        ChatMessage assistantMessage = new ChatMessage();
        assistantMessage.setUserId(userId);
        assistantMessage.setSessionId(request.getSessionId());
        assistantMessage.setContent(response.getReply());
        assistantMessage.setRole("assistant");
        assistantMessage.setSqlGenerated(response.getSqlGenerated());
        assistantMessage.setSqlResults(chatResultCodec.serialize(response.getSqlResults()));
        assistantMessage.setSqlSuccess(response.getSqlExecutionSuccess());
        assistantMessage.setFromKnowledgeBase(response.getFromKnowledgeBase());
        assistantMessage.setAgentTrace(serializeAgentSteps(
                response.getAgentSteps()
        ));
        chatMessageRepository.save(assistantMessage);

        // 更新会话的更新时间
        chatSessionRepository.findById(request.getSessionId()).ifPresent(session -> {
            session.setUpdateTime(java.time.LocalDateTime.now());
            chatSessionRepository.save(session);
        });

        return response;
    }

    /**
     * 获取会话历史
     */
    public List<ChatMessageResponse> getSessionHistory(Long sessionId) {
        long userId = StpUtil.getLoginIdAsLong();
        requireOwnedSession(userId, sessionId);

        List<ChatMessage> messages = chatMessageRepository
                .findByUserIdAndSessionIdOrderByCreateTimeAsc(userId, sessionId);
        Map<Long, List<AttachmentResponse>> attachments =
                attachmentService.responsesByMessage(
                        userId,
                        messages.stream()
                                .map(ChatMessage::getId)
                                .filter(Objects::nonNull)
                                .toList()
                );

        return messages
                .stream()
                .map(message -> toResponse(
                        message,
                        attachments.getOrDefault(message.getId(), List.of())
                ))
                .toList();
    }

    private ChatMessageResponse toResponse(
            ChatMessage message,
            List<AttachmentResponse> attachments
    ) {
        ChatResultCodec.DecodedSqlResults decoded =
                chatResultCodec.deserialize(message.getSqlResults());

        return ChatMessageResponse.builder()
                .id(message.getId())
                .sessionId(message.getSessionId())
                .role(message.getRole())
                .content(message.getContent())
                .sqlGenerated(message.getSqlGenerated())
                .sqlResults(decoded.rows())
                .sqlResultText(decoded.rawText())
                .sqlResultFormat(decoded.format())
                .sqlExecutionSuccess(message.getSqlSuccess())
                .fromKnowledgeBase(message.getFromKnowledgeBase())
                .attachments(attachments)
                .agentSteps(deserializeAgentSteps(message.getAgentTrace()))
                .createTime(message.getCreateTime())
                .build();
    }

    private String serializeAgentSteps(List<AgentStepResponse> steps) {
        if (steps == null || steps.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(steps);
        } catch (Exception exception) {
            log.warn(
                    "Agent 工作流序列化失败，最终回答仍将继续保存",
                    exception
            );
            return null;
        }
    }

    private List<AgentStepResponse> deserializeAgentSteps(String trace) {
        if (trace == null || trace.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(
                    trace,
                    new TypeReference<List<AgentStepResponse>>() {
                    }
            );
        } catch (Exception ignored) {
            return List.of();
        }
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

    private String titleSource(String message, List<ChatAttachment> attachments) {
        if (message != null && !message.isBlank()) {
            return message;
        }
        if (attachments != null && !attachments.isEmpty()) {
            return attachments.get(0).getOriginalName();
        }
        return "新对话";
    }

    private List<String> recentConversation(long userId, Long sessionId) {
        List<ChatMessage> messages = chatMessageRepository
                .findByUserIdAndSessionIdOrderByCreateTimeAsc(userId, sessionId);
        int start = Math.max(0, messages.size() - 8);
        return messages.subList(start, messages.size()).stream()
                .map(message -> {
                    String content = Objects.requireNonNullElse(
                            message.getContent(),
                            ""
                    ).replaceAll("\\s+", " ").trim();
                    if (content.length() > 1_000) {
                        content = content.substring(0, 1_000) + "...";
                    }
                    String sql = message.getSqlGenerated() == null
                            ? ""
                            : " [曾生成SQL: "
                            + message.getSqlGenerated()
                                    .replaceAll("\\s+", " ")
                                    .substring(
                                            0,
                                            Math.min(
                                                    300,
                                                    message.getSqlGenerated()
                                                            .replaceAll("\\s+", " ")
                                                            .length()
                                            )
                                    )
                            + "]";
                    return message.getRole() + ": " + content + sql;
                })
                .toList();
    }
}
