package com.example.sqlagent.attachment;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "attachments")
public class AttachmentProperties {
    private String storageDir = "./data/attachments";
    private long maxFileSizeBytes = 5 * 1024 * 1024L;
    private long maxDocumentSizeBytes = 20 * 1024 * 1024L;
    private long maxTotalSizeBytes = 30 * 1024 * 1024L;
    private long maxTotalImageBytes = 12 * 1024 * 1024L;
    private int maxFilesPerMessage = 4;
    private int maxTextChars = 20_000;
    private int maxTotalTextChars = 40_000;

    private int maxPdfPages = 300;
    private int maxWordParagraphs = 20_000;
    private int maxExcelRowsPerSheet = 5_000;
    private int maxExcelColumns = 60;
    private int maxDocumentUnits = 500;
    private int maxDocumentUnitChars = 6_000;
    private int maxDocumentContextChars = 16_000;
    private int maxContextCharsPerDocument = 10_000;
    private int maxContextUnitsPerDocument = 4;
}
