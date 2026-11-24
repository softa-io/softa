package io.softa.starter.file.excel.export.strategy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.apache.fesod.sheet.ExcelWriter;
import org.apache.fesod.sheet.FesodSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.exception.BusinessException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A file template's {@code {{ field }}} placeholders, filled the way the export fills them: the rows as a list, each
 * record on a row of its own.
 */
class ExportByFileTemplateFillTest {

    @Test
    void placeholdersBecomeFesodListPlaceholders() {
        assertEquals("{.name}", ExportByFileTemplate.normalizeCellValue("{{ name }}"));
        assertEquals("{.deptId.name}", ExportByFileTemplate.normalizeCellValue("{{deptId.name}}"));
        assertEquals("ID-{.code} / {.name}", ExportByFileTemplate.normalizeCellValue("ID-{{ code }} / {{ name }}"));
        assertEquals("Total", ExportByFileTemplate.normalizeCellValue("Total"));
        // Not a field name: left alone instead of turning into {..x} or {.a..b}, for the stray-brace check to refuse.
        assertEquals("{{ .x }}", ExportByFileTemplate.normalizeCellValue("{{ .x }}"));
        assertEquals("{{a..b}}", ExportByFileTemplate.normalizeCellValue("{{a..b}}"));
        assertEquals("{{ a. }}", ExportByFileTemplate.normalizeCellValue("{{ a. }}"));
    }

    @Test
    void everyRowIsFilledIncludingCascadedFieldsAndSurroundingText() throws Exception {
        byte[] template = template(wb -> dataSheet(wb, "Data", "{{ name }}", "{{ deptId.name }}", "ID-{{ code }}"));

        try (Workbook result = fill(template, null, 2)) {
            assertEquals(1, result.getNumberOfSheets());
            Sheet sheet = result.getSheet("Data");
            assertEquals(List.of("Name", "Department", "Code"), texts(sheet.getRow(0)));
            assertEquals(List.of("Alice1", "Dept1", "ID-1"), texts(sheet.getRow(1)));
            assertEquals(List.of("Alice2", "Dept2", "ID-2"), texts(sheet.getRow(2)));
        }
    }

    @Test
    void rowsBelowTheDataRowMoveDownInsteadOfBeingWrittenOver() throws Exception {
        byte[] template = template(wb -> {
            Sheet sheet = dataSheet(wb, "Data", "{{ name }}", "{{ deptId.name }}", "{{ code }}");
            sheet.createRow(2).createCell(0).setCellValue("Total");
            sheet.createRow(3).createCell(0).setCellValue("Signed by");
        });

        try (Workbook result = fill(template, null, 4)) {
            Sheet sheet = result.getSheet("Data");
            for (int i = 1; i <= 4; i++) {
                assertEquals(List.of("Alice" + i, "Dept" + i, String.valueOf(i)), texts(sheet.getRow(i)));
            }
            assertEquals("Total", sheet.getRow(5).getCell(0).getStringCellValue());
            assertEquals("Signed by", sheet.getRow(6).getCell(0).getStringCellValue());
        }
    }

