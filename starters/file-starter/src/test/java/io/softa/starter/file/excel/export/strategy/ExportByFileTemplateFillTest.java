package io.softa.starter.file.excel.export.strategy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
        byte[] template = template("Data", "{{ name }}", "{{ deptId.name }}", "ID-{{ code }}");

        try (Workbook result = fill(template, null)) {
            assertEquals(1, result.getNumberOfSheets());
            Sheet sheet = result.getSheet("Data");
            assertEquals(List.of("Name", "Department", "Code"), texts(sheet.getRow(0)));
            assertEquals(List.of("Alice", "HR", "ID-1"), texts(sheet.getRow(1)));
            assertEquals(List.of("Bob", "IT", "ID-2"), texts(sheet.getRow(2)));
        }
    }

    @Test
    void rowsGoIntoTheTemplatesOwnSheetWhateverItIsCalled() throws Exception {
        // No sheet name configured, or one the template does not have: the template's first sheet.
        for (String sheetName : new String[] {null, "Employees"}) {
            try (Workbook result = fill(template("Sheet1", "{{ name }}", "{{ deptId.name }}", "{{ code }}"), sheetName)) {
                assertEquals(1, result.getNumberOfSheets(), "no extra sheet for " + sheetName);
                assertEquals(List.of("Alice", "HR", "1"), texts(result.getSheet("Sheet1").getRow(1)));
            }
        }
    }

    @Test
    void aConfiguredSheetNameTheTemplateHasIsTheOneFilled() throws Exception {
        byte[] template;
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.createSheet("Notes").createRow(0).createCell(0).setCellValue("Read me");
            Sheet data = workbook.createSheet("Data");
            data.createRow(0).createCell(0).setCellValue("Name");
            data.createRow(1).createCell(0).setCellValue("{{ name }}");
            workbook.write(out);
            template = out.toByteArray();
        }
        try (Workbook result = fill(template, "Data")) {
            assertEquals(2, result.getNumberOfSheets());
            assertEquals("Read me", result.getSheet("Notes").getRow(0).getCell(0).getStringCellValue());
            assertEquals("Alice", result.getSheet("Data").getRow(1).getCell(0).getStringCellValue());
            assertEquals("Bob", result.getSheet("Data").getRow(2).getCell(0).getStringCellValue());
        }
    }

    private static byte[] template(String sheetName, String... placeholders) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(sheetName);
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Name");
            header.createCell(1).setCellValue("Department");
            header.createCell(2).setCellValue("Code");
            Row row = sheet.createRow(1);
            for (int i = 0; i < placeholders.length; i++) {
                row.createCell(i).setCellValue(placeholders[i]);
            }
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /** Fills two rows the way the export does: the prepared template, its chosen sheet, the rows as a list. */
    private static Workbook fill(byte[] template, String sheetName) throws Exception {
        List<Map<String, Object>> rows = List.of(row("Alice", "HR", "1"), row("Bob", "IT", "2"));
        ByteArrayOutputStream filled = new ByteArrayOutputStream();
        try (ExportByFileTemplate.PreparedTemplate prepared =
                     ExportByFileTemplate.prepareTemplate(new ByteArrayInputStream(template), sheetName);
             ExcelWriter writer = FesodSheet.write(filled).withTemplate(prepared.stream()).build()) {
            writer.fill(rows, FesodSheet.writerSheet(prepared.sheetNo()).build());
        }
        return new XSSFWorkbook(new ByteArrayInputStream(filled.toByteArray()));
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
