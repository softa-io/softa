package io.softa.framework.orm.jdbc.pipeline;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.domain.EvalContext;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.FieldConstraints;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.service.validation.WriteValidationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The conditional rules against real write shapes — above all the update whose patch does not carry
 * the field being judged. That is the hole the frontend cannot reproduce (the form re-evaluates on
 * every keystroke) and only an import or a direct call can open.
 */
class FieldConstraintsEnforcerTest {

    private static final Map<String, FieldType> TYPES = Map.of(
            "reason", FieldType.OPTION, "reasonDescription", FieldType.STRING, "status", FieldType.OPTION,
            "amount", FieldType.BIG_DECIMAL, "startDate", FieldType.DATE, "endDate", FieldType.DATE,
            "checkInStatus", FieldType.OPTION, "lateMinutes", FieldType.INTEGER, "costCentreId", FieldType.LONG,
            "tags", FieldType.MULTI_OPTION);

    private static MetaField field(String name, FieldConstraints constraints) {
        return field(name, constraints, null);
    }

    private static MetaField field(String name, FieldConstraints constraints, Object defaultValue) {
        MetaField f = new MetaField();
        ReflectionTestUtils.setField(f, "modelName", "EmpChangeRequest");
        ReflectionTestUtils.setField(f, "fieldName", name);
        ReflectionTestUtils.setField(f, "fieldType", TYPES.get(name));
        ReflectionTestUtils.setField(f, "constraints", constraints);
        ReflectionTestUtils.setField(f, "defaultValueObject", defaultValue);
        return f;
    }

    private static FieldConstraints c(String requiredWhen, String hiddenWhen, String readonlyWhen, String invalidWhen, String message) {
        return FieldConstraints.of(null, null, null, message, requiredWhen, hiddenWhen, readonlyWhen, invalidWhen, "EmpChangeRequest.x");
    }

    /** Every field of the model the tests use, typed; a name outside TYPES is "no such field". */
    private static MetaField fieldOf(String name) {
        return TYPES.containsKey(name) ? field(name, null) : null;
    }

    private static FieldConstraintsEnforcer enforcer(AccessType type, MetaField... fields) {
        return enforcer(type, FieldConstraintsEnforcerTest::fieldOf, (model, path, id) -> null, fields);
    }

