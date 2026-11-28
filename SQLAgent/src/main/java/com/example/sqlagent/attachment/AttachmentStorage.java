package com.example.sqlagent.attachment;

public interface AttachmentStorage {
    void write(String storageKey, byte[] content);

    byte[] read(String storageKey);

    void delete(String storageKey);
}
