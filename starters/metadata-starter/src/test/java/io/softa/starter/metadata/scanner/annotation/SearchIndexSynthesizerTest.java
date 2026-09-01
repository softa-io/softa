package io.softa.starter.metadata.scanner.annotation;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.IndexType;
import io.softa.starter.metadata.entity.SysModelIndex;
import io.softa.starter.metadata.scanner.annotation.SearchIndexSynthesizer.FieldSpec;
import io.softa.starter.metadata.scanner.annotation.SearchIndexSynthesizer.ModelSpec;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The derivation that spares every model a hand-written search index — see
 * {@link SearchIndexSynthesizer}.
 */
class SearchIndexSynthesizerTest {

    private static FieldSpec text(String fieldName, String columnName) {
        return new FieldSpec(fieldName, columnName, FieldType.STRING, false);
    }

    private static ModelSpec model(String modelName, String tableName, List<String> searchName,
                                   FieldSpec... fields) {
        return new ModelSpec(modelName, tableName, searchName, false, true, List.of(fields));
    }

    private static List<SysModelIndex> derive(ModelSpec... models) {
        return SearchIndexSynthesizer.derive(List.of(models), List.of());
    }

    @Test
    void explicitSearchName_yieldsOneTrigramIndexPerMember() {
        List<SysModelIndex> derived = derive(model("Department", "department", List.of("code", "name"),
                text("code", "code"), text("name", "name"), text("note", "note")));

        assertEquals(2, derived.size());
        SysModelIndex first = derived.get(0);
        assertEquals("idx_department_code_trgm", first.getIndexName());
        assertEquals("Department", first.getModelName());
        // Field names, not column names: the DDL context builder maps field -> column itself.
        assertEquals(List.of("code"), first.getIndexFields());
        assertEquals(IndexType.TRIGRAM, first.getIndexType());
        assertEquals(Boolean.FALSE, first.getUniqueIndex());
        assertNull(first.getMessage());
        assertEquals("idx_department_name_trgm", derived.get(1).getIndexName());
        // 'note' is a text column nobody searches — derivation is driven by intent, not by type.
        assertTrue(derived.stream().noneMatch(i -> i.getIndexName().contains("note")));
    }

    @Test
    void noSearchName_fallsBackToTheFieldCalledName() {
        List<SysModelIndex> derived = derive(model("Bank", "bank", List.of(),
                text("code", "code"), text("name", "name")));

        assertEquals(1, derived.size());
        assertEquals("idx_bank_name_trgm", derived.get(0).getIndexName());
    }

    @Test
    void noSearchNameAndNoNameField_derivesNothing() {
        // ModelManager resolves this model's searchName to ["id"], i.e. it has no text search.
        assertTrue(derive(model("PayRunLine", "pay_run_line", List.of(),
                text("code", "code"))).isEmpty());
    }

    @Test
    void nonStringNameField_isSkippedRatherThanDerived() {
        // ModelManager only asserts STRING for the EXPLICIT branch, so the implicit 'name'
        // fallback can legitimately land on a non-text column.
        ModelSpec spec = new ModelSpec("Shift", "shift", List.of(), false, true,
                List.of(new FieldSpec("name", "name", FieldType.INTEGER, false)));

        assertTrue(derive(spec).isEmpty());
    }

    @Test
    void dynamicField_isSkipped_itHasNoColumnToIndex() {
        ModelSpec spec = new ModelSpec("Report", "report", List.of("name"), false, true,
                List.of(new FieldSpec("name", "name", FieldType.STRING, true)));

        assertTrue(derive(spec).isEmpty());
    }

    @Test
    void projection_derivesNothing_itDoesNotOwnItsTable() {
        ModelSpec spec = new ModelSpec("BirthdayCountdown", "employee", List.of("fullName"), true, true,
                List.of(text("fullName", "full_name")));

        assertTrue(derive(spec).isEmpty());
    }

    @Test
    void nonRdbmsModel_derivesNothing() {
        ModelSpec spec = new ModelSpec("AuditTrail", "audit_trail", List.of("name"), false, false,
                List.of(text("name", "name")));

        assertTrue(derive(spec).isEmpty());
    }

    @Test
    void nameAlreadyClaimedByADeveloperDeclaration_isDropped() {
        ModelSpec spec = model("Department", "department", List.of("name"), text("name", "name"));

        assertTrue(SearchIndexSynthesizer.derive(List.of(spec),
                List.of("IDX_DEPARTMENT_NAME_TRGM")).isEmpty(),
                "index names are case-insensitive identifiers; the collision must still be seen");
    }

    @Test
    void twoModelsSharingATable_deriveTheNameOnce_ratherThanFailingTheBoot() {
        // ModelManager fails the boot on a duplicate index name. A derived index is never worth
        // that, so the second one is dropped instead.
        List<SysModelIndex> derived = derive(
                model("Employee", "employee", List.of("fullName"), text("fullName", "full_name")),
                model("EmployeeMirror", "employee", List.of("fullName"), text("fullName", "full_name")));

        assertEquals(1, derived.size());
        assertEquals("Employee", derived.get(0).getModelName());
    }

    @Test
    void overlongName_isShortenedDeterministicallyAndStaysWithinTheColumnWidth() {
        String table = "leave_request_rule_employment_type_rel";   // 38 chars, the real worst case
        String column = "counterparty_display_name";

        String first = SearchIndexSynthesizer.deriveIndexName(table, column);
        String second = SearchIndexSynthesizer.deriveIndexName(table, column);

        assertEquals(first, second, "an unstable name would churn the index on every boot");
        assertTrue(first.length() <= 60, "must fit sys_model_index.index_name: " + first);
        assertTrue(first.startsWith("idx_") && first.endsWith("_trgm"));
        // Both halves survive: a name that kept only the table would collide across its own columns.
        assertTrue(first.contains("leave_request"), first);
        assertTrue(first.contains("counterparty"), first);
    }

    @Test
    void shortenedNamesOfTwoColumnsOnOneLongTable_doNotCollide() {
        String table = "leave_request_rule_employment_type_rel";

        assertNotEquals(
                SearchIndexSynthesizer.deriveIndexName(table, "counterparty_display_name"),
                SearchIndexSynthesizer.deriveIndexName(table, "counterparty_display_label"));
    }

    @Test
    void shortNameIsLeftExactlyAsSpelled() {
        assertEquals("idx_employee_full_name_trgm",
                SearchIndexSynthesizer.deriveIndexName("employee", "full_name"));
    }
}
