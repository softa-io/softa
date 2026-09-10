package io.softa.starter.file.excel.export.support;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.apache.fesod.sheet.ExcelWriter;
import org.apache.fesod.sheet.FesodSheet;
import org.apache.fesod.sheet.write.metadata.WriteSheet;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An exported id must come back out of the file as the id that went in.
 *
 * <p>Read out of real bytes, and written through {@link ExcelWriterFactory} because that is the path
 * every export takes — a hand-built sheet would prove nothing about the handlers it registers.
 *
 * <p>The reported symptom was {@code 8.54278E+17} in the department export's ID column, which reads
 * as a formatting problem and is not one: the assertion that matters below is on the VALUE. Written
 * as a number, {@code 854278123456789012} is stored as a double and comes back
 * {@code 854278123456788992} — a different id, silently, in the file itself. No column format
 * recovers that.
 */
class LargeIntegerAsTextWorkbookTest {

    private static final List<List<String>> HEADS =
            List.of(List.of("ID"), List.of("Name"), List.of("Headcount"), List.of("Budget"));

    private Workbook write(List<Object> row) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelWriter writer = FesodSheet.write(out).build()) {
            WriteSheet sheet = new ExcelWriterFactory()
                    .createSheetBuilder(0, "Departments", HEADS)
                    .build();
            writer.write(List.of(row), sheet);
            writer.finish();
        }
        return new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()));
    }

    private Cell cell(Workbook workbook, int column) {
        return workbook.getSheetAt(0).getRow(1).getCell(column);
    }

    @Test
    void keepsEveryDigitOfAnId() throws Exception {
        try (Workbook workbook = write(List.of(854278123456789012L, "HR", 42, new BigDecimal("1250.75")))) {
            Cell id = cell(workbook, 0);

            assertThat(id.getCellType()).isEqualTo(CellType.STRING);
            assertThat(id.getStringCellValue()).isEqualTo("854278123456789012");
        }
    }

    @Test
    void leavesNumbersAReaderWouldSumAsNumbers() throws Exception {
        try (Workbook workbook = write(List.of(854278123456789012L, "HR", 42, new BigDecimal("1250.75")))) {
            assertThat(cell(workbook, 2).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(cell(workbook, 2).getNumericCellValue()).isEqualTo(42d);
            assertThat(cell(workbook, 3).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(cell(workbook, 3).getNumericCellValue()).isEqualTo(1250.75d);
        }
    }

    @Test
    void retypesARoundIdToo() throws Exception {
        // 1E+18 has a stripped precision of 1 and still needs 19 digits. Counting significant
        // digits instead of magnitude would let exactly the round ids through — the ones whose
        // scientific-notation form looks most like the right answer.
        try (Workbook workbook = write(List.of(1000000000000000000L, "Ops", 1, BigDecimal.ONE))) {
            assertThat(cell(workbook, 0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(cell(workbook, 0).getStringCellValue()).isEqualTo("1000000000000000000");
        }
    }

    @Test
    void drawsTheLineWhereExcelDoes() throws Exception {
        // 15 significant digits is what Excel represents exactly; the next digit is not.
        try (Workbook workbook = write(List.of(999999999999999L, "Edge", 1, BigDecimal.ONE))) {
            assertThat(cell(workbook, 0).getCellType()).isEqualTo(CellType.NUMERIC);
        }
        try (Workbook workbook = write(List.of(1000000000000000L, "Edge", 1, BigDecimal.ONE))) {
            assertThat(cell(workbook, 0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(cell(workbook, 0).getStringCellValue()).isEqualTo("1000000000000000");
        }
    }

    @Test
    void leavesTheHeaderRowAlone() throws Exception {
        try (Workbook workbook = write(List.of(854278123456789012L, "HR", 42, new BigDecimal("1250.75")))) {
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("ID");
        }
    }

    /**
     * The file-template export builds its own sheet — the uploaded template supplies the styling, so
     * it skips {@link ExcelWriterFactory} — and fills placeholders instead of writing rows. Same
     * guarantee, different executor, hence its own case: registering the handler in the factory
     * alone would leave this path writing doubles.
     */
    @Test
    void keepsEveryDigitThroughAFilledTemplate() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ByteArrayInputStream template = new ByteArrayInputStream(placeholderTemplate());
             ExcelWriter writer = FesodSheet.write(out).withTemplate(template).build()) {
            WriteSheet sheet = FesodSheet.writerSheet("Departments")
                    .registerWriteHandler(new LargeIntegerAsTextHandler())
                    .build();
            writer.fill(List.<Map<String, Object>>of(Map.of("id", 854278123456789012L, "name", "HR")), sheet);
            writer.finish();
        }
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Cell id = cell(workbook, 0);

            assertThat(id.getCellType()).isEqualTo(CellType.STRING);
            assertThat(id.getStringCellValue()).isEqualTo("854278123456789012");
        }
    }

    /** A two-column template whose data row is {@code {.id}} / {@code {.name}} placeholders. */
    private byte[] placeholderTemplate() throws Exception {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Departments");
            Row head = sheet.createRow(0);
            head.createCell(0).setCellValue("ID");
            head.createCell(1).setCellValue("Name");
            Row body = sheet.createRow(1);
            body.createCell(0).setCellValue("{.id}");
            body.createCell(1).setCellValue("{.name}");
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
