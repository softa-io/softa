package io.softa.starter.file.excel.imports;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.ValidationException;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.validation.WriteValidationException;
import io.softa.starter.file.dto.ImportFieldDTO;
import io.softa.starter.file.dto.ImportTemplateDTO;
import io.softa.starter.file.excel.imports.handler.BaseImportHandler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Who enforces a column the MODEL requires but the template leaves optional.
 *
 * <p>The importer used to check it on every blank cell. A cell cannot tell a create from an update,
 * so with {@code ignoreEmpty} on — where a blank means "keep the existing value" — every update row
 * that left Company Name blank was refused, though the write would have left the name alone. Now the
 * importer checks it only when the blank would be written as null; otherwise the ORM, which does
 * know create from update, refuses the create and lets the update through.
 */
class ImportModelRequiredTest {

    private static final String MODEL = "Company";

    @Test
    void ignoreEmptyOn_aBlankModelRequiredCell_isDroppedNotRefused() {
        try (MockedStatic<ModelManager> mm = stubName(true)) {
            ImportFieldDTO column = column("name", "Company Name", false, true);
            BaseImportHandler handler = onlyHandler(column);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", "");
            handler.handleRow(row);

            // Absent, not null: the ORM leaves an absent field alone on update.
            assertFalse(row.containsKey("name"));
            assertNotEquals(Boolean.TRUE, column.getRequired());
        }
    }

    @Test
    void ignoreEmptyOff_aBlankModelRequiredCell_isStillRefusedByColumnName() {
        try (MockedStatic<ModelManager> mm = stubName(true)) {
            BaseImportHandler handler = onlyHandler(column("name", "Company Name", false, false));

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", "");
            ValidationException e = assertThrows(ValidationException.class, () -> handler.handleRow(row));
            assertEquals("The field `Company Name` is required", e.getMessage());
        }
    }

    /** The template's own flag is a rule for every row and is not relaxed by ignoreEmpty. */
    @Test
    void ignoreEmptyOn_aTemplateRequiredCell_isStillRefused() {
        try (MockedStatic<ModelManager> mm = stubName(false)) {
            BaseImportHandler handler = onlyHandler(column("name", "Company Name", true, true));

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", "");
            assertThrows(ValidationException.class, () -> handler.handleRow(row));
        }
    }

    /** A lookup column follows the same rule: RelationLookupResolver leaves the relation unwritten. */
    @Test
    void ignoreEmptyOn_aRequiredLookup_getsNoImporterCheck() {
        MetaField legalEntity = Mockito.mock(MetaField.class);
        Mockito.when(legalEntity.getFieldType()).thenReturn(FieldType.MANY_TO_ONE);
        Mockito.when(legalEntity.getRelatedModel()).thenReturn("LegalEntity");
        Mockito.when(legalEntity.isRequired()).thenReturn(true);
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existField("Department", "legalEntityId")).thenReturn(true);
            mm.when(() -> ModelManager.getModelField("Department", "legalEntityId")).thenReturn(legalEntity);

            ImportFieldDTO column = column("legalEntityId.code", "Legal Entity Code", false, true);
            assertNull(new ImportHandlerFactory()
                    .createLookupRequiredHandler("Department", "legalEntityId.code", column));
        }
    }

    // ------------------------------------------------- the create the ORM refuses

    @Test
    void theOrmsRequiredRefusal_isReportedByTheColumnHeader() {
        try (MockedStatic<ModelManager> mm = stubName(true)) {
            RuntimeException ormRefusal = WriteValidationException.forField("name",
                    "Model field {0}:{1} is a required field and cannot be null!", MODEL, "name");

            String reason = ImportPersistenceService.failureReason(
                    template(column("name", "Company Name", false, true)), new HashMap<>(), ormRefusal);

            assertEquals("The field `Company Name` is required", reason);
        }
    }

    @Test
    void aLookupColumn_isNamedForTheRelationItWrites() {
        try (MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class)) {
            mm.when(() -> ModelManager.existField(MODEL, "legalEntityId")).thenReturn(true);
            mm.when(() -> ModelManager.getModelField(MODEL, "legalEntityId"))
                    .thenReturn(field("legalEntityId", "Legal Entity", true));
            RuntimeException ormRefusal = WriteValidationException.forField("legalEntityId", "required");

            String reason = ImportPersistenceService.failureReason(
                    template(column("legalEntityId.code", "Legal Entity Code", false, true)),
                    new HashMap<>(), ormRefusal);

            assertEquals("The field `Legal Entity Code` is required", reason);
        }
    }

    /** Only a refusal that is certainly the missing-value one is reworded. */
    @Test
    void aFieldErrorOnAValueTheRowCarried_keepsTheOrmsMessage() {
        try (MockedStatic<ModelManager> mm = stubName(true)) {
            RuntimeException tooLong = WriteValidationException.forField("name", "Name is too long");
            Map<String, Object> row = new HashMap<>();
            row.put("name", "x".repeat(300));

            String reason = ImportPersistenceService.failureReason(
                    template(column("name", "Company Name", false, true)), row, tooLong);

            assertEquals(tooLong.getMessage(), reason);
        }
    }

    @Test
    void anythingButAWriteValidationError_keepsItsMessage() {
        RuntimeException other = new IllegalStateException("duplicate key");
        assertEquals("duplicate key", ImportPersistenceService.failureReason(
                template(column("name", "Company Name", false, true)), new HashMap<>(), other));
    }

    private static MockedStatic<ModelManager> stubName(boolean modelRequired) {
        MockedStatic<ModelManager> mm = Mockito.mockStatic(ModelManager.class);
        mm.when(() -> ModelManager.existField(MODEL, "name")).thenReturn(true);
        mm.when(() -> ModelManager.getModelField(MODEL, "name")).thenReturn(field("name", "Name", modelRequired));
        return mm;
    }

    private static MetaField field(String fieldName, String label, boolean required) {
        MetaField metaField = new MetaField();
        ReflectionTestUtils.setField(metaField, "modelName", MODEL);
        ReflectionTestUtils.setField(metaField, "fieldName", fieldName);
        ReflectionTestUtils.setField(metaField, "label", label);
        ReflectionTestUtils.setField(metaField, "fieldType", FieldType.STRING);
        ReflectionTestUtils.setField(metaField, "required", required);
        return metaField;
    }

    private static ImportFieldDTO column(String fieldName, String header, boolean required, boolean ignoreEmpty) {
        ImportFieldDTO column = new ImportFieldDTO();
        column.setFieldName(fieldName);
        column.setHeader(header);
        column.setRequired(required);
        column.setIgnoreEmpty(ignoreEmpty);
        return column;
    }

    private static ImportTemplateDTO template(ImportFieldDTO column) {
        ImportTemplateDTO template = new ImportTemplateDTO();
        template.setModelName(MODEL);
        template.setImportFields(List.of(column));
        return template;
    }

    private static BaseImportHandler onlyHandler(ImportFieldDTO column) {
        List<BaseImportHandler> handlers = new ImportHandlerFactory().createHandlers(template(column));
        assertEquals(1, handlers.size());
        return handlers.getFirst();
    }
}
