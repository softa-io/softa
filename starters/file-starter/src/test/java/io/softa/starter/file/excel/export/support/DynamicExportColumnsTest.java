package io.softa.starter.file.excel.export.support;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.LookupKeys;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.file.excel.export.support.DynamicExportColumns.Column;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

/**
 * A relation looked up by a key other than its name is exported as two columns, the name and the key,
 * so the sheet imports back as it came out: the import maps a relation by its key, while the export
 * writes its name.
 */
class DynamicExportColumnsTest {

    private static final String MODEL = "Employee";

    private static MetaField field(String modelName, String fieldName, String label, FieldType type, String related) {
        MetaField metaField = new MetaField();
        ReflectionTestUtils.setField(metaField, "modelName", modelName);
        ReflectionTestUtils.setField(metaField, "fieldName", fieldName);
        ReflectionTestUtils.setField(metaField, "fieldType", type);
        ReflectionTestUtils.setField(metaField, "label", label);
        ReflectionTestUtils.setField(metaField, "relatedModel", related);
        return metaField;
    }

    private final MetaField code = field(MODEL, "code", "Employee Code", FieldType.STRING, null);
    private final MetaField department = field(MODEL, "departmentId", "Department", FieldType.MANY_TO_ONE, "Department");
    private final MetaField departmentCode = field("Department", "code", "Code", FieldType.STRING, null);
    private final MetaField company = field(MODEL, "companyId", "Company", FieldType.MANY_TO_ONE, "Company");
    private final MetaField country = field(MODEL, "country", "Country", FieldType.MANY_TO_ONE, "CountryRegion");
    private final MetaField bankName = field("EmpBankAccount", "bankId", "Bank", FieldType.MANY_TO_ONE, "Bank");

    private List<Column> columnsOf(List<String> picked) {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class);
             MockedStatic<LookupKeys> keys = Mockito.mockStatic(LookupKeys.class)) {
            mm.when(() -> ModelManager.getLastFieldOfCascaded(MODEL, "code")).thenReturn(code);
            mm.when(() -> ModelManager.getLastFieldOfCascaded(MODEL, "departmentId")).thenReturn(department);
            mm.when(() -> ModelManager.getLastFieldOfCascaded(MODEL, "departmentId.code")).thenReturn(departmentCode);
            mm.when(() -> ModelManager.getLastFieldOfCascaded(MODEL, "companyId")).thenReturn(company);
            mm.when(() -> ModelManager.getLastFieldOfCascaded(MODEL, "country")).thenReturn(country);
            mm.when(() -> ModelManager.getLastFieldOfCascaded(MODEL, "bankAccountId.bankId")).thenReturn(bankName);
            mm.when(() -> ModelManager.getModelField("Department", "code")).thenReturn(departmentCode);
            mm.when(() -> ModelManager.getModelDisplayName("Department")).thenReturn(List.of("name"));
            // A company is looked up by its name, the very thing it displays.
            mm.when(() -> ModelManager.getModelDisplayName("Company")).thenReturn(List.of("name"));
            keys.when(() -> LookupKeys.of(any())).thenReturn(List.of());
            keys.when(() -> LookupKeys.of(department)).thenReturn(List.of("code"));
            keys.when(() -> LookupKeys.of(company)).thenReturn(List.of("name"));
            keys.when(() -> LookupKeys.of(bankName)).thenReturn(List.of("code"));
            return DynamicExportColumns.of(MODEL, picked);
        }
    }

    @Test
    void aRelationLookedUpByItsCodeWritesItsNameAndThenItsCode() {
        assertThat(columnsOf(List.of("code", "departmentId"))).containsExactly(
                new Column("code", "Employee Code"),
                new Column("departmentId", "Department (Name)"),
                new Column("departmentId.code", "Department (Code)"));
    }

    @Test
    void aRelationLookedUpByTheNameItDisplaysKeepsOneColumn() {
        assertThat(columnsOf(List.of("companyId"))).containsExactly(new Column("companyId", "Company"));
    }

    @Test
    void aRelationWithNoLookupKeyKeepsOneColumn() {
        // A code-as-id master: the id it writes is the code a person types.
        assertThat(columnsOf(List.of("country"))).containsExactly(new Column("country", "Country"));
    }

    @Test
    void aSubRecordsRelationKeepsOneColumn() {
        assertThat(columnsOf(List.of("bankAccountId.bankId"))).containsExactly(
                new Column("bankAccountId.bankId", "Bank"));
    }

    @Test
    void aKeyPathPickedOnItsOwnIsNotRepeated() {
        assertThat(columnsOf(List.of("departmentId", "departmentId.code"))).containsExactly(
                new Column("departmentId", "Department (Name)"),
                new Column("departmentId.code", "Code"));
    }
}
