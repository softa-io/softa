package io.softa.starter.file.excel.export.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.exception.PermissionException;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.dto.FileInfo;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.MetaModel;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.PermissionService;
import io.softa.starter.file.dto.ExportResult;
import io.softa.starter.file.dto.ImportFieldDTO;
import io.softa.starter.file.dto.ImportTemplateDTO;
import io.softa.starter.file.entity.ImportTemplate;
import io.softa.starter.file.enums.ImportRule;
import io.softa.starter.file.excel.imports.ImportTemplateWorkbook;
import io.softa.starter.file.service.ImportService;
import io.softa.starter.file.service.ImportTemplateService;
import io.softa.starter.file.vo.ExportableImportTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which import templates a file for re-import can be written with, and how each column is read so the
 * file goes back in through the same template.
 */
class ImportTemplateExportTest {

    /** model → field name → field, in declaration order. */
    private final Map<String, Map<String, MetaField>> catalog = new LinkedHashMap<>();
    private final Map<String, Boolean> timeline = new LinkedHashMap<>();
    private MockedStatic<ModelManager> modelManager;

    private void field(String model, String name, FieldType type, String relatedModel) {
        MetaField field = new MetaField();
        ReflectionTestUtils.setField(field, "modelName", model);
        ReflectionTestUtils.setField(field, "fieldName", name);
        ReflectionTestUtils.setField(field, "label", name);
        ReflectionTestUtils.setField(field, "fieldType", type);
        ReflectionTestUtils.setField(field, "relatedModel", relatedModel);
        catalog.computeIfAbsent(model, m -> new LinkedHashMap<>()).put(name, field);
    }

    private void cascadedAnchor(String model, String name, String relatedModel, String cascadedField) {
        field(model, name, FieldType.MANY_TO_ONE, relatedModel);
        MetaField field = catalog.get(model).get(name);
        ReflectionTestUtils.setField(field, "dynamic", true);
        ReflectionTestUtils.setField(field, "cascadedField", cascadedField);
    }

    @BeforeEach
    void setUp() {
        field("Position", "id", FieldType.LONG, null);
        field("Position", "code", FieldType.STRING, null);
        field("Position", "name", FieldType.STRING, null);
        field("Position", "salary", FieldType.BIG_DECIMAL, null);
        field("Position", "currency", FieldType.MANY_TO_ONE, "Currency");
        field("Position", "departmentId", FieldType.MANY_TO_ONE, "Department");
        field("Position", "roleIds", FieldType.MANY_TO_MANY, "Role");
        field("Department", "id", FieldType.LONG, null);
        field("Department", "code", FieldType.STRING, null);
        field("Department", "companyId", FieldType.MANY_TO_ONE, "Company");
        field("Currency", "id", FieldType.STRING, null);
        field("Role", "id", FieldType.LONG, null);
        field("Role", "code", FieldType.STRING, null);
        field("Company", "id", FieldType.LONG, null);
        field("Company", "country", FieldType.MANY_TO_ONE, "CountryRegion");
        field("Employee", "id", FieldType.LONG, null);
        field("Employee", "code", FieldType.STRING, null);
        field("Employee", "legalEntityId", FieldType.MANY_TO_ONE, "Company");
        field("DeptStatistic", "id", FieldType.LONG, null);
        field("DeptStatistic", "deptId", FieldType.MANY_TO_ONE, "Department");
        cascadedAnchor("DeptStatistic", "companyId", "Company", "deptId.companyId");
        field("Allowance", "id", FieldType.LONG, null);
        field("Allowance", "code", FieldType.STRING, null);
        timeline.put("Allowance", true);

        modelManager = Mockito.mockStatic(ModelManager.class);
        modelManager.when(() -> ModelManager.existModel(anyString()))
                .thenAnswer(call -> catalog.containsKey(call.<String>getArgument(0)));
        modelManager.when(() -> ModelManager.existField(anyString(), anyString()))
                .thenAnswer(call -> catalog.getOrDefault(call.<String>getArgument(0), Map.of())
                        .containsKey(call.<String>getArgument(1)));
        modelManager.when(() -> ModelManager.getModelField(anyString(), anyString()))
                .thenAnswer(call -> catalog.get(call.<String>getArgument(0)).get(call.<String>getArgument(1)));
        modelManager.when(() -> ModelManager.getModelFields(anyString()))
                .thenAnswer(call -> new ArrayList<>(catalog.get(call.<String>getArgument(0)).values()));
        modelManager.when(() -> ModelManager.getModel(anyString())).thenAnswer(call -> {
            MetaModel model = mock(MetaModel.class);
            when(model.isTimeline()).thenReturn(timeline.getOrDefault(call.<String>getArgument(0), false));
            return model;
        });
        modelManager.when(() -> ModelManager.getLastFieldOfCascaded(anyString(), anyString())).thenAnswer(call -> {
            String model = call.getArgument(0);
            MetaField field = null;
            for (String segment : call.<String>getArgument(1).split("\\.")) {
                field = catalog.get(model).get(segment);
                model = field.getRelatedModel();
            }
            return field;
        });
    }

