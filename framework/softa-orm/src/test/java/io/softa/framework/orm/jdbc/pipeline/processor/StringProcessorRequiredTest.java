package io.softa.framework.orm.jdbc.pipeline.processor;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.sequence.SequenceService;
import io.softa.framework.orm.service.validation.WriteValidationException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * A required string field refuses blank, not only null.
 *
 * <p>It used to check null alone, so {@code {"name": ""}} saved a company with no name while the
 * form refused the same input — the form validates, the API did not. Every field this processor
 * handles was affected: STRING, TEXT, OPTION, and a relation keyed by a string code. MultiString,
 * Filters and Orders already asked the blank question; String, the type nearly every required
 * field is, was the one that did not.
 *
 * <p>The conditional {@code requiredWhen} rule already treats blank as missing, so before this a
 * field rejected "" when required by a condition and accepted it when required outright.
 */
class StringProcessorRequiredTest {

    private static final String MODEL = "Company";
    private static final String FIELD = "name";

    private static MetaField field(boolean required) {
        MetaField metaField = new MetaField();
        ReflectionTestUtils.setField(metaField, "modelName", MODEL);
        ReflectionTestUtils.setField(metaField, "fieldName", FIELD);
        ReflectionTestUtils.setField(metaField, "columnName", FIELD);
        ReflectionTestUtils.setField(metaField, "label", "Company Name");
        ReflectionTestUtils.setField(metaField, "fieldType", FieldType.STRING);
        ReflectionTestUtils.setField(metaField, "required", required);
        return metaField;
    }

    private static Map<String, Object> row(Object value) {
        Map<String, Object> row = new HashMap<>();
        row.put(FIELD, value);
        return row;
    }

    private static void write(boolean required, AccessType access, Map<String, Object> row) {
        new StringProcessor(field(required), access).processInputRow(row);
    }

    @Test
    void creatingWithARequiredFieldEmptyIsRefused() {
        WriteValidationException refused = assertThrows(WriteValidationException.class,
                () -> write(true, AccessType.CREATE, row("")));

        // On the field, so a form can put the sentence beside the box rather than in a toast.
        assertTrue(refused.fieldErrors().containsKey(FIELD));
    }

    @Test
    void theRefusalNamesTheFieldByItsLabel() {
        // Not "Model field Company:name ..." — the sentence reaches import failure reasons and toasts,
        // where the reader knows the field as its label. The model reads as its name here only
        // because no model is registered in this test.
        WriteValidationException refused = assertThrows(WriteValidationException.class,
                () -> write(true, AccessType.CREATE, row(null)));

        assertEquals("Company: Company Name is required.", refused.getMessage());
        assertEquals("Company: Company Name is required.", refused.fieldErrors().get(FIELD));
    }

    @Test
    void spacesAreAsEmptyAsNothing() {
        assertThrows(WriteValidationException.class, () -> write(true, AccessType.CREATE, row("   ")));
        assertThrows(WriteValidationException.class, () -> write(true, AccessType.UPDATE, row("\t ")));
    }

    @Test
    void clearingARequiredFieldOnUpdateIsRefused() {
        assertThrows(WriteValidationException.class, () -> write(true, AccessType.UPDATE, row("")));
    }

    @Test
    void nullIsStillRefusedAsBefore() {
        assertThrows(WriteValidationException.class, () -> write(true, AccessType.UPDATE, row(null)));
        assertThrows(WriteValidationException.class, () -> write(true, AccessType.CREATE, new HashMap<>()));
    }

    @Test
    void anUpdateThatDoesNotSendTheFieldIsNotChecked() {
        // A partial update: a patch that leaves the name out is not clearing it, and a row whose name
        // is already blank must stay editable in every other respect.
        Map<String, Object> patch = new HashMap<>();
        patch.put("code", "C001");

        assertDoesNotThrow(() -> write(true, AccessType.UPDATE, patch));
    }

    @Test
    void anOptionalFieldMayStillBeSentEmpty() {
        // Only the required check changed. An optional field sent "" is stored as "", as before.
        Map<String, Object> created = row("");
        write(false, AccessType.CREATE, created);
        assertEquals("", created.get(FIELD));

        Map<String, Object> updated = row("");
        write(false, AccessType.UPDATE, updated);
        assertEquals("", updated.get(FIELD));
    }

    @Test
    void aRequiredFieldWithAValueIsAcceptedAndTrimmed() {
        Map<String, Object> created = row("  Acme Pte Ltd  ");
        write(true, AccessType.CREATE, created);

        assertEquals("Acme Pte Ltd", created.get(FIELD));
    }

    @Test
    void aBlankAutoNumberedFieldIsNumberedBeforeItIsChecked() {
        // Why this change cannot refuse a blank code that a sequence fills: DataCreatePipeline runs
        // the sequence processor ahead of this one, and a blank value is exactly what the sequence
        // replaces. Composed here in that order — and the second assertion shows the order is what
        // carries it, because this processor on its own refuses the same row.
        MetaField code = field(true);
        ReflectionTestUtils.setField(code, "fieldName", "code");
        ReflectionTestUtils.setField(code, "columnName", "code");
        ReflectionTestUtils.setField(code, "autoSequence", true);
        SequenceService sequence = Mockito.mock(SequenceService.class);
        when(sequence.next(eq(MODEL + ".code"), anyMap())).thenReturn("CO-00001");

        Map<String, Object> created = new HashMap<>();
        created.put("code", "");
        new SequenceProcessor(code, AccessType.CREATE, sequence).processInputRow(created);
        new StringProcessor(code, AccessType.CREATE).processInputRow(created);
        assertEquals("CO-00001", created.get("code"));

        Map<String, Object> unnumbered = new HashMap<>();
        unnumbered.put("code", "");
        assertThrows(WriteValidationException.class,
                () -> new StringProcessor(code, AccessType.CREATE).processInputRow(unnumbered));
    }
}
