package com.example.sqlagent.attachment.document;

import com.example.sqlagent.attachment.AttachmentProperties;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class DocumentParserTest {
    private AttachmentProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AttachmentProperties();
        properties.setMaxDocumentUnitChars(4_000);
        properties.setMaxDocumentUnits(100);
    }

    @Test
    void extractsPdfPagesAndManifest() throws Exception {
        byte[] pdf = createPdf("Database permissions require a read-only account.");

        ParsedDocument document = new PdfDocumentParser(properties)
                .parse(pdf, "permissions.pdf");

        assertEquals(DocumentFormat.PDF, document.manifest().format());
        assertTrue(document.manifest().summary().contains("共 1 页"));
        assertFalse(document.units().isEmpty());
        assertTrue(document.units().get(0).text().contains("read-only"));
        assertEquals("1", document.units().get(0).metadata().get("page"));
    }

    @Test
    void extractsDocxParagraphsOutlineAndTables() throws Exception {
        byte[] docx = createDocx();

        ParsedDocument document = new WordDocumentParser(properties)
                .parse(docx, "rules.docx");

        assertEquals(DocumentFormat.WORD, document.manifest().format());
        assertTrue(document.manifest().summary().contains("1 个表格"));
        assertTrue(document.manifest().statistics().get("outline")
                .toString().contains("数据库规范"));
        assertTrue(document.units().stream()
                .anyMatch(unit -> unit.text().contains("学生表包含学号和姓名")));
        assertTrue(document.units().stream()
                .anyMatch(unit -> unit.text().contains("student_id")));
    }

    @Test
    void extractsExcelSheetHeadersRowsAndFormulas() throws Exception {
        byte[] xlsx = createXlsx();

        ParsedDocument document = new ExcelDocumentParser(properties)
                .parse(xlsx, "scores.xlsx");

        assertEquals(DocumentFormat.EXCEL, document.manifest().format());
        assertTrue(document.manifest().summary().contains("成绩"));
        assertFalse(document.units().isEmpty());
        assertTrue(document.units().get(0).text().contains("学号 | 姓名 | 成绩"));
        assertTrue(document.units().get(0).text().contains("A001 | 张三 | 92"));
        assertTrue(document.units().get(0).text().contains("=SUM(C2:C2)"));
    }

    private byte[] createPdf(String content) throws Exception {
        try (
                PDDocument document = new PDDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()
        ) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(
                        new PDType1Font(Standard14Fonts.FontName.HELVETICA),
                        12
                );
                stream.newLineAtOffset(72, 720);
                stream.showText(content);
                stream.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private byte[] createDocx() throws Exception {
        try (
                XWPFDocument document = new XWPFDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()
        ) {
            XWPFParagraph heading = document.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("数据库规范");

            XWPFParagraph body = document.createParagraph();
            body.createRun().setText("学生表包含学号和姓名。");

            XWPFTable table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("字段");
            table.getRow(0).getCell(1).setText("说明");
            table.getRow(1).getCell(0).setText("student_id");
            table.getRow(1).getCell(1).setText("学生编号");

            document.write(output);
            return output.toByteArray();
        }
    }

    private byte[] createXlsx() throws Exception {
        try (
                XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()
        ) {
            var sheet = workbook.createSheet("成绩");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("学号");
            header.createCell(1).setCellValue("姓名");
            header.createCell(2).setCellValue("成绩");

            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("A001");
            row.createCell(1).setCellValue("张三");
            row.createCell(2).setCellValue(92);

            var formulaRow = sheet.createRow(2);
            formulaRow.createCell(0).setCellValue("A002");
            formulaRow.createCell(1).setCellValue("李四");
            formulaRow.createCell(2).setCellFormula("SUM(C2:C2)");

            workbook.write(output);
            return output.toByteArray();
        }
    }
}