    @AfterEach
    void tearDown() {
        modelManager.close();
    }

    private static ImportTemplate template(String model, ImportRule rule, String... uniqueKeys) {
        ImportTemplate template = new ImportTemplate();
        template.setId(21L);
        template.setName("Position Import Template");
        template.setModelName(model);
        template.setImportRule(rule);
        template.setIgnoreEmpty(true);
        template.setUniqueConstraints(List.of(uniqueKeys));
        return template;
    }

    private static ImportFieldDTO column(String fieldName, String header, boolean required) {
        ImportFieldDTO column = new ImportFieldDTO();
        column.setFieldName(fieldName);
        column.setHeader(header);
        column.setRequired(required);
        return column;
    }

    private static ImportTemplateDTO shape(String model, ImportFieldDTO... columns) {
        ImportTemplateDTO shape = new ImportTemplateDTO();
        shape.setModelName(model);
        for (ImportFieldDTO column : columns) {
            shape.addImportField(column);
        }
        return shape;
    }

    private static ImportTemplateExport.Sheet sheet(ImportTemplate template, ImportTemplateDTO shape) {
        return ImportTemplateExport.Sheet.of(template, shape, Map.of());
    }

    @Test
    void aTemplateThatOnlyCreatesIsNotOffered() {
        var sheet = sheet(template("Position", ImportRule.ONLY_CREATE, "code"),
                shape("Position", column("code", "Position Code", true)));

        assertThat(sheet.refusal()).isNotNull();
        assertThat(sheet.refusal().pattern()).contains("only creates records");
    }

    @Test
    void aTemplateThatOnlyUpdatesIsOffered() {
        var sheet = sheet(template("Position", ImportRule.ONLY_UPDATE, "code"),
                shape("Position", column("code", "Position Code", true)));

        assertThat(sheet.refusal()).isNull();
    }

    @Test
    void recordsWithEffectiveDatedVersionsAreNotOfferedYet() {
        var sheet = sheet(template("Allowance", ImportRule.CREATE_OR_UPDATE, "code"),
                shape("Allowance", column("code", "Allowance Code", true)));

        assertThat(sheet.refusal()).isNotNull();
        assertThat(sheet.refusal().pattern()).contains("effective-dated versions");
    }

    @Test
    void aTemplateWithoutAUniqueKeyIsNotOffered() {
        var sheet = sheet(template("Position", ImportRule.CREATE_OR_UPDATE),
                shape("Position", column("code", "Position Code", true)));

        assertThat(sheet.refusal()).isNotNull();
        assertThat(sheet.refusal().pattern()).contains("names no unique key");
    }

