package io.softa.framework.orm.jdbc.pipeline.processor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The write side of File / MultiFile fields.
 *
 * <p>Its CREATE branch used to {@code return} where it meant {@code continue}, which skipped the
 * conversion below it entirely. A {@code MULTI_FILE} value therefore reached JDBC as a live
 * {@code List}, the driver fell back to Java serialisation, and MySQL rejected {@code 0xACED0005...}
 * as an "Incorrect string value" — a message that reads like a charset problem and is really a missing
 * conversion. Only creates were affected; updates took the other branch and converted fine.
 *
 * <p>The same {@code return} also abandoned the loop, so on a model with several File fields only the
 * first was ever checked for required-ness.
 */
class FilesGroupInputTest {

    /** MetaField's setters are package-private, so the fixture is a stub rather than a real instance. */
    private static MetaField field(String name, FieldType type, boolean required) {
        MetaField f = mock(MetaField.class);
        when(f.getModelName()).thenReturn("EmpTransferRequest");
        when(f.getFieldName()).thenReturn(name);
        when(f.getFieldType()).thenReturn(type);
        when(f.isRequired()).thenReturn(required);
        return f;
    }

    private static FilesGroupProcessor processor(AccessType accessType, MetaField first, MetaField... rest) {
        FilesGroupProcessor p = new FilesGroupProcessor(first, accessType, ConvertType.TYPE_CAST);
        for (MetaField f : rest) {
            p.addFileField(f);
        }
        return p;
    }

    @Test
    void aMultiFileListIsJoinedOnCreate() {
        Map<String, Object> row = new HashMap<>();
        row.put("attachments", List.of("101", "102"));

        processor(AccessType.CREATE, field("attachments", FieldType.MULTI_FILE, false))
                .processInputRow(row);

        assertEquals("101,102", row.get("attachments"),
                "an unconverted List reaches JDBC as a serialised object and MySQL rejects it");
    }

    @Test
    void aMultiFileListIsJoinedOnUpdate() {
        Map<String, Object> row = new HashMap<>();
        row.put("attachments", List.of("101"));

        processor(AccessType.UPDATE, field("attachments", FieldType.MULTI_FILE, false))
                .processInputRow(row);

        assertEquals("101", row.get("attachments"));
    }

    @Test
    void aFileIdIsCastToLongOnCreate() {
        Map<String, Object> row = new HashMap<>();
        row.put("photoFile", "101");

        processor(AccessType.CREATE, field("photoFile", FieldType.FILE, false))
                .processInputRow(row);

        assertEquals(101L, row.get("photoFile"));
    }

    /** The loop must not stop at the first field — every File field of the group gets converted. */
    @Test
    void everyFileFieldOfTheGroupIsConverted() {
        Map<String, Object> row = new HashMap<>();
        row.put("photoFile", "101");
        row.put("attachments", List.of("201", "202"));

        processor(AccessType.CREATE,
                field("photoFile", FieldType.FILE, false),
                field("attachments", FieldType.MULTI_FILE, false))
                .processInputRow(row);

        assertEquals(101L, row.get("photoFile"));
        assertEquals("201,202", row.get("attachments"));
    }

    /**
     * required is asserted per field, against that field's own metadata. A group processor holds one
     * metaField — the first — so the no-argument checks read the wrong flags for every other member.
     */
    @Test
    void aRequiredSecondFieldIsStillEnforcedOnCreate() {
        Map<String, Object> row = new HashMap<>();
        row.put("photoFile", "101");

        assertThrows(IllegalArgumentException.class, () ->
                processor(AccessType.CREATE,
                        field("photoFile", FieldType.FILE, false),
                        field("attachments", FieldType.MULTI_FILE, true))
                        .processInputRow(row));
    }

    /** An update that never mentions a field says nothing about it — including whether it is required. */
    @Test
    void anUpdateOmittingARequiredFieldIsLeftAlone() {
        Map<String, Object> row = new HashMap<>();
        row.put("photoFile", "101");

        processor(AccessType.UPDATE,
                field("photoFile", FieldType.FILE, false),
                field("attachments", FieldType.MULTI_FILE, true))
                .processInputRow(row);

        assertFalse(row.containsKey("attachments"), "an untouched field must not be materialised");
    }
}
