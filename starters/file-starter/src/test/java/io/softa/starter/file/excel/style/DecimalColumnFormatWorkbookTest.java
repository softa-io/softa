package io.softa.starter.file.excel.style;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import org.apache.fesod.sheet.ExcelWriter;
import org.apache.fesod.sheet.FesodSheet;
import org.apache.fesod.sheet.write.metadata.WriteSheet;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.file.excel.export.support.ExcelWriterFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

/**
 * The handler driven the way the exports drive it — through the writer, with the shared handlers
 * registered — and the result read back out of real bytes: the format on each written cell, and
 * that the cell is still a number underneath.
 */
class DecimalColumnFormatWorkbookTest {

    /**
     * Column 0 a string, 1 an amount kept to 2 decimals, 2 a rate kept to 6, 3 a double declaring no
     * scale, 4 a decimal kept to 0 decimals, 5 an integer, 6 a name that is not a field at all.
     */
    private static final List<String> FIELDS =
            List.of("code", "amount", "rate", "weight", "units", "count", "note");
    private static final List<List<String>> HEADS = FIELDS.stream().map(List::of).toList();

    private void withMetadata(Runnable body) {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.getLastFieldOfCascaded(any(), any())).thenAnswer(inv -> {
                String fieldName = inv.getArgument(1);
                if ("note".equals(fieldName)) {
                    throw new IllegalArgumentException("not a field");
                }
                MetaField metaField = new MetaField();
                FieldType type = switch (fieldName) {
                    case "amount", "rate", "units" -> FieldType.BIG_DECIMAL;
                    case "weight" -> FieldType.DOUBLE;
                    case "count" -> FieldType.INTEGER;
                    default -> FieldType.STRING;
                };
                Integer scale = switch (fieldName) {
                    case "amount" -> 2;
                    case "rate" -> 6;
                    case "units" -> 0;
                    default -> null;
                };
                ReflectionTestUtils.setField(metaField, "fieldType", type);
                ReflectionTestUtils.setField(metaField, "scale", scale);
                return metaField;
            });
            body.run();
        }
    }

    /** Written through {@link ExcelWriterFactory}, whose shared handlers give every cell an explicit style. */
    private static byte[] write(List<List<Object>> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelWriter writer = FesodSheet.write(out).build()) {
            WriteSheet sheet = new ExcelWriterFactory()
                    .createSheetBuilder(0, "Orders", HEADS, DecimalColumnFormatHandler.forFields("Order", FIELDS))
                    .build();
            writer.write(rows, sheet);
            writer.finish();
        }
        return out.toByteArray();
    }

    private static Workbook read(List<List<Object>> rows) throws Exception {
        return new XSSFWorkbook(new ByteArrayInputStream(write(rows)));
    }

    private static String formatOf(Row row, int column) {
        Cell cell = row.getCell(column);
        return cell == null ? null : cell.getCellStyle().getDataFormatString();
    }

    @Test
    void aWrittenDecimalCarriesItsFieldsScaleAsTheNumberFormat() {
        withMetadata(() -> run(() -> {
            List<Object> values = Arrays.asList("ORD-1", new BigDecimal("650.00"), new BigDecimal("1.234500"),
                    12.5d, new BigDecimal("3"), 7, "free text");
            try (Workbook workbook = read(List.of(values))) {
                Row row = workbook.getSheetAt(0).getRow(1);
                assertThat(formatOf(row, 1)).isEqualTo("#,##0.00");
                assertThat(formatOf(row, 2)).isEqualTo("#,##0.000000");
                assertThat(formatOf(row, 3)).as("no declared scale falls back to the default").isEqualTo("#,##0.00");
                assertThat(formatOf(row, 4)).as("a scale of 0 formats as a whole number").isEqualTo("#,##0");
                assertThat(formatOf(row, 0)).as("a string column is left alone").isEqualTo("General");
                assertThat(formatOf(row, 5)).as("an integer column is left alone").isEqualTo("General");
                assertThat(formatOf(row, 6)).as("a name that is not a field has no format").isEqualTo("General");
                // The value is still a number: the format changes what is shown, not what is summed.
                assertThat(row.getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
                assertThat(row.getCell(1).getNumericCellValue()).isEqualTo(650d);
            }
        }));
    }

    @Test
    void aTextOrEmptyCellInADecimalColumnIsLeftAlone() {
        withMetadata(() -> run(() -> {
            // A decimal column may still carry text — a large id retyped by LargeIntegerAsTextHandler,
            // or a value the fetch could not convert — and an empty cell carries nothing to format.
            List<Object> values = Arrays.asList("ORD-2", "n/a", null, null, null, null, null);
            try (Workbook workbook = read(List.of(values))) {
                Row row = workbook.getSheetAt(0).getRow(1);
                assertThat(row.getCell(1).getCellType()).isEqualTo(CellType.STRING);
                assertThat(formatOf(row, 1)).isEqualTo("General");
                assertThat(formatOf(row, 2)).as("an empty cell is written without the format, or not at all")
                        .isIn(null, "General");
            }
        }));
    }

    @Test
    void aSheetWithNoDecimalColumnGetsNoHandlerAtAll() {
        // forFields returns null, which the sheet builder skips — nothing registered, nothing to undo.
        withMetadata(() -> assertThat(DecimalColumnFormatHandler.forFields("Order", List.of("code", "count", "note")))
                .isNull());
        assertThat(DecimalColumnFormatHandler.forFields(null, List.of("amount"))).isNull();
        assertThat(DecimalColumnFormatHandler.forFields("Order", null)).isNull();
    }

    @Test
    void oneWorkbookStylePerFormatAcrossTheCellsThatShareIt() {
        // A workbook caps at 64k cell styles. A column of amounts, or two columns of the same scale,
        // must not mean one style per cell: the writer reuses a style for an identical cell style.
        withMetadata(() -> run(() -> {
            List<List<Object>> rows = List.of(
                    Arrays.asList("ORD-1", new BigDecimal("650.00"), null, 1.5d, null, null, null),
                    Arrays.asList("ORD-2", new BigDecimal("427.40"), null, 2.5d, null, null, null));
            try (Workbook workbook = read(rows)) {
                Row first = workbook.getSheetAt(0).getRow(1);
                Row second = workbook.getSheetAt(0).getRow(2);
                assertThat(first.getCell(1).getCellStyle().getIndex())
                        .as("the same column on two rows shares one style")
                        .isEqualTo(second.getCell(1).getCellStyle().getIndex());
                assertThat(first.getCell(1).getCellStyle().getIndex())
                        .as("two columns of the same format share one style")
                        .isEqualTo(first.getCell(3).getCellStyle().getIndex());
            }
        }));
    }

    /** Lets a lambda throw, so the assertions read as they would outside one. */
    private static void run(ThrowingBody body) {
        try {
            body.run();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private interface ThrowingBody {
        void run() throws Exception;
    }
}