    @Test
    void rowsArePushedDownOnlyWhenTheTemplateHasRowsBelowTheDataRow() throws Exception {
        // Nothing below the data row: the plain, streaming fill.
        byte[] plain = template(wb -> dataSheet(wb, "Data", "{{ name }}", "{{ deptId.name }}", "{{ code }}"));
        ExportByFileTemplate.PreparedTemplate prepared =
                ExportByFileTemplate.prepareTemplate(new ByteArrayInputStream(plain), null);
        assertFalse(prepared.rowsBelowData());
        assertEquals(Boolean.FALSE, prepared.fillConfig().getForceNewRow());
        try (Workbook result = fill(plain, null, 3)) {
            Sheet sheet = result.getSheet("Data");
            for (int i = 1; i <= 3; i++) {
                assertEquals(List.of("Alice" + i, "Dept" + i, String.valueOf(i)), texts(sheet.getRow(i)));
            }
        }

        // A footer below the data row: each record gets a row of its own and the footer moves down.
        byte[] footer = template(wb -> {
            Sheet sheet = dataSheet(wb, "Data", "{{ name }}", "{{ deptId.name }}", "{{ code }}");
            sheet.createRow(2).createCell(0).setCellValue("Total");
        });
        prepared = ExportByFileTemplate.prepareTemplate(new ByteArrayInputStream(footer), null);
        assertTrue(prepared.rowsBelowData());
        assertEquals(Boolean.TRUE, prepared.fillConfig().getForceNewRow());

        // A row below that holds only formatting still counts: the fill would write over it.
        byte[] styledOnly = template(wb -> {
            Sheet sheet = dataSheet(wb, "Data", "{{ name }}", "{{ deptId.name }}", "{{ code }}");
            sheet.createRow(5);
        });
        assertTrue(ExportByFileTemplate.prepareTemplate(new ByteArrayInputStream(styledOnly), null).rowsBelowData());
    }

