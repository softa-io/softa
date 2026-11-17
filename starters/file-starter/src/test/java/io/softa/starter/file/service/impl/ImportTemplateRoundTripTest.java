package io.softa.starter.file.service.impl;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.file.dto.ImportFieldDTO;
import io.softa.starter.file.entity.ImportTemplate;
import io.softa.starter.file.enums.ImportRule;
import io.softa.starter.file.excel.export.ExcelSheetData;
import io.softa.starter.file.excel.export.support.ExcelUploadService;
import io.softa.starter.file.excel.export.support.ExcelWriterFactory;
import io.softa.starter.file.excel.export.support.OptionDropdownResolver;
import io.softa.starter.file.excel.imports.ImportHeaderMatcher;
import io.softa.starter.file.excel.imports.ImportTemplateWorkbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A file exported for re-import goes back in through its template as it came out: written by the real
 * workbook writer into the template's workbook, read by the import's real reader, every column comes
 * back under its field with the value it was written with.
 */
class ImportTemplateRoundTripTest {

    private static final String MODEL = "JobPosition";

    private static final List<ImportFieldDTO> COLUMNS = List.of(
            column("code", "Position Code", true),
            column("name", "Position Name", true),
            column("departmentId.code", "Department Code", false),
            column("currency", "Currency", false),
            column("active", "Active", false),
            column("type", "Type", false),
            column("startDate", "Start Date", false));

    private static ImportFieldDTO column(String fieldName, String header, boolean required) {
        ImportFieldDTO column = new ImportFieldDTO();
        column.setFieldName(fieldName);
        column.setHeader(header);
        column.setRequired(required);
        column.setDescription(header + " of the position");
        return column;
    }

    private static ImportTemplate template() {
        ImportTemplate template = new ImportTemplate();
        template.setId(21L);
        template.setName("Position Import Template");
        template.setModelName(MODEL);
        template.setImportRule(ImportRule.CREATE_OR_UPDATE);
        template.setIncludeDescription(true);
        return template;
    }

    /** What the export reads for each column — by its read path, with options and booleans as labels. */
    private static List<List<Object>> exportedRows() {
        return List.of(
                Arrays.asList("P001", "Analyst", "D001", "SGD", "Yes", "Full Time", LocalDate.of(2026, 11, 17)),
                Arrays.asList("P002", "Engineer", "D002", "NZD", "No", "Part Time", null));
    }

    private static MetaField metaField(String fieldName, FieldType type) {
        MetaField field = new MetaField();
        ReflectionTestUtils.setField(field, "modelName", MODEL);
        ReflectionTestUtils.setField(field, "fieldName", fieldName);
        ReflectionTestUtils.setField(field, "fieldType", type);
        return field;
    }

    private static byte[] export(List<List<Object>> rows) throws Exception {
        OptionDropdownResolver resolver = mock(OptionDropdownResolver.class);
        when(resolver.resolve(anyString(), any(), any(), any()))
                .thenReturn(Map.of(4, List.of("Yes", "No"), 5, List.of("Full Time", "Part Time")));
        ImportTemplateWorkbook workbook = new ImportTemplateWorkbook();
        ReflectionTestUtils.setField(workbook, "optionDropdownResolver", resolver);
        ExcelUploadService uploadService = new ExcelUploadService();
        ReflectionTestUtils.setField(uploadService, "excelWriterFactory", new ExcelWriterFactory());
        try (MockedStatic<ModelManager> modelManager = Mockito.mockStatic(ModelManager.class)) {
            // The date column's format comes from the field it lands on; the rest are plain.
            modelManager.when(() -> ModelManager.getLastFieldOfCascaded(anyString(), anyString()))
                    .thenAnswer(call -> "startDate".equals(call.getArgument(1))
                            ? metaField("startDate", FieldType.DATE)
                            : metaField(call.getArgument(1), FieldType.STRING));
            List<ExcelSheetData> sheets = workbook.sheets(template(), COLUMNS, rows);
            return uploadService.writeWorkbook(sheets);
        }
    }

    private static List<Map<String, Object>> importBack(byte[] bytes) throws Exception {
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            return new ImportServiceImpl().extractDataFromExcel(ImportHeaderMatcher.of(COLUMNS), "positions.xlsx", in);
        }
    }

    @Test
    void everyColumnComesBackUnderItsFieldWithTheValueItWasWrittenWith() throws Exception {
        List<Map<String, Object>> rows = importBack(export(exportedRows()));

        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst())
                .containsEntry("code", "P001")
                .containsEntry("name", "Analyst")
                .containsEntry("departmentId.code", "D001")
                .containsEntry("currency", "SGD")
                .containsEntry("active", "Yes")
                .containsEntry("type", "Full Time")
                .containsEntry("startDate", "2026-11-17");
        assertThat(rows.get(1))
                .containsEntry("code", "P002")
                .containsEntry("currency", "NZD")
                .containsEntry("active", "No");
        assertThat(rows.get(1).get("startDate")).isNull();
    }

    @Test
    void theFileIsTheTemplatesOwnWorkbook_withItsRecordsCoveredByTheFilterAndTheDropdowns() throws Exception {
        List<List<Object>> rows = new ArrayList<>(exportedRows());
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(export(rows)))) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("Position Import Template");
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Position Code");
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
            // The filter reaches the records, as a result file's does — not the header alone.
            assertThat(sheet.getCTWorksheet().getAutoFilter().getRef()).isEqualTo("A1:G3");
            // The dropdowns reach past the records, by as many rows as a blank template offers.
            List<DataValidation> validations = new ArrayList<>(sheet.getDataValidations());
            assertThat(validations).isNotEmpty();
            assertThat(validations).allSatisfy(validation ->
                    assertThat(validation.getRegions().getCellRangeAddress(0).getLastRow()).isEqualTo(2 + 5000));
            assertThat(workbook.getSheetAt(1).getSheetName()).isEqualTo("Import instructions");
        }
    }

    @Test
    void aBlankTemplateStillCoversTheRowsItAlwaysDid() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(export(List.of())))) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isZero();
            assertThat(sheet.getCTWorksheet().getAutoFilter().getRef()).isEqualTo("A1:G1");
            assertThat(new ArrayList<>(sheet.getDataValidations())).allSatisfy(validation ->
                    assertThat(validation.getRegions().getCellRangeAddress(0).getLastRow()).isEqualTo(5000));
        }
    }
}
