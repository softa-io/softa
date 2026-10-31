package io.softa.starter.file.excel.imports;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.PermissionService;
import io.softa.starter.file.enums.ImportRule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A column is withheld when the caller may not write the field it lands on. Without that, a caller
 * without a sensitive field set was handed a template that required a column the save then refused —
 * a file that could fail either way and never succeed.
 */
class ImportFieldAccessTest {

    private static final List<String> COLUMNS = List.of(
            "code", "departmentId.code", "empBankAccountId.methodOfPayment", "empBankAccountId.bank.name");

    private MockedStatic<ModelManager> models;
    private PermissionService permissions;
    private ImportFieldAccess access;

    @BeforeEach
    void setUp() {
        models = Mockito.mockStatic(ModelManager.class);
        models.when(() -> ModelManager.existModel(anyString())).thenReturn(true);
        stub("Employee", "code", FieldType.STRING, null);
        stub("Employee", "departmentId", FieldType.MANY_TO_ONE, "Department");
        stub("Employee", "empBankAccountId", FieldType.ONE_TO_ONE, "EmpBankAccount");
        stub("EmpBankAccount", "methodOfPayment", FieldType.OPTION, null);
        stub("EmpBankAccount", "bank", FieldType.MANY_TO_ONE, "Bank");
        permissions = mock(PermissionService.class);
        when(permissions.getUserBlockedModelFields(anyString(), any())).thenReturn(Set.of());
        access = new ImportFieldAccess(permissions);
    }

    @AfterEach
    void tearDown() {
        models.close();
    }

    private void stub(String model, String name, FieldType type, String related) {
        MetaField field = mock(MetaField.class);
        when(field.getModelName()).thenReturn(model);
        when(field.getFieldName()).thenReturn(name);
        when(field.getFieldType()).thenReturn(type);
        when(field.getRelatedModel()).thenReturn(related);
        models.when(() -> ModelManager.getModelFieldOrNull(model, name)).thenReturn(field);
    }

    private void blocked(String model, AccessType type, String... fields) {
        when(permissions.getUserBlockedModelFields(model, type)).thenReturn(Set.of(fields));
    }

    @Test
    void aCallerWithoutTheBankSetLosesTheBankColumnsOnly() {
        blocked("EmpBankAccount", AccessType.CREATE, "methodOfPayment", "bank");
        blocked("EmpBankAccount", AccessType.UPDATE, "methodOfPayment", "bank");

        // Asked of the field each column lands on: the bank account's own fields, the employee's
        // reference to the department — not of the employee's bank account relation as a whole.
        assertThat(access.withheld("Employee", ImportRule.CREATE_OR_UPDATE, COLUMNS))
                .containsExactlyInAnyOrder("empBankAccountId.methodOfPayment", "empBankAccountId.bank.name");
    }

    @Test
    void aCallerBlockedFromNothingKeepsEveryColumn() {
        // An admin or a consultant: the permission service reports nothing blocked.
        assertThat(access.withheld("Employee", ImportRule.CREATE_OR_UPDATE, COLUMNS)).isEmpty();
    }

    @Test
    void aCreateOrUpdateTemplateLosesAColumnRefusedEitherWay() {
        // The file decides row by row whether a row creates or updates, so the column has to work for both.
        blocked("EmpBankAccount", AccessType.UPDATE, "methodOfPayment");

        assertThat(access.withheld("Employee", ImportRule.CREATE_OR_UPDATE, COLUMNS))
                .containsExactly("empBankAccountId.methodOfPayment");
    }

    @Test
    void aCreateOnlyTemplateAsksOnlyAboutCreating() {
        blocked("EmpBankAccount", AccessType.UPDATE, "methodOfPayment");

        assertThat(access.withheld("Employee", ImportRule.ONLY_CREATE, COLUMNS)).isEmpty();
        verify(permissions, never()).getUserBlockedModelFields(anyString(), eq(AccessType.UPDATE));
    }

    @Test
    void aColumnWhoseFieldIsGoneIsLeftAloneRatherThanFailingTheTemplate() {
        // A template column can outlive its field. Resolving it strictly threw for every caller and took
        // the import dialog's template list down with it; the column fails on its own at import time.
        blocked("Employee", AccessType.CREATE, "code");
        blocked("Employee", AccessType.UPDATE, "code");

        assertThat(access.withheld("Employee", ImportRule.CREATE_OR_UPDATE,
                List.of("code", "uenRegistrationNo", "empBankAccountId.retired")))
                .containsExactly("code");
    }

    @Test
    void aLookupLandsOnTheReferenceItWrites() {
        // departmentId.code finds a department by its code and writes the employee's departmentId.
        blocked("Employee", AccessType.CREATE, "departmentId");
        blocked("Employee", AccessType.UPDATE, "departmentId");

        assertThat(access.withheld("Employee", ImportRule.CREATE_OR_UPDATE, COLUMNS))
                .containsExactly("departmentId.code");
    }
}
