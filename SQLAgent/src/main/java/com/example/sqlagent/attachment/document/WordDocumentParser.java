package com.example.sqlagent.attachment.document;

import com.example.sqlagent.attachment.AttachmentKind;
import com.example.sqlagent.attachment.AttachmentProperties;
import com.example.sqlagent.error.ErrorCode;
import com.example.sqlagent.error.ValidationException;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.*;

@Component
public class WordDocumentParser implements DocumentParser {
    private final AttachmentProperties properties;

    public WordDocumentParser(AttachmentProperties properties) {
        this.properties = properties;
    }

    @Override
    public AttachmentKind kind() {
        return AttachmentKind.WORD;
    }

    @Override
    public ParsedDocument parse(byte[] bytes, String originalName) {
        String lowerName = Objects.requireNonNullElse(originalName, "")
                .toLowerCase(Locale.ROOT);
        return lowerName.endsWith(".doc")
                ? parseLegacyDoc(bytes, originalName)
                : parseDocx(bytes, originalName);
    }

    private ParsedDocument parseDocx(byte[] bytes, String originalName) {
        try (XWPFDocument document = new XWPFDocument(
                new ByteArrayInputStream(bytes)
        )) {
            SectionBuilder sections = new SectionBuilder();
            int paragraphCount = 0;
            int tableCount = 0;
            int characterCount = 0;
            int paragraphLimit = Math.max(1, properties.getMaxWordParagraphs());

            for (IBodyElement element : document.getBodyElements()) {
                if (paragraphCount >= paragraphLimit) {
                    break;
                }

                if (element instanceof XWPFParagraph paragraph) {
                    String text = DocumentParserSupport.clean(paragraph.getText());
                    if (text.isBlank()) {
                        continue;
                    }
                    paragraphCount++;
                    characterCount += text.length();

                    String style = Objects.requireNonNullElse(
                            paragraph.getStyle(),
                            ""
                    );
                    boolean heading = isHeading(style);
                    if (heading) {
                        sections.addHeading(text);
                    } else {
                        sections.addParagraph(text);
                    }
                } else if (element instanceof XWPFTable table) {
                    tableCount++;
                    String tableText = tableText(table);
                    characterCount += tableText.length();
                    sections.addTable(tableCount, tableText);
                }
            }

            List<DocumentUnit> units = sections.buildUnits(
                    Math.max(1, properties.getMaxDocumentUnits()),
                    Math.max(1_000, properties.getMaxDocumentUnitChars())
            );
            boolean truncated = paragraphCount >= paragraphLimit;

            Map<String, Object> statistics = new LinkedHashMap<>();
            statistics.put("paragraphs", paragraphCount);
            statistics.put("tables", tableCount);
            statistics.put("headings", sections.headings.size());
            statistics.put("images", document.getAllPictures().size());
            statistics.put("characters", characterCount);
            statistics.put("truncated", truncated);

            String headings = sections.headings.stream()
                    .limit(12)
                    .reduce((left, right) -> left + " / " + right)
                    .orElse("未识别到标题");
            statistics.put("outline", headings);

            String summary = "Word 文档《%s》：%d 个段落，%d 个表格，%d 张图片，约 %d 字符；目录：%s。"
                    .formatted(
                            originalName,
                            paragraphCount,
                            tableCount,
                            document.getAllPictures().size(),
                            characterCount,
                            DocumentParserSupport.singleLine(headings, 600)
                    );
            if (truncated) {
                summary += " 已达到段落解析上限，后续内容未纳入解析产物。";
            }

            return new ParsedDocument(
                    DocumentParserSupport.PARSER_VERSION,
                    new DocumentManifest(DocumentFormat.WORD, summary, statistics),
                    units
            );
        } catch (Exception exception) {
            throw invalid("Word 文档解析失败，文件可能已损坏或受密码保护", exception);
        }
    }

