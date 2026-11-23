package io.softa.starter.file.excel.export.strategy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.fesod.sheet.ExcelWriter;
import org.apache.fesod.sheet.FesodSheet;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A file template's {@code {{ field }}} placeholders, filled the way the export fills them: the rows as a list.
 */
class ExportByFileTemplateFillTest {

    @Test
    void placeholdersBecomeFesodListPlaceholders() {
        assertEquals("{.name}", ExportByFileTemplate.normalizeCellValue("{{ name }}"));
        assertEquals("{.deptId.name}", ExportByFileTemplate.normalizeCellValue("{{deptId.name}}"));
        assertEquals("ID-{.code} / {.name}", ExportByFileTemplate.normalizeCellValue("ID-{{ code }} / {{ name }}"));
        assertEquals("Total", ExportByFileTemplate.normalizeCellValue("Total"));
    }

    @Test
    void everyRowIsFilledIncludingCascadedFieldsAndSurroundingText() throws Exception {
        byte[] template;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Data");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Name");
            header.createCell(1).setCellValue("Department");
            header.createCell(2).setCellValue("Code");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("{{ name }}");
            row.createCell(1).setCellValue("{{ deptId.name }}");
            row.createCell(2).setCellValue("ID-{{ code }}");
            workbook.write(out);
            template = out.toByteArray();
        }
        List<Map<String, Object>> rows = List.of(row("Alice", "HR", "1"), row("Bob", "IT", "2"));

        ByteArrayOutputStream filled = new ByteArrayOutputStream();
        try (InputStream normalized = ExportByFileTemplate.normalizeTemplateInputStream(new ByteArrayInputStream(template));
             ExcelWriter writer = FesodSheet.write(filled).withTemplate(normalized).build()) {
            writer.fill(rows, FesodSheet.writerSheet("Data").build());
        }

        try (Workbook result = new XSSFWorkbook(new ByteArrayInputStream(filled.toByteArray()))) {
            Sheet sheet = result.getSheet("Data");
            assertEquals(List.of("Name", "Department", "Code"), texts(sheet.getRow(0)));
            assertEquals(List.of("Alice", "HR", "ID-1"), texts(sheet.getRow(1)));
            assertEquals(List.of("Bob", "IT", "ID-2"), texts(sheet.getRow(2)));
        }
    }

    private static Map<String, Object> row(String name, String department, String code) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("deptId.name", department);
        row.put("code", code);
        return row;
    }

    private static List<String> texts(Row row) {
        DataFormatter formatter = new DataFormatter();
        return List.of(formatter.formatCellValue(row.getCell(0)), formatter.formatCellValue(row.getCell(1)),
                formatter.formatCellValue(row.getCell(2)));
    }
}
