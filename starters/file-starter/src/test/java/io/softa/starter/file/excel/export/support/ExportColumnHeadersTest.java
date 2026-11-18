package io.softa.starter.file.excel.export.support;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.file.entity.ExportTemplate;
import io.softa.starter.file.entity.ExportTemplateField;
import io.softa.starter.file.excel.export.ResolvedTemplateSheet;
import io.softa.starter.file.service.ExportTemplateFieldService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A column nobody named a header for is headed by the relations its path goes through and then the
 * field — the same in a dynamic export and in a template — so two columns reached through different
 * relations onto one model never share a header.
 */
class ExportColumnHeadersTest {

    private static MetaField field(String model, String fieldName, String label, FieldType type, String relatedModel) {
        MetaField metaField = new MetaField();
        ReflectionTestUtils.setField(metaField, "modelName", model);
        ReflectionTestUtils.setField(metaField, "fieldName", fieldName);
        ReflectionTestUtils.setField(metaField, "fieldType", type);
        ReflectionTestUtils.setField(metaField, "label", label);
        ReflectionTestUtils.setField(metaField, "relatedModel", relatedModel);
        return metaField;
    }

    private static MetaField field(String model, String fieldName, String label) {
        return field(model, fieldName, label, FieldType.STRING, null);
    }

    /** Employee and LeaveRequest as far as the headers below read them. */
    private static void stubModels(MockedStatic<ModelManager> mm) {
        mm.when(() -> ModelManager.getModelField("Employee", "managerId"))
                .thenReturn(field("Employee", "managerId", "Manager", FieldType.MANY_TO_ONE, "Employee"));
        mm.when(() -> ModelManager.getModelField("Employee", "hrbpId"))
                .thenReturn(field("Employee", "hrbpId", "HRBP", FieldType.MANY_TO_ONE, "Employee"));
        mm.when(() -> ModelManager.getModelField("Employee", "code"))
                .thenReturn(field("Employee", "code", "Employee Code"));
        mm.when(() -> ModelManager.getModelField("Employee", "departmentId"))
                .thenReturn(field("Employee", "departmentId", "Department", FieldType.MANY_TO_ONE, "Department"));
        mm.when(() -> ModelManager.getModelField("Employee", "employeeProfileId"))
                .thenReturn(field("Employee", "employeeProfileId", "Employee Profile", FieldType.ONE_TO_ONE, "EmployeeProfile"));
        mm.when(() -> ModelManager.getModelField("EmployeeProfile", "passType"))
                .thenReturn(field("EmployeeProfile", "passType", "Pass Type", FieldType.MANY_TO_ONE, "PassType"));
        mm.when(() -> ModelManager.getModelField("PassType", "name"))
                .thenReturn(field("PassType", "name", "Name"));
        mm.when(() -> ModelManager.getModelField("Department", "name"))
                .thenReturn(field("Department", "name", "Name"));
        mm.when(() -> ModelManager.getModelField("LeaveRequest", "employeeId"))
                .thenReturn(field("LeaveRequest", "employeeId", "Employee", FieldType.MANY_TO_ONE, "Employee"));
        mm.when(() -> ModelManager.getLastFieldOfCascaded("LeaveRequest", "startDate"))
                .thenReturn(field("LeaveRequest", "startDate", "Start Date"));
    }

    @Test
    void aFieldOfARelatedRecordIsHeadedByItsRelation_aFieldOfTheRowsOwnSubRecordIsNot() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            stubModels(mm);

            // Two relations onto one model: under the last label alone, both columns read "Employee Code".
            assertThat(ExportColumnHeaders.of("Employee", "managerId.code")).isEqualTo("Manager › Employee Code");
            assertThat(ExportColumnHeaders.of("Employee", "hrbpId.code")).isEqualTo("HRBP › Employee Code");
            // The row's own sub-record reads as the row's own field, as the export dialog lists it.
            assertThat(ExportColumnHeaders.of("Employee", "employeeProfileId.passType")).isEqualTo("Pass Type");
            assertThat(ExportColumnHeaders.of("Employee", "employeeProfileId.passType.name"))
                    .isEqualTo("Pass Type › Name");
        }
    }

    @Test
    void aFieldTwoRelationsDownIsHeadedByBoth() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            stubModels(mm);

            assertThat(ExportColumnHeaders.of("LeaveRequest", "employeeId.departmentId.name"))
                    .isEqualTo("Employee › Department › Name");
            assertThat(ExportColumnHeaders.of("LeaveRequest", "startDate")).isEqualTo("Start Date");
        }
    }

    /** A template column with no header of its own is headed as a dynamic export heads it; one with a header keeps it. */
    @Test
    void aTemplateColumnWithoutACustomHeaderIsHeadedByItsRelations() {
        ExportTemplate template = new ExportTemplate();
        template.setId(7L);
        template.setModelName("LeaveRequest");
        ExportTemplateFieldService fieldService = mock(ExportTemplateFieldService.class);
        when(fieldService.searchList(any(FlexQuery.class))).thenReturn(List.of(
                column("startDate", null, null),
                column("employeeId.departmentId.name", " ", null),
                column("employeeId.code", "Staff No.", null),
                column("employeeId.managerId.code", null, true)));
        ExportTemplateResolver resolver = new ExportTemplateResolver();
        ReflectionTestUtils.setField(resolver, "exportTemplateFieldService", fieldService);

        ResolvedTemplateSheet sheet;
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            stubModels(mm);
            sheet = resolver.resolve(template);
        }

        assertThat(sheet.getHeaders()).containsExactly("Start Date", "Employee › Department › Name", "Staff No.");
        assertThat(sheet.getExportFields())
                .containsExactly("startDate", "employeeId.departmentId.name", "employeeId.code");
        // An ignored column is still read — a custom handler may use it — but is neither written nor headed.
        assertThat(sheet.getFetchFields()).contains("employeeId.managerId.code");
    }

    private static ExportTemplateField column(String fieldName, String customHeader, Boolean ignored) {
        ExportTemplateField column = new ExportTemplateField();
        column.setFieldName(fieldName);
        column.setCustomHeader(customHeader);
        column.setIgnored(ignored);
        return column;
    }
}