    @Test
    void aKeyWithoutAColumnIsNotOffered() {
        var sheet = sheet(template("Position", ImportRule.CREATE_OR_UPDATE, "code"),
                shape("Position", column("name", "Position Name", true)));

        assertThat(sheet.refusal()).isNotNull();
        assertThat(sheet.refusal().pattern()).contains("has no column for");
        assertThat(sheet.refusal().args()).contains("code");
    }

    @Test
    void aRelationInTheKeyIsCoveredByTheColumnThatLooksItUp() {
        // CompanyPayrollAccount and TimeSheet in the HR app: the key names the relation, the column its code.
        var sheet = sheet(template("Position", ImportRule.CREATE_OR_UPDATE, "departmentId", "code"),
                shape("Position", column("departmentId.code", "Department Code", true),
                        column("code", "Position Code", true)));

        assertThat(sheet.refusal()).isNull();
        assertThat(sheet.describe(template("Position", ImportRule.CREATE_OR_UPDATE, "departmentId", "code"))
                .getMatchHeaders()).containsExactly("Department Code", "Position Code");
    }

    @Test
    void aColumnNamingSeveralRecordsIsNotOfferedYet() {
        var sheet = sheet(template("Position", ImportRule.CREATE_OR_UPDATE, "code"),
                shape("Position", column("code", "Position Code", true), column("roleIds.code", "Roles", false)));

        assertThat(sheet.refusal()).isNotNull();
        assertThat(sheet.refusal().pattern()).contains("names several records");
        assertThat(sheet.refusal().args()).contains("Roles");
    }

    @Test
    void eachColumnIsReadAsItsImportTakesIt() {
        var sheet = sheet(template("Position", ImportRule.CREATE_OR_UPDATE, "code"),
                shape("Position", column("code", "Position Code", true),
                        column("departmentId.code", "Department Code", false),
                        column("currency", "Currency", false)));

        assertThat(sheet.refusal()).isNull();
        // A bare to-one column imports the related row's id — for a code-as-id master, its code.
        assertThat(sheet.readPaths()).containsExactly("code", "departmentId.code", "currency.id");
    }

    @Test
    void anOptionalColumnTheCallerCannotSeeIsLeftOutOfTheFile() {
        var sheet = ImportTemplateExport.Sheet.of(template("Position", ImportRule.CREATE_OR_UPDATE, "code"),
                shape("Position", column("code", "Position Code", true), column("salary", "Salary", false)),
                Map.of("Position", Set.of("salary")));

        assertThat(sheet.refusal()).isNull();
        assertThat(sheet.columns()).extracting(ImportFieldDTO::getHeader).containsExactly("Position Code");
        assertThat(sheet.readPaths()).containsExactly("code");
    }

    @Test
    void aRequiredColumnTheCallerCannotSeeMakesTheTemplateUnusableForThem() {
        var sheet = ImportTemplateExport.Sheet.of(template("Position", ImportRule.CREATE_OR_UPDATE, "code"),
                shape("Position", column("code", "Position Code", true), column("salary", "Salary", true)),
                Map.of("Position", Set.of("salary")));

        assertThat(sheet.refusal()).isNotNull();
        assertThat(sheet.refusal().pattern()).contains("which you cannot see");
    }

    @Test
    void aHiddenKeyColumnLeavesNothingToMatchARowBy() {
        var sheet = ImportTemplateExport.Sheet.of(template("Position", ImportRule.CREATE_OR_UPDATE, "code"),
                shape("Position", column("code", "Position Code", false), column("name", "Position Name", true)),
                Map.of("Position", Set.of("code")));

        assertThat(sheet.refusal()).isNotNull();
        assertThat(sheet.refusal().pattern()).contains("has no column for");
    }

