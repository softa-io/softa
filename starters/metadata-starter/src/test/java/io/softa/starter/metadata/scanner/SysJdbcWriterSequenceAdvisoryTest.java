package io.softa.starter.metadata.scanner;

import org.junit.jupiter.api.Test;

import io.softa.starter.metadata.entity.SysField;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The rename advisories print copy-paste SQL that converges the per-tenant
 * {@code sys_sequence.code} strings after a declared rename (the scanner never
 * rewrites tenant data itself). Locks the hint composition, including the
 * combined model+field rename case where the two hints must chain.
 */
class SysJdbcWriterSequenceAdvisoryTest {

    private static SysField sysField(String modelName, String fieldName) {
        SysField field = new SysField();
        field.setModelName(modelName);
        field.setFieldName(fieldName);
        field.setAutoSequence(true);
        return field;
    }

    @Test
    void fieldRenameHint_swapsOnlyTheFieldPart() {
        assertEquals("UPDATE sys_sequence SET code = 'Employee.employeeNo' WHERE code = 'Employee.code';",
                SysJdbcWriter.fieldRenameSequenceHint(
                        sysField("Employee", "employeeNo"), sysField("Employee", "code")));
    }

    @Test
    void modelRenameHint_swapsOnlyTheModelPart() {
        assertEquals("UPDATE sys_sequence SET code = 'Staff.code' WHERE code = 'Employee.code';",
                SysJdbcWriter.modelRenameSequenceHint("Employee", "Staff", "code"));
    }

    @Test
    void combinedRename_hintsChain() {
        // Same-boot model + field rename: the model hint fires first (old field
        // name, old→new model), then the field hint continues from that state by
        // using the NEW model name on both sides.
        String modelHint = SysJdbcWriter.modelRenameSequenceHint("Employee", "Staff", "code");
        String fieldHint = SysJdbcWriter.fieldRenameSequenceHint(
                sysField("Staff", "employeeNo"), sysField("Employee", "code"));
        assertEquals("UPDATE sys_sequence SET code = 'Staff.code' WHERE code = 'Employee.code';", modelHint);
        assertEquals("UPDATE sys_sequence SET code = 'Staff.employeeNo' WHERE code = 'Staff.code';", fieldHint);
    }
}
