package com.example.sqlagent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChatRequest {
    @NotBlank(message = "消息不能为空")
    @Size(max = 4000, message = "消息不能超过 4000 个字符")
    private String message;

    @NotNull(message = "会话 ID 不能为空")
    private Long sessionId;
    private Boolean useKnowledgeBase;
    private Boolean useSqlAgent;
    private Long dataSourceConfigId;
}