    @Test
    void theDescriptionCarriesTheFileColumnsAndTheTemplateSettings() {
        ImportTemplate template = template("Position", ImportRule.CREATE_OR_UPDATE, "code");
        template.setCountry(" SG ");
        var sheet = sheet(template, shape("Position", column("code", "Position Code", true),
                column("name", "Position Name", false)));

        ExportableImportTemplate described = sheet.describe(template);

        assertThat(described.getId()).isEqualTo(21L);
        assertThat(described.getName()).isEqualTo("Position Import Template");
        assertThat(described.getCountry()).isEqualTo("SG");
        assertThat(described.getImportRule()).isEqualTo(ImportRule.CREATE_OR_UPDATE);
        assertThat(described.getIgnoreEmpty()).isTrue();
        assertThat(described.getMatchHeaders()).containsExactly("Position Code");
        assertThat(described.getColumns()).containsExactly(
                new ExportableImportTemplate.Column("Position Code", true),
                new ExportableImportTemplate.Column("Position Name", false));
    }

    @Test
    void theCompanyModelIsFilteredByItsOwnCountry() {
        Filters filters = ImportTemplateExport.countryFilter("Company", "SG");

        assertThat(filters.getFilterUnit().getField()).isEqualTo("country");
        assertThat(filters.getFilterUnit().getValue()).isEqualTo("SG");
    }

    @Test
    void aRowIsJudgedByTheCompanyItNames_asTheImportJudgesIt() {
        Filters filters = ImportTemplateExport.countryFilter("Employee", "NZ");

        assertThat(filters.getFilterUnit().getField()).isEqualTo("legalEntityId.country");
        assertThat(filters.getFilterUnit().getValue()).isEqualTo("NZ");
    }

    @Test
    void aCompanyAnchorJoinedAtQueryTimeIsFilteredThroughItsSourcePath() {
        Filters filters = ImportTemplateExport.countryFilter("DeptStatistic", "SG");

        assertThat(filters.getFilterUnit().getField()).isEqualTo("deptId.companyId.country");
    }

    @Test
    void noCountryOrNoCompanyMeansEveryRow() {
        assertThat(ImportTemplateExport.countryFilter("Employee", null)).isNull();
        assertThat(ImportTemplateExport.countryFilter("Employee", " ")).isNull();
        // Rows tied to no company: the template's country decides only how values are looked up.
        assertThat(ImportTemplateExport.countryFilter("Position", "SG")).isNull();
    }

    // ───────────────────────── the listing and the export, wired ─────────────────────────

    private final PermissionService permissionService = mock(PermissionService.class);
    private final ImportTemplateService importTemplateService = mock(ImportTemplateService.class);
    private final ImportService importService = mock(ImportService.class);
    private final ExportDataFetcher exportDataFetcher = mock(ExportDataFetcher.class);
    private final ImportTemplateWorkbook importTemplateWorkbook = mock(ImportTemplateWorkbook.class);
    private final ExcelUploadService excelUploadService = mock(ExcelUploadService.class);

    private ImportTemplateExport wired() {
        ImportTemplateExport export = new ImportTemplateExport();
        ReflectionTestUtils.setField(export, "permissionService", permissionService);
        ReflectionTestUtils.setField(export, "importTemplateService", importTemplateService);
        ReflectionTestUtils.setField(export, "importService", importService);
        ReflectionTestUtils.setField(export, "exportDataFetcher", exportDataFetcher);
        ReflectionTestUtils.setField(export, "importTemplateWorkbook", importTemplateWorkbook);
        ReflectionTestUtils.setField(export, "excelUploadService", excelUploadService);
        return export;
    }

    private void grants(boolean export, boolean importing) {
        when(permissionService.hasModelExportGrant(anyString())).thenReturn(export);
        when(permissionService.hasModelImportGrant(anyString())).thenReturn(importing);
        when(permissionService.getHiddenSensitiveFields()).thenReturn(Map.of());
    }

    @Test
    void nothingIsListedForACallerWhoCannotImportTheModelBack() {
        grants(true, false);

        assertThat(wired().listFor("Position")).isEmpty();
        verify(importTemplateService, never()).searchList(any(FlexQuery.class));
    }

