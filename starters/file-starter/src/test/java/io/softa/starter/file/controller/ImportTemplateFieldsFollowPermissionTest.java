package io.softa.starter.file.controller;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.starter.file.entity.ImportTemplate;
import io.softa.starter.file.entity.ImportTemplateField;
import io.softa.starter.file.enums.ImportRule;
import io.softa.starter.file.excel.imports.ImportFieldAccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The import dialog lists a template's columns from {@code listByModel}. A column the caller may not
 * write is left out there too — otherwise the dialog calls it required while the downloaded file does
 * not even have it.
 */
class ImportTemplateFieldsFollowPermissionTest {

    private static ImportTemplateField field(String name) {
        ImportTemplateField field = new ImportTemplateField();
        field.setFieldName(name);
        return field;
    }

    private static ImportTemplate template() {
        ImportTemplate template = new ImportTemplate();
        template.setModelName("Employee");
        template.setImportRule(ImportRule.CREATE_OR_UPDATE);
        template.setImportFields(List.of(field("fullName"), field("empBankAccountId.methodOfPayment")));
        return template;
    }

    private static ImportTemplateController controllerWithheld(Set<String> withheld) {
        ImportFieldAccess access = mock(ImportFieldAccess.class);
        when(access.withheld(eq("Employee"), eq(ImportRule.CREATE_OR_UPDATE), anyList())).thenReturn(withheld);
        ImportTemplateController controller = new ImportTemplateController();
        ReflectionTestUtils.setField(controller, "importFieldAccess", access);
        return controller;
    }

    @Test
    void aColumnTheCallerMayNotWriteIsNotListed() {
        ImportTemplate template = template();

        controllerWithheld(Set.of("empBankAccountId.methodOfPayment")).withoutUnwritableFields(template);

        assertThat(template.getImportFields()).extracting(ImportTemplateField::getFieldName).containsExactly("fullName");
    }

    @Test
    void aCallerBlockedFromNothingSeesEveryColumn() {
        ImportTemplate template = template();

        controllerWithheld(Set.of()).withoutUnwritableFields(template);

        assertThat(template.getImportFields()).hasSize(2);
    }
}
