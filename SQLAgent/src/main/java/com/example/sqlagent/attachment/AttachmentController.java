package com.example.sqlagent.attachment;

import com.example.sqlagent.dto.ApiResponse;
import com.example.sqlagent.dto.AttachmentResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@RestController
@RequestMapping("/api/attachments")
public class AttachmentController {
    private final AttachmentService attachmentService;

    public AttachmentController(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<AttachmentResponse>> upload(
            @RequestParam Long sessionId,
            @RequestPart("file") MultipartFile file
    ) {
        AttachmentResponse response = attachmentService.upload(sessionId, file);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{attachmentId}/content")
    public ResponseEntity<byte[]> content(@PathVariable Long attachmentId) {
        AttachmentService.AttachmentContent content =
                attachmentService.getContent(attachmentId);

        String encodedName = URLEncoder.encode(
                content.name(),
                StandardCharsets.UTF_8
        ).replace("+", "%20");

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePrivate())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename*=UTF-8''" + encodedName
                )
                .body(content.bytes());
    }

    @DeleteMapping("/{attachmentId}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long attachmentId) {
        attachmentService.deletePending(attachmentId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
