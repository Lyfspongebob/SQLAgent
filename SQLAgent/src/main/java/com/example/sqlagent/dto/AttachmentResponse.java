package com.example.sqlagent.dto;

import com.example.sqlagent.attachment.AttachmentKind;
import com.example.sqlagent.attachment.ChatAttachment;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AttachmentResponse {
    private Long id;
    private String name;
    private String contentType;
    private long sizeBytes;
    private AttachmentKind kind;
    private LocalDateTime createTime;

    public static AttachmentResponse from(ChatAttachment attachment) {
        return AttachmentResponse.builder()
                .id(attachment.getId())
                .name(attachment.getOriginalName())
                .contentType(attachment.getContentType())
                .sizeBytes(attachment.getSizeBytes())
                .kind(attachment.getKind())
                .createTime(attachment.getCreateTime())
                .build();
    }
}