    private ParsedDocument parseLegacyDoc(byte[] bytes, String originalName) {
        try (HWPFDocument document = new HWPFDocument(
                new ByteArrayInputStream(bytes)
        )) {
            Range range = document.getRange();
            int paragraphLimit = Math.max(1, properties.getMaxWordParagraphs());
            int paragraphs = Math.min(range.numParagraphs(), paragraphLimit);
            int tables = 0;
            boolean previousInTable = false;
            List<String> lines = new ArrayList<>();
            int characters = 0;

            for (int index = 0; index < paragraphs; index++) {
                org.apache.poi.hwpf.usermodel.Paragraph paragraph =
                        range.getParagraph(index);
                if (paragraph.isInTable() && !previousInTable) {
                    tables++;
                }
                previousInTable = paragraph.isInTable();
                String text = DocumentParserSupport.clean(
                        paragraph.text()
                );
                if (text.isBlank()) {
                    continue;
                }
                lines.add(text);
                characters += text.length();
            }

            SectionBuilder sections = new SectionBuilder();
            lines.forEach(sections::addParagraph);
            List<DocumentUnit> units = sections.buildUnits(
                    Math.max(1, properties.getMaxDocumentUnits()),
                    Math.max(1_000, properties.getMaxDocumentUnitChars())
            );
            boolean truncated = range.numParagraphs() > paragraphs;

            Map<String, Object> statistics = new LinkedHashMap<>();
            statistics.put("legacyFormat", true);
            statistics.put("paragraphs", paragraphs);
            statistics.put("tables", tables);
            statistics.put("headings", 0);
            statistics.put("characters", characters);
            statistics.put("truncated", truncated);

            String summary = "Word 97-2003 文档《%s》：%d 个段落，%d 个表格，约 %d 字符。"
                    .formatted(originalName, paragraphs, tables, characters);
            if (truncated) {
                summary += " 已达到段落解析上限。";
            }

            return new ParsedDocument(
                    DocumentParserSupport.PARSER_VERSION,
                    new DocumentManifest(DocumentFormat.WORD, summary, statistics),
                    units
            );
        } catch (Exception exception) {
            throw invalid("旧版 Word 文档解析失败，文件可能已损坏或受密码保护", exception);
        }
    }

    private boolean isHeading(String style) {
        String normalized = style.toLowerCase(Locale.ROOT);
        return normalized.contains("heading")
                || normalized.contains("标题")
                || normalized.matches("h[1-6]");
    }

    private String tableText(XWPFTable table) {
        StringBuilder text = new StringBuilder();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cells.add(DocumentParserSupport.singleLine(cell.getText(), 500));
            }
            text.append(String.join(" | ", cells)).append('\n');
        }
        return DocumentParserSupport.clean(text.toString());
    }

    private ValidationException invalid(String message, Throwable cause) {
        return new ValidationException(
                ErrorCode.ATTACHMENT_INVALID,
                message,
                null,
                cause
        );
    }

    private static class SectionBuilder {
        private final List<String> headings = new ArrayList<>();
        private final List<Section> sections = new ArrayList<>();
        private String currentHeading = "正文";
        private final StringBuilder currentText = new StringBuilder();

        void addHeading(String heading) {
            flush();
            currentHeading = DocumentParserSupport.singleLine(heading, 300);
            if (!headings.contains(currentHeading)) {
                headings.add(currentHeading);
            }
        }

        void addParagraph(String paragraph) {
            currentText.append(paragraph).append("\n\n");
        }

        void addTable(int number, String tableText) {
            currentText.append("【表格 ").append(number).append("】\n")
                    .append(tableText)
                    .append("\n\n");
        }

        List<DocumentUnit> buildUnits(int maxUnits, int maxChars) {
            flush();
            List<DocumentUnit> units = new ArrayList<>();

            for (Section section : sections) {
                if (units.size() >= maxUnits) {
                    break;
                }
                List<String> chunks = DocumentParserSupport.chunks(
                        section.text(),
                        maxChars
                );
                for (int index = 0; index < chunks.size() && units.size() < maxUnits; index++) {
                    String suffix = chunks.size() > 1
                            ? "（片段 " + (index + 1) + "/" + chunks.size() + "）"
                            : "";
                    units.add(new DocumentUnit(
                            "章节「" + section.heading() + "」" + suffix,
                            "word_section",
                            section.heading(),
                            chunks.get(index),
                            Map.of("section", section.heading())
                    ));
                }
            }
            return units;
        }

        private void flush() {
            String text = DocumentParserSupport.clean(currentText.toString());
            if (!text.isBlank()) {
                sections.add(new Section(currentHeading, text));
            }
            currentText.setLength(0);
        }
    }

    private record Section(String heading, String text) {
    }
}
