package io.softa.framework.orm.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The semantics the frontend evaluator must share, pinned one by one. The SQL compiler of the same
 * tree is not the reference here — where the two differ (null handling, negation) the answer written
 * in {@link FilterEvaluator}'s contract is.
 */
class FilterEvaluatorTest {

    private static final Map<String, FieldType> TYPES = Map.of(
            "reason", FieldType.OPTION, "country", FieldType.STRING, "startDate", FieldType.DATE,
            "endDate", FieldType.DATE, "hireDate", FieldType.DATE, "dateOfBirth", FieldType.DATE,
            "amount", FieldType.BIG_DECIMAL, "headcount", FieldType.INTEGER, "isPrimary", FieldType.BOOLEAN,
            "tags", FieldType.MULTI_OPTION);
    private static final Function<String, FieldType> TYPE_OF = TYPES::get;
    private static final EvalContext CTX = new EvalContext(AccessType.UPDATE, 42L,
            LocalDate.of(2026, 9, 11), LocalDateTime.of(2026, 9, 11, 10, 30));

    private static boolean eval(String filters, Map<String, Object> row) {
        return FilterEvaluator.matches(Filters.of(filters), row, CTX, TYPE_OF);
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> row = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            row.put((String) kv[i], kv[i + 1]);
        }
        return row;
    }

    @Test
    void equalityComparesOptionCodesAndTreatsNullAsEmpty() {
        assertThat(eval("[[\"reason\", \"=\", \"Others\"]]", row("reason", "Others"))).isTrue();
        assertThat(eval("[[\"reason\", \"=\", \"Others\"]]", row("reason", "Relocation"))).isFalse();
        // value semantics, not SQL's three-valued logic: an empty country is "not SG"
        assertThat(eval("[[\"country\", \"!=\", \"SG\"]]", row("country", null))).isTrue();
        assertThat(eval("[[\"country\", \"!=\", \"SG\"]]", row("country", ""))).isTrue();
        assertThat(eval("[[\"country\", \"=\", \"\"]]", row("country", null))).isTrue();
    }

    @Test
    void datesAreReadInTheFrameworkShapeAndInIso8601() {
        // the pipeline's type cast accepts both spellings, so a patch may carry either
        assertThat(eval("[[\"startDate\", \"=\", \"2026-01-31\"]]", row("startDate", "2026-01-31T00:00:00"))).isTrue();
        assertThat(eval("[[\"startDate\", \"<\", \"{{ TODAY }}\"]]", row("startDate", "2026-01-31T09:30:00Z"))).isTrue();
        assertThat(eval("[[\"startDate\", \"=\", \"2026-01-31\"]]", row("startDate", "2026-01-31 09:30:00"))).isTrue();
    }

    @Test
    void aValueThatDoesNotCoerceIsEqualToNothing() {
        // Objects.equals(null, null) must not make two unparseable dates "equal"
        assertThat(eval("[[\"startDate\", \"=\", \"n/a\"]]", row("startDate", "tbd"))).isFalse();
        assertThat(eval("[[\"startDate\", \"!=\", \"n/a\"]]", row("startDate", "tbd"))).isTrue();
        assertThat(eval("[[\"isPrimary\", \"=\", \"maybe\"]]", row("isPrimary", "perhaps"))).isFalse();
    }

    @Test
    void anEmptyMultiValueFieldIsTheSameValueAsNullAndEmptyText() {
        // the set semantics must not swallow the emptiness rule: asking an empty set for a member
        // would answer no to both "is it empty" and "does it hold x"
        assertThat(FilterEvaluator.equal(List.of(), null, FieldType.MULTI_OPTION)).isTrue();
        assertThat(FilterEvaluator.equal(List.of(), "", FieldType.MULTI_OPTION)).isTrue();
        assertThat(FilterEvaluator.equal(List.of(), "a", FieldType.MULTI_OPTION)).isFalse();
        assertThat(FilterEvaluator.equal(List.of("a"), null, FieldType.MULTI_OPTION)).isFalse();
        assertThat(eval("[[\"tags\", \"=\", \"\"]]", row("tags", List.of()))).isTrue();
        assertThat(eval("[[\"tags\", \"=\", \"a\"]]", row("tags", List.of("a", "b")))).isTrue();
    }

    @Test
    void anOffsetSpellingIsReadAsWallClockSoTheVerdictDoesNotFollowTheServerZone() {
        // stored date-times are zone-less; re-zoning the literal would move the rule against them
        assertThat(FilterEvaluator.equal("2026-01-31T09:30:00+08:00", "2026-01-31 09:30:00",
                FieldType.DATE_TIME)).isTrue();
        assertThat(eval("[[\"startDate\", \"=\", \"2026-01-31\"]]", row("startDate", "2026-01-31T00:00:00Z"))).isTrue();
    }

    @Test
    void theSqlTemporalTypesCoerceInsteadOfThrowing() {
        // java.sql.Date and java.sql.Time refuse toInstant(); a condition must still get an answer
        assertThat(FilterEvaluator.equal(java.sql.Date.valueOf("2026-01-31"), "2026-01-31", FieldType.DATE)).isTrue();
        assertThat(FilterEvaluator.equal(java.sql.Timestamp.valueOf("2026-01-31 09:30:00"),
                "2026-01-31 09:30:00", FieldType.DATE_TIME)).isTrue();
        assertThat(FilterEvaluator.equal(java.sql.Time.valueOf("09:30:00"), "09:30:00", FieldType.TIME)).isTrue();
        // and a clock time names no day, so a date-time comparison simply has no answer for it
        assertThatCode(() -> FilterEvaluator.equal(java.sql.Time.valueOf("09:30:00"),
                "2026-01-31 09:30:00", FieldType.DATE_TIME)).doesNotThrowAnyException();
    }

    @Test
    void typedEqualityIsExposedForTheAssignmentCheck() {
        assertThat(FilterEvaluator.equal(10, "10.00", FieldType.BIG_DECIMAL)).isTrue();
        assertThat(FilterEvaluator.equal(LocalDate.of(2026, 1, 31), "2026-01-31", FieldType.DATE)).isTrue();
        assertThat(FilterEvaluator.equal("", null, FieldType.STRING)).isTrue();
        assertThat(FilterEvaluator.equal("a", "b", FieldType.STRING)).isFalse();
    }

    @Test
    void numbersCompareByValueWhateverTheirJavaClass() {
        assertThat(eval("[[\"amount\", \">\", 100]]", row("amount", new BigDecimal("100.50")))).isTrue();
        assertThat(eval("[[\"amount\", \">\", 100]]", row("amount", "100.50"))).isTrue();
        assertThat(eval("[[\"headcount\", \"<\", 0]]", row("headcount", -3))).isTrue();
        assertThat(eval("[[\"headcount\", \"<\", 0]]", row("headcount", 0))).isFalse();
        // ordering needs two values
        assertThat(eval("[[\"headcount\", \"<\", 0]]", row("headcount", null))).isFalse();
    }

    @Test
    void datesCompareAcrossStoredTextAndPatchObjects() {
        // the stored row carries the date as text, the patch as LocalDate — they must agree
        assertThat(eval("[[\"endDate\", \"<\", \"{{ @startDate }}\"]]",
                row("startDate", "2026-03-01", "endDate", LocalDate.of(2026, 2, 1)))).isTrue();
        assertThat(eval("[[\"endDate\", \"<\", \"{{ @startDate }}\"]]",
                row("startDate", LocalDate.of(2026, 3, 1), "endDate", "2026-03-01"))).isFalse();
    }

    @Test
    void environmentTokensAndOffsetsResolveFromTheContext() {
        assertThat(eval("[[\"dateOfBirth\", \">\", \"{{ TODAY - P13Y }}\"]]",
                row("dateOfBirth", LocalDate.of(2015, 1, 1)))).isTrue();
        assertThat(eval("[[\"dateOfBirth\", \">\", \"{{ TODAY - P13Y }}\"]]",
                row("dateOfBirth", LocalDate.of(2013, 9, 11)))).isFalse();   // exactly 13 years: not after
        assertThat(eval("[[\"endDate\", \">\", \"{{ @hireDate + P6M }}\"]]",
                row("hireDate", "2026-01-15", "endDate", "2026-08-01"))).isTrue();
        assertThat(eval("[[\"endDate\", \">\", \"{{ TODAY }}\"]]", row("endDate", "2026-09-11"))).isFalse();
        assertThat(eval("[[\"country\", \"=\", \"{{ USER_ID }}\"]]", row("country", "42"))).isTrue();
    }

    @Test
    void reservedVariablesReadTheContextNotTheRow() {
        assertThat(eval("[[\"@mode\", \"=\", \"update\"]]", row())).isTrue();
        assertThat(eval("[[\"@mode\", \"=\", \"create\"]]", row())).isFalse();
        assertThat(eval("[[\"@userId\", \"=\", 42]]", row())).isTrue();
    }

    @Test
    void setMembershipAndCollections() {
        assertThat(eval("[[\"country\", \"IN\", [\"SG\", \"MY\"]]]", row("country", "MY"))).isTrue();
        assertThat(eval("[[\"country\", \"NOT IN\", [\"SG\", \"MY\"]]]", row("country", "NZ"))).isTrue();
        assertThat(eval("[[\"country\", \"NOT IN\", [\"SG\", \"MY\"]]]", row("country", null))).isTrue();
        // a multi-value field is a set: `=` asks whether it contains the value
        assertThat(eval("[[\"tags\", \"=\", \"GPS\"]]", row("tags", List.of("WIFI", "GPS")))).isTrue();
        assertThat(eval("[[\"tags\", \"IS SET\", null]]", row("tags", List.of()))).isFalse();
        assertThat(eval("[[\"tags\", \"IS NOT SET\", null]]", row("tags", null))).isTrue();
    }

    @Test
    void betweenIsInclusiveAndItsNegationIsTheOpposite() {
        assertThat(eval("[[\"headcount\", \"BETWEEN\", [1, 31]]]", row("headcount", 31))).isTrue();
        assertThat(eval("[[\"headcount\", \"BETWEEN\", [1, 31]]]", row("headcount", 32))).isFalse();
        assertThat(eval("[[\"headcount\", \"NOT BETWEEN\", [1, 31]]]", row("headcount", 32))).isTrue();
        assertThat(eval("[[\"headcount\", \"NOT BETWEEN\", [1, 31]]]", row("headcount", null))).isTrue();
    }

    @Test
    void stringOperatorsTreatEmptyAsEmptyString() {
        assertThat(eval("[[\"country\", \"CONTAINS\", \"S\"]]", row("country", null))).isFalse();
        assertThat(eval("[[\"country\", \"NOT CONTAINS\", \"S\"]]", row("country", null))).isTrue();
        assertThat(eval("[[\"country\", \"START WITH\", \"S\"]]", row("country", "SG"))).isTrue();
    }

    @Test
    void nestedTreesCombineWithAndAndOr() {
        String sector = "[[\"country\", \"!=\", \"SG\"], \"OR\", [[\"country\", \"=\", \"SG\"], [\"reason\", \"!=\", \"BRANCH\"]]]";
        assertThat(eval(sector, row("country", "MY", "reason", "BRANCH"))).isTrue();
        assertThat(eval(sector, row("country", "SG", "reason", "BRANCH"))).isFalse();
        assertThat(eval(sector, row("country", "SG", "reason", "HQ"))).isTrue();
        // implicit AND, booleans coerce from text
        assertThat(eval("[[\"isPrimary\", \"=\", true], [\"country\", \"IS NOT SET\", null]]",
                row("isPrimary", "1", "country", ""))).isTrue();
    }

    @Test
    void anEmptyConditionHoldsAndTreeOperatorsAreRefused() {
        assertThat(FilterEvaluator.matches(null, row(), CTX, TYPE_OF)).isTrue();
        assertThat(FilterEvaluator.matches(new Filters(), row(), CTX, TYPE_OF)).isTrue();
        assertThatThrownBy(() -> eval("[[\"country\", \"PARENT OF\", [\"1/2\"]]]", row("country", "1")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void placeholderParsingClassifiesReferences() {
        assertThat(FilterEvaluator.parseValueRef("{{ @startDate }}").kind()).isEqualTo(FilterEvaluator.RefKind.FIELD);
        assertThat(FilterEvaluator.parseValueRef("{{ TODAY - P13Y }}").offset()).isNotNull();
        assertThat(FilterEvaluator.parseValueRef("{{ NOW - PT2H }}").hasTimeOffset()).isTrue();
        assertThat(FilterEvaluator.parseValueRef("{{ USER_DEPT_ID }}").isReference()).isFalse();
        assertThat(FilterEvaluator.parseValueRef("plain").isReference()).isFalse();
        assertThatThrownBy(() -> FilterEvaluator.parseValueRef("{{ TODAY - PX }}"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
