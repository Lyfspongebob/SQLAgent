package com.example.sqlagent.attachment.document;

import com.example.sqlagent.attachment.AttachmentKind;
import com.example.sqlagent.attachment.AttachmentProperties;
import com.example.sqlagent.error.ErrorCode;
import com.example.sqlagent.error.ValidationException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class PdfDocumentParser implements DocumentParser {
    private final AttachmentProperties properties;

    public PdfDocumentParser(AttachmentProperties properties) {
        this.properties = properties;
    }

    @Override
    public AttachmentKind kind() {
        return AttachmentKind.PDF;
    }

    @Override
    public ParsedDocument parse(byte[] bytes, String originalName) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            int totalPages = document.getNumberOfPages();
            if (totalPages <= 0) {
                throw invalid("PDF 中没有可读取的页面");
            }

            int pageLimit = Math.min(
                    totalPages,
                    Math.max(1, properties.getMaxPdfPages())
            );
            int unitLimit = Math.max(1, properties.getMaxDocumentUnits());
            int unitCharLimit = Math.max(1_000, properties.getMaxDocumentUnitChars());

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            List<DocumentUnit> units = new ArrayList<>();
            List<String> pagePreviews = new ArrayList<>();
            int textPages = 0;
            int scannedPages = 0;
            int totalChars = 0;

            for (int page = 1; page <= pageLimit && units.size() < unitLimit; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = DocumentParserSupport.clean(stripper.getText(document));

                if (pageText.length() < 20) {
                    scannedPages++;
                    continue;
                }

                textPages++;
                totalChars += pageText.length();
                if (pagePreviews.size() < 10) {
                    pagePreviews.add(
                            "第" + page + "页："
                                    + DocumentParserSupport.singleLine(pageText, 80)
                    );
                }
                List<String> chunks = DocumentParserSupport.chunks(
                        pageText,
                        unitCharLimit
                );
                for (int index = 0; index < chunks.size() && units.size() < unitLimit; index++) {
                    String suffix = chunks.size() > 1
                            ? "（片段 " + (index + 1) + "/" + chunks.size() + "）"
                            : "";
                    units.add(new DocumentUnit(
                            "第 " + page + " 页" + suffix,
                            "pdf_page",
                            "第 " + page + " 页",
                            chunks.get(index),
                            Map.of("page", String.valueOf(page))
                    ));
                }
            }

            Map<String, Object> statistics = new LinkedHashMap<>();
            statistics.put("pages", totalPages);
            statistics.put("parsedPages", pageLimit);
            statistics.put("textPages", textPages);
            statistics.put("scannedPages", scannedPages);
            statistics.put("characters", totalChars);
            statistics.put("pagePreviews", pagePreviews);
            statistics.put("truncated", totalPages > pageLimit || units.size() >= unitLimit);

            PDDocumentInformation information = document.getDocumentInformation();
            putIfPresent(statistics, "title", information.getTitle());
            putIfPresent(statistics, "author", information.getAuthor());

            String summary = "PDF 文档《%s》：共 %d 页，已解析 %d 页；"
                    .formatted(originalName, totalPages, pageLimit)
                    + "%d 页提取到文本，%d 页疑似扫描页，文本约 %d 字符。"
                    .formatted(textPages, scannedPages, totalChars);
            if (totalPages > pageLimit) {
                summary += " 已达到解析页数上限，后续页面未生成内容单元。";
            }
            if (!pagePreviews.isEmpty()) {
                summary += " 页首预览：" + String.join("；", pagePreviews);
            }

            return new ParsedDocument(
                    DocumentParserSupport.PARSER_VERSION,
                    new DocumentManifest(
                            DocumentFormat.PDF,
                            summary,
                            statistics
                    ),
                    units
            );
        } catch (InvalidPasswordException exception) {
            throw invalid("PDF 已加密，当前不支持解析密码保护文档", exception);
        } catch (ValidationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("PDF 解析失败，文件可能已损坏", exception);
        }
    }

    private void putIfPresent(
            Map<String, Object> target,
            String key,
            String value
    ) {
        String cleaned = DocumentParserSupport.singleLine(value, 300);
        if (!cleaned.isBlank()) {
            target.put(key, cleaned);
        }
    }

    private ValidationException invalid(String message) {
        return new ValidationException(ErrorCode.ATTACHMENT_INVALID, message);
    }

    private ValidationException invalid(String message, Throwable cause) {
        return new ValidationException(
                ErrorCode.ATTACHMENT_INVALID,
                message,
                null,
                cause
        );
    }
}