    private static FieldConstraintsEnforcer enforcer(AccessType type, java.util.function.Function<String, MetaField> fieldOf,
                                                     FieldConstraintsEnforcer.RelatedRowReader reader, MetaField... fields) {
        EvalContext ctx = new EvalContext(type, 7L, LocalDate.of(2026, 9, 11), LocalDateTime.of(2026, 9, 11, 9, 0));
        return new FieldConstraintsEnforcer("EmpChangeRequest", type, List.of(fields), fieldOf, ctx, reader);
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> row = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            row.put((String) kv[i], kv[i + 1]);
        }
        return row;
    }

    private static final MetaField REASON_DESCRIPTION =
            field("reasonDescription", c("[[\"reason\", \"=\", \"Others\"]]", null, null, null, null));

    @Test
    void onCreateAConditionalRequiredFieldIsDemandedOnlyWhenItsConditionHolds() {
        FieldConstraintsEnforcer e = enforcer(AccessType.CREATE, REASON_DESCRIPTION);
        assertThatThrownBy(() -> e.enforceCreate(row("reason", "Others")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reasonDescription");
        assertThatCode(() -> e.enforceCreate(row("reason", "Relocation"))).doesNotThrowAnyException();
        assertThatCode(() -> e.enforceCreate(row("reason", "Others", "reasonDescription", "moved"))).doesNotThrowAnyException();
    }

    @Test
    void onUpdateTheDocumentedTraceHolds_patchChangesReasonOnly() {
        // stored: {reason: Relocation, reasonDescription: null}; patch: {reason: Others}
        FieldConstraintsEnforcer e = enforcer(AccessType.UPDATE, REASON_DESCRIPTION);
        Map<String, Object> original = row("id", 7L, "reason", "Relocation", "reasonDescription", null);
        Map<String, Object> patch = row("id", 7L, "reason", "Others");
        Map<String, Object> merged = new HashMap<>(original);
        merged.putAll(patch);
        assertThatThrownBy(() -> e.enforceUpdate(merged, patch, original))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reasonDescription");
    }

    @Test
    void onUpdateAnUnrelatedEditDoesNotWakeTheRule() {
        FieldConstraintsEnforcer e = enforcer(AccessType.UPDATE, REASON_DESCRIPTION);
        Map<String, Object> original = row("id", 7L, "reason", "Others", "reasonDescription", null, "status", "Draft");
        Map<String, Object> patch = row("id", 7L, "status", "Approved");
        Map<String, Object> merged = new HashMap<>(original);
        merged.putAll(patch);
        assertThatCode(() -> e.enforceUpdate(merged, patch, original)).doesNotThrowAnyException();
    }

    @Test
    void requiredWhenTrueCannotBeClearedButMayBeOmitted() {
        MetaField costCentre = field("costCentreId", c("true", null, null, null, null));
        FieldConstraintsEnforcer create = enforcer(AccessType.CREATE, costCentre);
        assertThatThrownBy(() -> create.enforceCreate(row("status", "Draft"))).hasMessageContaining("costCentreId");

        FieldConstraintsEnforcer update = enforcer(AccessType.UPDATE, costCentre);
        Map<String, Object> original = row("id", 1L, "costCentreId", null, "status", "Draft");
        Map<String, Object> omit = row("id", 1L, "status", "Active");
        Map<String, Object> mergedOmit = new HashMap<>(original);
        mergedOmit.putAll(omit);
        assertThatCode(() -> update.enforceUpdate(mergedOmit, omit, original)).doesNotThrowAnyException();
        Map<String, Object> clear = row("id", 1L, "costCentreId", null);
        Map<String, Object> mergedClear = new HashMap<>(original);
        mergedClear.putAll(clear);
        assertThatThrownBy(() -> update.enforceUpdate(mergedClear, clear, original)).hasMessageContaining("costCentreId");
    }

    @Test
    void hiddenFieldsAreNotJudged() {
        MetaField lateMinutes = field("lateMinutes",
                c("true", "[[\"checkInStatus\", \"=\", \"Normal\"]]", null, null, null));
        FieldConstraintsEnforcer e = enforcer(AccessType.CREATE, lateMinutes);
        assertThatCode(() -> e.enforceCreate(row("checkInStatus", "Normal"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> e.enforceCreate(row("checkInStatus", "Late"))).hasMessageContaining("lateMinutes");
    }

    @Test
    void onCreateAConditionReadsTheDefaultsTheChainWillFillIn() {
        // status defaults to Draft; a form that never sends it must be judged as Draft, not as empty
        MetaField statusWithDefault = field("status", null, "Draft");
        MetaField amount = field("amount", c("[[\"status\", \"=\", \"Draft\"]]", null, null, null, null));
        FieldConstraintsEnforcer e = enforcer(AccessType.CREATE,
                name -> "status".equals(name) ? statusWithDefault : fieldOf(name), (m, p, id) -> null, amount);
        assertThatThrownBy(() -> e.enforceCreate(row("reason", "Others"))).hasMessageContaining("required");
        // an explicit null takes the default too — the chain's computeIfAbsent does the same
        assertThatThrownBy(() -> e.enforceCreate(row("status", null))).hasMessageContaining("required");
        // the default never enters the row that is written
        Map<String, Object> sent = row("status", "Approved");
        assertThatCode(() -> e.enforceCreate(sent)).doesNotThrowAnyException();
        assertThat(sent).doesNotContainKey("amount").containsEntry("status", "Approved");
    }

    @Test
    void readonlyWhenComparesTheAssignedValueUnderTheFieldType() {
        MetaField amount = field("amount", c(null, null, "[[\"status\", \"!=\", \"Draft\"]]", null, null));
        FieldConstraintsEnforcer e = enforcer(AccessType.UPDATE, amount);
        Map<String, Object> original = row("id", 1L, "status", "Approved", "amount", "10.00");
        Map<String, Object> sameAmount = row("id", 1L, "amount", 10);
        Map<String, Object> merged = new HashMap<>(original);
        merged.putAll(sameAmount);
        assertThatCode(() -> e.enforceUpdate(merged, sameAmount, original)).doesNotThrowAnyException();
    }

    @Test
    void anUnchangedMultiValueFieldIsNotAnAssignment() {
        // the plain case: both sides already the same list object-for-object
        MetaField tags = field("tags", c(null, null, "[[\"status\", \"!=\", \"Draft\"]]", null, null));
        FieldConstraintsEnforcer e = enforcer(AccessType.UPDATE, tags);
        Map<String, Object> original = row("id", 1L, "status", "Approved", "tags", List.of("a", "b"));
        Map<String, Object> same = row("id", 1L, "tags", List.of("a", "b"));
        Map<String, Object> mergedSame = new HashMap<>(original);
        mergedSame.putAll(same);
        assertThatCode(() -> e.enforceUpdate(mergedSame, same, original)).doesNotThrowAnyException();
        Map<String, Object> changed = row("id", 1L, "tags", List.of("a"));
        Map<String, Object> mergedChanged = new HashMap<>(original);
        mergedChanged.putAll(changed);
        assertThatThrownBy(() -> e.enforceUpdate(mergedChanged, changed, original)).hasMessageContaining("readonly");
    }

    @Test
    void aMultiValueFieldIsComparedAsASetAcrossItsTwoShapes() {
        // the patch carries a list, the stored row the comma-joined text the write produced
        MetaField tags = field("tags", c(null, null, "[[\"status\", \"!=\", \"Draft\"]]", null, null));
        FieldConstraintsEnforcer e = enforcer(AccessType.UPDATE, tags);
        Map<String, Object> original = row("id", 1L, "status", "Approved", "tags", "a,b");
        for (Object unchanged : List.of(List.of("a", "b"), List.of("b", "a"))) {
            Map<String, Object> patch = row("id", 1L, "tags", unchanged);
            Map<String, Object> merged = new HashMap<>(original);
            merged.putAll(patch);
            assertThatCode(() -> e.enforceUpdate(merged, patch, original))
                    .as("%s against a,b", unchanged).doesNotThrowAnyException();
        }
        Map<String, Object> changed = row("id", 1L, "tags", List.of("a", "c"));
        Map<String, Object> mergedChanged = new HashMap<>(original);
        mergedChanged.putAll(changed);
        assertThatThrownBy(() -> e.enforceUpdate(mergedChanged, changed, original)).hasMessageContaining("readonly");
        // and an untouched multi-select that submits an empty list against a stored null is no assignment
        MetaField tags2 = field("tags", c(null, null, "[[\"status\", \"!=\", \"Draft\"]]", null, null));
        FieldConstraintsEnforcer e2 = enforcer(AccessType.UPDATE, tags2);
        Map<String, Object> emptyOriginal = row("id", 1L, "status", "Approved", "tags", null);
        Map<String, Object> emptyPatch = row("id", 1L, "tags", List.of());
        Map<String, Object> mergedEmpty = new HashMap<>(emptyOriginal);
        mergedEmpty.putAll(emptyPatch);
        assertThatCode(() -> e2.enforceUpdate(mergedEmpty, emptyPatch, emptyOriginal)).doesNotThrowAnyException();
    }

    @Test
    void theDeclaredMessageIsShownAsWrittenNotAsAFormatPattern() {
        MetaField endDate = field("endDate", c(null, null, null,
                "[[\"endDate\", \"<\", \"{{ @startDate }}\"]]", "Use the form {CC}-{NNNN}; it's the end date's rule."));
        FieldConstraintsEnforcer e = enforcer(AccessType.CREATE, endDate);
        assertThatThrownBy(() -> e.enforceCreate(row("startDate", "2026-07-01", "endDate", "2026-01-01")))
                .isInstanceOf(WriteValidationException.class)
                .hasMessage("Use the form {CC}-{NNNN}; it's the end date's rule.");
    }

    @Test
    void readonlyWhenRejectsAnAssignmentNotAnUnchangedValue() {
        MetaField amount = field("amount", c(null, null, "[[\"status\", \"!=\", \"Draft\"]]", null, null));
        FieldConstraintsEnforcer e = enforcer(AccessType.UPDATE, amount);
        Map<String, Object> original = row("id", 1L, "status", "Approved", "amount", "100");
        Map<String, Object> change = row("id", 1L, "amount", 200);
        Map<String, Object> mergedChange = new HashMap<>(original);
        mergedChange.putAll(change);
        assertThatThrownBy(() -> e.enforceUpdate(mergedChange, change, original)).hasMessageContaining("readonly");
        Map<String, Object> same = row("id", 1L, "amount", "100");
        Map<String, Object> mergedSame = new HashMap<>(original);
        mergedSame.putAll(same);
        assertThatCode(() -> e.enforceUpdate(mergedSame, same, original)).doesNotThrowAnyException();
        Map<String, Object> draft = row("id", 1L, "status", "Draft", "amount", 300);
        assertThatCode(() -> e.enforceUpdate(new HashMap<>(draft), draft, row("id", 1L, "status", "Draft", "amount", "1")))
                .doesNotThrowAnyException();
    }

    @Test
    void invalidWhenRejectsWithTheDeclaredMessageAndReadsTheMergedRow() {
        MetaField endDate = field("endDate", c(null, null, null,
                "[[\"endDate\", \"<\", \"{{ @startDate }}\"]]", "End date cannot precede start date."));
        FieldConstraintsEnforcer e = enforcer(AccessType.UPDATE, endDate);
        // the patch moves startDate past the stored endDate — the rule wakes because startDate is referenced
        Map<String, Object> original = row("id", 1L, "startDate", "2026-01-01", "endDate", "2026-06-30");
        Map<String, Object> patch = row("id", 1L, "startDate", LocalDate.of(2026, 7, 1));
        Map<String, Object> merged = new HashMap<>(original);
        merged.putAll(patch);
        assertThatThrownBy(() -> e.enforceUpdate(merged, patch, original))
                .hasMessage("End date cannot precede start date.");
    }

    @Test
    void columnsToReadRegistersBothDirections() {
        io.softa.framework.orm.meta.MetaModel model = new io.softa.framework.orm.meta.MetaModel();
        ReflectionTestUtils.invokeMethod(model, "addConditionalField", REASON_DESCRIPTION);
        assertThat(FieldConstraintsEnforcer.columnsToRead(model, Set.of("reason"), FieldConstraintsEnforcerTest::fieldOf))
                .containsExactlyInAnyOrder("reason", "reasonDescription");
        assertThat(FieldConstraintsEnforcer.columnsToRead(model, Set.of("reasonDescription"), FieldConstraintsEnforcerTest::fieldOf))
                .containsExactlyInAnyOrder("reason", "reasonDescription");
        assertThat(FieldConstraintsEnforcer.columnsToRead(model, Set.of("status"), FieldConstraintsEnforcerTest::fieldOf)).isEmpty();
    }

    // ---- a condition on a dynamic cascaded field: `bankCode` = bankId.code, no column of its own ----

    private static MetaField bankId() {
        MetaField f = field("bankId", null);
        ReflectionTestUtils.setField(f, "fieldType", FieldType.MANY_TO_ONE);
        ReflectionTestUtils.setField(f, "relatedModel", "Bank");
        return f;
    }

    private static MetaField bankCode() {
        MetaField f = field("bankCode", null);
        ReflectionTestUtils.setField(f, "fieldType", FieldType.STRING);
        ReflectionTestUtils.setField(f, "cascadedField", "bankId.code");
        ReflectionTestUtils.setField(f, "dynamic", true);
        ReflectionTestUtils.setField(f, "dependentFields", List.of("bankId", "code"));
        return f;
    }

    private static final MetaField ORGANISATION_ID =
            field("organisationId", c("[[\"bankCode\", \"=\", \"DBS\"]]", null, null, null, null));

    private static MetaField bankName() {
        MetaField f = field("bankName", null);
        ReflectionTestUtils.setField(f, "fieldType", FieldType.STRING);
        ReflectionTestUtils.setField(f, "cascadedField", "bankId.name");
        ReflectionTestUtils.setField(f, "dynamic", true);
        ReflectionTestUtils.setField(f, "dependentFields", List.of("bankId", "name"));
        return f;
    }

    private static MetaField bankFieldOf(String name) {
        return switch (name) {
            case "bankId" -> bankId();
            case "bankCode" -> bankCode();
            case "bankName" -> bankName();
            case "organisationId", "branchNote" -> field(name, null);
            default -> fieldOf(name);
        };
    }

    @Test
    void twoCascadedReferencesToTheSameRelatedRowEachReadTheirOwnAttribute() {
        // the read selects one attribute, so the cache must not answer a second reference from the
        // row fetched for the first — it would find the column missing and resolve to null
        MetaField branchNote = field("branchNote", c(null, null, null,
                "[[\"bankName\", \"=\", \"DBS Bank Ltd\"]]", "Branch note does not apply to this bank."));
        List<String> reads = new java.util.ArrayList<>();
        FieldConstraintsEnforcer.RelatedRowReader reader = (model, path, id) -> {
            reads.add(model + "/" + path + "/" + id);
            return Map.of("id", id, path, "code".equals(path) ? "DBS" : "DBS Bank Ltd");
        };
        FieldConstraintsEnforcer e = enforcer(AccessType.CREATE, FieldConstraintsEnforcerTest::bankFieldOf,
                reader, ORGANISATION_ID, branchNote);
        assertThatThrownBy(() -> e.enforceCreate(row("bankId", 1L, "organisationId", "ORG-1")))
                .hasMessage("Branch note does not apply to this bank.");
        assertThat(reads).containsExactly("Bank/code/1", "Bank/name/1");
    }

    @Test
    void aDanglingForeignKeyIsLookedUpOnceForTheWholeWrite() {
        List<String> reads = new java.util.ArrayList<>();
        FieldConstraintsEnforcer.RelatedRowReader reader = (model, path, id) -> {
            reads.add(model + "/" + path + "/" + id);
            return null;
        };
        FieldConstraintsEnforcer e = enforcer(AccessType.CREATE, FieldConstraintsEnforcerTest::bankFieldOf,
                reader, ORGANISATION_ID);
        e.enforceCreate(row("bankId", 7L));
        e.enforceCreate(row("bankId", 7L));
        assertThat(reads).containsExactly("Bank/code/7");
    }

    @Test
    void aJsonFieldIsNotComparedAsCommaJoinedMembers() {
        // a JSON column also arrives as a list and stores as its own text; splitting it on commas
        // would make an untouched resubmission look like an assignment
        MetaField payload = field("payload", c(null, null, "[[\"status\", \"!=\", \"Draft\"]]", null, null));
        ReflectionTestUtils.setField(payload, "fieldType", FieldType.JSON);
        FieldConstraintsEnforcer e = enforcer(AccessType.UPDATE, payload);
        Map<String, Object> original = row("id", 1L, "status", "Approved", "payload", "[\"a\",\"b\"]");
        Map<String, Object> patch = row("id", 1L, "payload", List.of("a", "b"));
        Map<String, Object> merged = new HashMap<>(original);
        merged.putAll(patch);
        assertThatCode(() -> e.enforceUpdate(merged, patch, original)).doesNotThrowAnyException();

        // and the stored text as a database may hand it back: keys reordered, spaces added
        Map<String, Object> storedObject = row("id", 1L, "status", "Approved", "payload", "{\"id\": 1, \"name\": \"x\"}");
        Map<String, Object> objectPatch = row("id", 1L, "payload", new java.util.LinkedHashMap<>(
                java.util.Map.of("name", "x", "id", 1)));
        Map<String, Object> mergedObject = new HashMap<>(storedObject);
        mergedObject.putAll(objectPatch);
        assertThatCode(() -> e.enforceUpdate(mergedObject, objectPatch, storedObject)).doesNotThrowAnyException();
    }

    @Test
    void aDynamicCascadedReferenceIsFetchedFromTheRelatedRowByTheForeignKey() {
        List<String> reads = new java.util.ArrayList<>();
        FieldConstraintsEnforcer.RelatedRowReader reader = (model, path, id) -> {
            reads.add(model + "/" + path + "/" + id);
            return Map.of("id", id, "code", id.equals(1L) ? "DBS" : "OCBC");
        };
        FieldConstraintsEnforcer e = enforcer(AccessType.CREATE, FieldConstraintsEnforcerTest::bankFieldOf, reader, ORGANISATION_ID);

        assertThatThrownBy(() -> e.enforceCreate(row("bankId", 1L))).hasMessageContaining("organisationId");
        assertThatCode(() -> e.enforceCreate(row("bankId", 2L))).doesNotThrowAnyException();
        assertThatCode(() -> e.enforceCreate(row("bankId", 1L, "organisationId", "ORG-1"))).doesNotThrowAnyException();
        // one read per FK value for the whole write, and the row written is never touched
        assertThat(reads).containsExactly("Bank/code/1", "Bank/code/2");
        Map<String, Object> untouched = row("bankId", 2L);
        e.enforceCreate(untouched);
        assertThat(untouched).doesNotContainKey("bankCode");
    }

    @Test
    void theForeignKeyOfADynamicReferenceIsWhatTheUpdateReadsAndWhatWakesTheRule() {
        io.softa.framework.orm.meta.MetaModel model = new io.softa.framework.orm.meta.MetaModel();
        ReflectionTestUtils.invokeMethod(model, "addConditionalField", ORGANISATION_ID);
        assertThat(FieldConstraintsEnforcer.columnsToRead(model, Set.of("bankId"), FieldConstraintsEnforcerTest::bankFieldOf))
                .containsExactlyInAnyOrder("bankId", "organisationId");

        FieldConstraintsEnforcer e = enforcer(AccessType.UPDATE, FieldConstraintsEnforcerTest::bankFieldOf,
                (m, path, id) -> Map.of("code", "DBS"), ORGANISATION_ID);
        Map<String, Object> original = row("id", 9L, "bankId", 3L, "organisationId", null);
        Map<String, Object> patch = row("id", 9L, "bankId", 1L);
        Map<String, Object> merged = new HashMap<>(original);
        merged.putAll(patch);
        assertThatThrownBy(() -> e.enforceUpdate(merged, patch, original)).hasMessageContaining("organisationId");
    }
}
