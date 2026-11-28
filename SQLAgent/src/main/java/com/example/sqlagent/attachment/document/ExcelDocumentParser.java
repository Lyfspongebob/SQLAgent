package com.example.sqlagent.attachment.document;

import com.example.sqlagent.attachment.AttachmentKind;
import com.example.sqlagent.attachment.AttachmentProperties;
import com.example.sqlagent.error.ErrorCode;
import com.example.sqlagent.error.ValidationException;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.*;

@Component
public class ExcelDocumentParser implements DocumentParser {
    private static final int ROWS_PER_UNIT = 30;

    private final AttachmentProperties properties;

    public ExcelDocumentParser(AttachmentProperties properties) {
        this.properties = properties;
    }

    @Override
    public AttachmentKind kind() {
        return AttachmentKind.EXCEL;
    }

    @Override
    public ParsedDocument parse(byte[] bytes, String originalName) {
        try (Workbook workbook = WorkbookFactory.create(
                new ByteArrayInputStream(bytes)
        )) {
            DataFormatter formatter = new DataFormatter(Locale.CHINA);
            int sheetCount = workbook.getNumberOfSheets();
            if (sheetCount <= 0) {
                throw invalid("Excel 中没有可读取的工作表");
            }

            int maxRows = Math.max(1, properties.getMaxExcelRowsPerSheet());
            int maxColumns = Math.max(1, properties.getMaxExcelColumns());
            int maxUnits = Math.max(1, properties.getMaxDocumentUnits());
            int maxUnitChars = Math.max(1_000, properties.getMaxDocumentUnitChars());

            List<DocumentUnit> units = new ArrayList<>();
            List<Map<String, Object>> sheetSummaries = new ArrayList<>();
            int formulaCount = 0;
            int totalRows = 0;

            for (int sheetIndex = 0;
                 sheetIndex < sheetCount && units.size() < maxUnits;
                 sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                String sheetName = Objects.requireNonNullElse(
                        sheet.getSheetName(),
                        "Sheet" + (sheetIndex + 1)
                );
                int firstRow = sheet.getFirstRowNum();
                int lastRow = sheet.getLastRowNum();

                if (firstRow < 0 || lastRow < firstRow) {
                    sheetSummaries.add(Map.of(
                            "name", sheetName,
                            "rows", 0,
                            "columns", 0,
                            "hidden", workbook.isSheetHidden(sheetIndex)
                    ));
                    continue;
                }

                int headerRowIndex = findHeaderRow(sheet, formatter);
                Row headerRow = sheet.getRow(headerRowIndex);
                int columnCount = countColumns(
                        sheet,
                        headerRowIndex,
                        Math.min(lastRow, headerRowIndex + 20),
                        maxColumns
                );
                if (columnCount == 0 && headerRow != null) {
                    columnCount = Math.max(1, Math.min(
                            safeCellCount(headerRow),
                            maxColumns
                    ));
                }
                columnCount = Math.max(1, columnCount);

                List<String> headers = readHeaders(
                        headerRow,
                        columnCount,
                        formatter
                );
                int parsedLastRow = Math.min(
                        lastRow,
                        headerRowIndex + maxRows
                );
                int dataRows = Math.max(0, parsedLastRow - headerRowIndex);
                totalRows += dataRows;

                Map<String, Object> sheetSummary = new LinkedHashMap<>();
                sheetSummary.put("name", sheetName);
                sheetSummary.put("rows", dataRows);
                sheetSummary.put("columns", columnCount);
                sheetSummary.put("headerRow", headerRowIndex + 1);
                sheetSummary.put("headers", headers);
                sheetSummary.put(
                        "hidden",
                        workbook.isSheetHidden(sheetIndex)
                                || workbook.isSheetVeryHidden(sheetIndex)
                );
                sheetSummary.put(
                        "truncated",
                        lastRow > parsedLastRow
                );
                sheetSummaries.add(sheetSummary);

                for (int start = headerRowIndex + 1;
                     start <= parsedLastRow && units.size() < maxUnits;
                     start += ROWS_PER_UNIT) {
                    int end = Math.min(parsedLastRow, start + ROWS_PER_UNIT - 1);
                    StringBuilder text = new StringBuilder();
                    text.append("工作表：").append(sheetName).append('\n');
                    text.append("表头：")
                            .append(String.join(" | ", headers))
                            .append('\n');

                    for (int rowIndex = start; rowIndex <= end; rowIndex++) {
                        Row row = sheet.getRow(rowIndex);
                        if (row == null) {
                            continue;
                        }
                        List<String> values = new ArrayList<>(columnCount);
                        for (int column = 0; column < columnCount; column++) {
                            Cell cell = row.getCell(column, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                            if (cell != null && cell.getCellType() == CellType.FORMULA) {
                                formulaCount++;
                            }
                            values.add(formatCell(cell, formatter));
                        }
                        text.append("第 ").append(rowIndex + 1).append(" 行：")
                                .append(String.join(" | ", values))
                                .append('\n');
                    }

                    List<String> chunks = DocumentParserSupport.chunks(
                            text.toString(),
                            maxUnitChars
                    );
                    for (int index = 0; index < chunks.size() && units.size() < maxUnits; index++) {
                        String suffix = chunks.size() > 1
                                ? "，片段 " + (index + 1) + "/" + chunks.size()
                                : "";
                        units.add(new DocumentUnit(
                                "工作表「" + sheetName + "」第 "
                                        + (start + 1) + "-" + (end + 1) + " 行"
                                        + suffix,
                                "excel_rows",
                                sheetName,
                                chunks.get(index),
                                Map.of(
                                        "sheet", sheetName,
                                        "startRow", String.valueOf(start + 1),
                                        "endRow", String.valueOf(end + 1)
                                )
                        ));
                    }
                }
            }

            Map<String, Object> statistics = new LinkedHashMap<>();
            statistics.put("sheets", sheetCount);
            statistics.put("parsedRows", totalRows);
            statistics.put("formulas", formulaCount);
            statistics.put("sheetDetails", sheetSummaries);
            statistics.put("truncated", units.size() >= maxUnits);

            String summary = "Excel 工作簿《%s》：%d 个工作表，已解析 %d 行；工作表：%s。"
                    .formatted(
                            originalName,
                            sheetCount,
                            totalRows,
                            sheetSummaries.stream()
                                    .map(item -> {
                                        Object name = item.get("name");
                                        Object rows = item.get("rows");
                                        Object columns = item.get("columns");
                                        return name + "(" + rows + "行×"
                                                + columns + "列)";
                                    })
                                    .reduce((left, right) -> left + "、" + right)
                                    .orElse("无")
                    );

            return new ParsedDocument(
                    DocumentParserSupport.PARSER_VERSION,
                    new DocumentManifest(DocumentFormat.EXCEL, summary, statistics),
                    units
            );
        } catch (ValidationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("Excel 文件解析失败，文件可能已损坏或受密码保护", exception);
        }
    }

    private int findHeaderRow(Sheet sheet, DataFormatter formatter) {
        int first = sheet.getFirstRowNum();
        int last = Math.min(sheet.getLastRowNum(), first + 9);
        int bestRow = first;
        int bestScore = -1;

        for (int rowIndex = first; rowIndex <= last; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            int nonBlank = 0;
            for (Cell cell : row) {
                if (!formatCell(cell, formatter).isBlank()) {
                    nonBlank++;
                }
            }
            if (nonBlank > bestScore) {
                bestScore = nonBlank;
                bestRow = rowIndex;
            }
        }
        return bestRow;
    }

    private int countColumns(
            Sheet sheet,
            int headerRow,
            int lastInspectionRow,
            int maxColumns
    ) {
        int columns = 0;
        for (int rowIndex = headerRow; rowIndex <= lastInspectionRow; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row != null) {
                columns = Math.max(columns, safeCellCount(row));
            }
        }
        return Math.min(columns, maxColumns);
    }

    private int safeCellCount(Row row) {
        return Math.max(0, row.getLastCellNum());
    }

    private List<String> readHeaders(
            Row headerRow,
            int columnCount,
            DataFormatter formatter
    ) {
        List<String> headers = new ArrayList<>(columnCount);
        for (int column = 0; column < columnCount; column++) {
            Cell cell = headerRow == null
                    ? null
                    : headerRow.getCell(
                            column,
                            Row.MissingCellPolicy.RETURN_BLANK_AS_NULL
                    );
            String header = formatCell(cell, formatter);
            headers.add(header.isBlank() ? "列" + (column + 1) : header);
        }
        return headers;
    }

    private String formatCell(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }

        String value = formatter.formatCellValue(cell);
        if (cell.getCellType() == CellType.FORMULA) {
            String formula = "=" + cell.getCellFormula();
            return value.isBlank() ? formula : formula + " => " + value;
        }
        return DocumentParserSupport.singleLine(value, 500);
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