    @Test
    void theListingOffersTheUsableTemplatesInDisplayOrder() {
        grants(true, true);
        ImportTemplate creates = template("Position", ImportRule.ONLY_CREATE, "code");
        creates.setId(31L);
        creates.setSequence(1);
        ImportTemplate second = template("Position", ImportRule.CREATE_OR_UPDATE, "code");
        second.setId(32L);
        second.setSequence(3);
        ImportTemplate first = template("Position", ImportRule.ONLY_UPDATE, "code");
        first.setId(33L);
        first.setSequence(2);
        when(importTemplateService.searchList(any(FlexQuery.class))).thenReturn(List.of(second, creates, first));
        when(importService.getImportTemplateDTO(any(), any()))
                .thenAnswer(call -> shape("Position", column("code", "Position Code", true)));

        assertThat(wired().listFor("Position")).extracting(ExportableImportTemplate::getId).containsExactly(33L, 32L);
    }

    @Test
    void anExportNeedsTheImportOfTheModelToo() {
        grants(true, false);

        assertThatThrownBy(() -> wired().export("Position", 21L, new FlexQuery()))
                .isInstanceOf(PermissionException.class);
        verify(exportDataFetcher, never()).fetchRows(anyString(), any(), any());
    }

    @Test
    void anotherModelsTemplateIsRefused() {
        grants(true, true);
        when(importTemplateService.getById(21L))
                .thenReturn(Optional.of(template("Employee", ImportRule.CREATE_OR_UPDATE, "code")));

        assertThatThrownBy(() -> wired().export("Position", 21L, new FlexQuery()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not of Position");
    }

    @Test
    void anUnusableTemplateIsRefusedWithItsReason() {
        grants(true, true);
        when(importTemplateService.getById(21L))
                .thenReturn(Optional.of(template("Position", ImportRule.ONLY_CREATE, "code")));
        when(importService.getImportTemplateDTO(any(), any()))
                .thenReturn(shape("Position", column("code", "Position Code", true)));

        assertThatThrownBy(() -> wired().export("Position", 21L, new FlexQuery()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("only creates records");
        verify(exportDataFetcher, never()).fetchRows(anyString(), any(), any());
    }

    @Test
    void theFileHoldsTheTemplateColumnsOfTheTemplateCountry_andBelongsToTheExportHistory() {
        grants(true, true);
        ImportTemplate template = template("Employee", ImportRule.CREATE_OR_UPDATE, "code");
        template.setCountry("NZ");
        when(importTemplateService.getById(21L)).thenReturn(Optional.of(template));
        when(importService.getImportTemplateDTO(any(), any())).thenReturn(shape("Employee",
                column("code", "Employee Code", true), column("legalEntityId", "Legal Entity ID", false)));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 7L);
        row.put("legalEntityId.id", 501L);
        row.put("code", "E001");
        when(exportDataFetcher.fetchRows(eq("Employee"), isNull(), any(FlexQuery.class))).thenReturn(List.of(row));
        when(importTemplateWorkbook.sheets(any(), any(), any())).thenReturn(List.of());
        FileInfo file = new FileInfo();
        file.setFileId(404L);
        when(excelUploadService.generateFileAndUpload(anyString(), anyString(), anyList())).thenReturn(file);

        FlexQuery flexQuery = new FlexQuery();
        ExportResult result = wired().export("Employee", 21L, flexQuery);

        assertThat(result.getTotalRows()).isEqualTo(1);
        assertThat(result.getFileInfo().getFileId()).isEqualTo(404L);
        assertThat(flexQuery.getFields()).containsExactly("code", "legalEntityId.id");
        assertThat(flexQuery.getFilters().getFilterUnit().getField()).isEqualTo("legalEntityId.country");
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List<List<Object>>> table = ArgumentCaptor.forClass((Class) List.class);
        verify(importTemplateWorkbook).sheets(eq(template), any(), table.capture());
        assertThat(table.getValue()).containsExactly(List.of("E001", 501L));
        verify(excelUploadService).generateFileAndUpload(eq("ExportHistory"), eq("Position Import Template"), anyList());
    }
}
