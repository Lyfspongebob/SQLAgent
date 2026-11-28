package com.example.sqlagent.attachment;

import com.example.sqlagent.error.ErrorCode;
import com.example.sqlagent.error.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Component
public class LocalAttachmentStorage implements AttachmentStorage {
    private static final Logger log =
            LoggerFactory.getLogger(LocalAttachmentStorage.class);

    private final Path root;

    public LocalAttachmentStorage(AttachmentProperties properties) {
        this.root = Path.of(properties.getStorageDir()).toAbsolutePath().normalize();
    }

    @Override
    public void write(String storageKey, byte[] content) {
        Path target = resolve(storageKey);
        try {
            Files.createDirectories(target.getParent());
            Files.write(
                    target,
                    content,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        } catch (IOException exception) {
            throw storageFailure("保存附件失败", exception);
        }
    }

    @Override
    public byte[] read(String storageKey) {
        try {
            return Files.readAllBytes(resolve(storageKey));
        } catch (IOException exception) {
            throw storageFailure("读取附件失败", exception);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException exception) {
            log.warn("删除附件文件失败: storageKey={}", storageKey, exception);
        }
    }

    private Path resolve(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw storageFailure("附件存储路径为空", null);
        }

        Path target = root.resolve(storageKey).normalize();
        if (!target.startsWith(root)) {
            throw storageFailure("附件存储路径越界", null);
        }
        return target;
    }

    private ValidationException storageFailure(String message, Throwable cause) {
        return new ValidationException(
                ErrorCode.ATTACHMENT_STORAGE_FAILED,
                message,
                null,
                cause
        );
    }
}
