package com.example.sqlagent.attachment;

/**
 * 一次模型调用需要携带的附件内容。
 * 文件内容只在请求处理期间驻留内存，不写入模型请求日志。
 */
public record AttachmentPayload(
        String name,
        String contentType,
        AttachmentKind kind,
        byte[] bytes,
        String textContent
) {
    public boolean isImage() {
        return kind == AttachmentKind.IMAGE;
    }
}