    @Test
    void aMergedDataCellKeepsEveryRecord() throws Exception {
        // The name spans A:B on the data row, the code sits in C; a footer row follows.
        byte[] template = template(wb -> {
            Sheet sheet = wb.createSheet("Data");
            sheet.createRow(0).createCell(0).setCellValue("Name");
            Row row = sheet.createRow(1);
            row.createCell(0).setCellValue("{{ name }}");
            row.createCell(2).setCellValue("{{ code }}");
            sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 1));
            sheet.createRow(2).createCell(0).setCellValue("Total");
        });

        try (Workbook result = fill(template, null, 3)) {
            Sheet sheet = result.getSheet("Data");
            for (int i = 1; i <= 3; i++) {
                assertEquals("Alice" + i, sheet.getRow(i).getCell(0).getStringCellValue());
                assertEquals(String.valueOf(i), new DataFormatter().formatCellValue(sheet.getRow(i).getCell(2)));
            }
            assertEquals("Total", sheet.getRow(4).getCell(0).getStringCellValue());
        }
    }

    @Test
    void withNoSheetNameTheFirstSheetWithPlaceholdersIsFilled() throws Exception {
        byte[] template = template(wb -> {
            wb.createSheet("Notes").createRow(0).createCell(0).setCellValue("Read me");
            dataSheet(wb, "Data", "{{ name }}", "{{ deptId.name }}", "{{ code }}");
        });

        try (Workbook result = fill(template, null, 2)) {
            assertEquals(2, result.getNumberOfSheets());
            assertEquals("Read me", result.getSheet("Notes").getRow(0).getCell(0).getStringCellValue());
            assertEquals(List.of("Alice1", "Dept1", "1"), texts(result.getSheet("Data").getRow(1)));
            assertEquals(List.of("Alice2", "Dept2", "2"), texts(result.getSheet("Data").getRow(2)));
        }
    }

    @Test
    void aConfiguredSheetIsFilledAndTheOtherSheetsAreLeftAsTheyAre() throws Exception {
        byte[] template = template(wb -> {
            wb.createSheet("Notes").createRow(0).createCell(0).setCellValue("Use {{ remark }} syntax");
            dataSheet(wb, "Data", "{{ name }}", "{{ deptId.name }}", "{{ code }}");
        });

        ExportByFileTemplate.PreparedTemplate prepared =
                ExportByFileTemplate.prepareTemplate(new ByteArrayInputStream(template), "Data");
        assertEquals(1, prepared.sheetNo());
        assertEquals(Set.of("name", "deptId.name", "code"), prepared.variables(), "only the filled sheet is queried");

        try (Workbook result = fill(template, "Data", 2)) {
            assertEquals("Use {{ remark }} syntax", result.getSheet("Notes").getRow(0).getCell(0).getStringCellValue());
            assertEquals(List.of("Alice2", "Dept2", "2"), texts(result.getSheet("Data").getRow(2)));
        }
    }

    @Test
    void aConfiguredSheetNameTheTemplateLacksIsRefused() throws Exception {
        byte[] template = template(wb -> dataSheet(wb, "Data", "{{ name }}", "{{ deptId.name }}", "{{ code }}"));

        BusinessException e = assertThrows(BusinessException.class,
                () -> ExportByFileTemplate.prepareTemplate(new ByteArrayInputStream(template), "Dta"));
        assertTrue(e.getMessage().contains("Dta"), e.getMessage());
    }

    @Test
    void placeholdersOnMoreThanOneRowAreRefused() throws Exception {
        // A title naming a field above the data row.
        byte[] titled = template(wb -> {
            Sheet sheet = dataSheet(wb, "Data", "{{ name }}", "{{ deptId.name }}", "{{ code }}");
            sheet.getRow(0).getCell(0).setCellValue("Report for {{ deptId.name }}");
        });
        // One record spread over two rows.
        byte[] twoRows = template(wb -> {
            Sheet sheet = wb.createSheet("Data");
            sheet.createRow(0).createCell(0).setCellValue("{{ name }}");
            sheet.createRow(1).createCell(0).setCellValue("{{ code }}");
        });

        for (byte[] template : List.of(titled, twoRows)) {
            BusinessException e = assertThrows(BusinessException.class,
                    () -> ExportByFileTemplate.prepareTemplate(new ByteArrayInputStream(template), null));
            assertTrue(e.getMessage().contains("[1, 2]"), e.getMessage());
        }
    }

    @Test
    void bracesThatAreNotPlaceholdersAreRefusedWithTheCellNamed() throws Exception {
        // Each would be read by Fesod as a single-value placeholder and blanked, or leave a stray brace.
        String[][] cases = {
                {"A1", "Price {USD}"},
                {"B2", "{{ name }} {unit}"},
                {"B2", "{{ dept-name }}"},
                {"B2", "\\{{ code }}"},
                {"B2", "{{ }}"},
                {"B2", "{{ .x }}"},
                // Fesod's own list syntax written by hand: repeated per record and left empty.
                {"A1", "{.title}"},
                {"B2", "{{ code }} {.other}"},
        };
        for (String[] c : cases) {
            byte[] template = template(wb -> {
                Sheet sheet = dataSheet(wb, "Data", "{{ code }}", "", "ID-{{ code }}");
                Row row = c[0].equals("A1") ? sheet.getRow(0) : sheet.getRow(1);
                row.getCell(c[0].equals("A1") ? 0 : 1).setCellValue(c[1]);
            });
            BusinessException e = assertThrows(BusinessException.class,
                    () -> ExportByFileTemplate.prepareTemplate(new ByteArrayInputStream(template), null), c[1]);
            assertTrue(e.getMessage().contains("Cell " + c[0] + " of sheet Data"), e.getMessage());
        }
    }

    @Test
    void aSheetWithNothingToFillIsRefused() throws Exception {
        byte[] notesConfigured = template(wb -> {
            wb.createSheet("Notes").createRow(0).createCell(0).setCellValue("Read me");
            dataSheet(wb, "Data", "{{ name }}", "{{ deptId.name }}", "{{ code }}");
        });
        BusinessException e = assertThrows(BusinessException.class,
                () -> ExportByFileTemplate.prepareTemplate(new ByteArrayInputStream(notesConfigured), "Notes"));
        assertTrue(e.getMessage().contains("Sheet Notes"), e.getMessage());

        byte[] noPlaceholderAnywhere = template(wb -> wb.createSheet("Data").createRow(0).createCell(0).setCellValue("Name"));
        e = assertThrows(BusinessException.class,
                () -> ExportByFileTemplate.prepareTemplate(new ByteArrayInputStream(noPlaceholderAnywhere), null));
        assertTrue(e.getMessage().contains("No visible sheet"), e.getMessage());
    }

    @Test
    void placeholdersOnlyOnAHiddenSheetAreRefusedNamingThatSheet() throws Exception {
        // The hidden sheet is the first one, the visible one has nothing to fill; very hidden counts as hidden too.
        for (boolean veryHidden : new boolean[] {false, true}) {
            byte[] template = template(wb -> {
                dataSheet(wb, "Old", "{{ name }}", "{{ deptId.name }}", "{{ code }}");
                wb.createSheet("Notes").createRow(0).createCell(0).setCellValue("Read me");
                wb.setSheetVisibility(0, veryHidden ? SheetVisibility.VERY_HIDDEN : SheetVisibility.HIDDEN);
                wb.setActiveSheet(1);
            });
            BusinessException e = assertThrows(BusinessException.class,
                    () -> ExportByFileTemplate.prepareTemplate(new ByteArrayInputStream(template), null));
            assertTrue(e.getMessage().contains("Sheet Old has placeholders but is hidden"), e.getMessage());
            // Configured by name, the hidden sheet is filled.
            assertEquals(0, ExportByFileTemplate.prepareTemplate(new ByteArrayInputStream(template), "Old").sheetNo());
        }
    }

    @Test
    void aHiddenSheetIsNotPickedWhenNoSheetNameIsConfigured() throws Exception {
        byte[] template = template(wb -> {
            dataSheet(wb, "Old", "{{ name }}", "{{ deptId.name }}", "{{ code }}");
            dataSheet(wb, "Data", "{{ name }}", "{{ deptId.name }}", "{{ code }}");
            wb.setActiveSheet(1);
            wb.setSheetVisibility(0, SheetVisibility.VERY_HIDDEN);
        });
        assertEquals(1, ExportByFileTemplate.prepareTemplate(new ByteArrayInputStream(template), null).sheetNo());
    }

    @Test
    void aFileThatIsNotAnXlsxWorkbookIsRefusedReadably() throws Exception {
        byte[] xls;
        try (HSSFWorkbook workbook = new HSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.createSheet("Data").createRow(0).createCell(0).setCellValue("{{ name }}");
            workbook.write(out);
            xls = out.toByteArray();
        }
        for (byte[] file : List.of(xls, "not a workbook".getBytes(), new byte[0])) {
            BusinessException e = assertThrows(BusinessException.class,
                    () -> ExportByFileTemplate.prepareTemplate(new ByteArrayInputStream(file), null));
            assertTrue(e.getMessage().contains(".xlsx"), e.getMessage());
        }
    }

    private static byte[] template(Consumer<XSSFWorkbook> build) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            build.accept(workbook);
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /** A header row and, under it, the data row holding the placeholders. */
    private static Sheet dataSheet(Workbook workbook, String sheetName, String... placeholders) {
        Sheet sheet = workbook.createSheet(sheetName);
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("Name");
        header.createCell(1).setCellValue("Department");
        header.createCell(2).setCellValue("Code");
        Row row = sheet.createRow(1);
        for (int i = 0; i < placeholders.length; i++) {
            row.createCell(i).setCellValue(placeholders[i]);
        }
        return sheet;
    }

    /** Fills {@code count} records the way the export does: the prepared template, its chosen sheet, the same fill config. */
    private static Workbook fill(byte[] template, String sheetName, int count) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            rows.add(row("Alice" + i, "Dept" + i, i));
        }
        ExportByFileTemplate.PreparedTemplate prepared =
                ExportByFileTemplate.prepareTemplate(new ByteArrayInputStream(template), sheetName);
        ByteArrayOutputStream filled = new ByteArrayOutputStream();
        try (ExcelWriter writer = FesodSheet.write(filled).withTemplate(prepared.stream()).build()) {
            writer.fill(rows, prepared.fillConfig(), FesodSheet.writerSheet(prepared.sheetNo()).build());
        }
        return new XSSFWorkbook(new ByteArrayInputStream(filled.toByteArray()));
    }

    private static Map<String, Object> row(String name, String department, int code) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("deptId.name", department);
        row.put("code", code);
        return row;
    }

    private static List<String> texts(Row row) {
        DataFormatter formatter = new DataFormatter();
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Cell cell = row.getCell(i);
            texts.add(formatter.formatCellValue(cell));
        }
        return texts;
    }
}
