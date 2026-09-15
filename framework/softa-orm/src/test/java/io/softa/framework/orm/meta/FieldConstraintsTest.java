package io.softa.framework.orm.meta;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.enums.FieldType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The declaration object: what it stores, what it references, what it refuses at boot.
 */
class FieldConstraintsTest {

    private static final Map<String, FieldType> SIBLINGS = Map.of(
            "reason", FieldType.OPTION, "reasonDescription", FieldType.STRING, "startDate", FieldType.DATE,
            "endDate", FieldType.DATE, "name", FieldType.STRING, "hireDate", FieldType.DATE,
            "amount", FieldType.BIG_DECIMAL, "reportsTo", FieldType.MANY_TO_ONE, "id", FieldType.LONG);
    private static final Function<String, FieldType> TYPE_OF = SIBLINGS::get;

    private static FieldConstraints of(String requiredWhen, String hiddenWhen, String readonlyWhen, String invalidWhen) {
        return FieldConstraints.of("", "", "", "", requiredWhen, hiddenWhen, readonlyWhen, invalidWhen, "M.f");
    }

    @Test
    void nothingDeclaredIsNullNotAnEmptyObject() {
        assertThat(FieldConstraints.of("", "", "", "", "", "", "", "", "M.f")).isNull();
    }

    @Test
    void jsonRoundTripIsCompactAndKeepsTheAlwaysForm() {
        FieldConstraints c = FieldConstraints.of("0", null, null, "Headcount cannot be negative.",
                "true", null, null, null, "Department.activeEmpCount");
        String json = JsonUtils.objectToString(c);
        assertThat(json).contains("\"min\":\"0\"").contains("\"requiredWhen\":true").doesNotContain("max")
                .doesNotContain("empty");   // isEmpty() is a query, not a key the frontend should see
        FieldConstraints back = JsonUtils.stringToObject(json, FieldConstraints.class);
        assertThat(back).isEqualTo(c);
        assertThat(back.requiredWhen().isAlways()).isTrue();

        FieldConstraints conditional = of("[[\"reason\", \"=\", \"Others\"]]", null, null, null);
        FieldConstraints back2 = JsonUtils.stringToObject(JsonUtils.objectToString(conditional), FieldConstraints.class);
        assertThat(back2.requiredWhen().getFilters())
                .isEqualTo(Filters.of("[[\"reason\", \"=\", \"Others\"]]"));
    }

    @Test
    void theExpressionFormCoversTheShapesAnEntityActuallyDeclares() {
        // Pinned because the expression grammar is a separate lane from the JSON one: an entity that
        // switches spelling must not silently lose a placeholder, an offset or a nested group.
        assertThat(Filters.of("""
                endDate < "{{ @startDate }}"
                """)).isEqualTo(Filters.of("[[\"endDate\", \"<\", \"{{ @startDate }}\"]]"));
        assertThat(Filters.of("""
                dateOfBirth > "{{ TODAY - P13Y }}"
                """)).isEqualTo(Filters.of("[[\"dateOfBirth\", \">\", \"{{ TODAY - P13Y }}\"]]"));
        assertThat(Filters.of("""
                reason = "Others" AND status = "Draft"
                """)).isEqualTo(Filters.of(
                        "[[\"reason\", \"=\", \"Others\"], \"AND\", [\"status\", \"=\", \"Draft\"]]"));
        // grouping to depth, which is what a complex rule actually needs
        assertThat(Filters.of("""
                title = "PM" OR (code = "A010" AND grade = 1)
                """)).isEqualTo(Filters.of(
                        "[[\"title\", \"=\", \"PM\"], \"OR\", [[\"code\", \"=\", \"A010\"], \"AND\", [\"grade\", \"=\", 1]]]"));
        // The one shape the expression grammar does NOT cover: an operator with no value. Its visitor
        // demands a value context, so a valueless unit has to stay in the JSON spelling.
        assertThatThrownBy(() -> Filters.of("""
                costCentreId IS NOT SET
                """)).hasMessageContaining("Unsupported value context");
        assertThat(Filters.of("[[\"costCentreId\", \"IS NOT SET\", null]]")).isNotNull();
    }

    @Test
    void aConditionReadsTheSameWhetherItIsWrittenAsJsonOrAsAnExpression() {
        // The JSON spelling needs an escaped quote per token; the expression spelling needs none at
        // all when it is written as a text block, which is what an entity should be able to declare.
        FieldConstraints json = FieldConstraints.of(null, null, null, null,
                "[[\"reason\", \"=\", \"Others\"]]", null, null, null, "X.y");
        FieldConstraints expression = FieldConstraints.of(null, null, null, null, """
                reason = "Others"
                """, null, null, null, "X.y");
        assertThat(expression).isNotNull();
        assertThat(expression.requiredWhen().getFilters()).isEqualTo(json.requiredWhen().getFilters());
        assertThat(expression.referencedFields()).containsExactly("reason");
    }

    @Test
    void referencedFieldsCoverBothSlotsAndSkipReservedVariables() {
        FieldConstraints c = of("[[\"reason\", \"=\", \"Others\"], [\"@mode\", \"=\", \"update\"]]",
                null, null, "[[\"endDate\", \"<\", \"{{ @startDate }}\"]]");
        assertThat(c.referencedFields()).containsExactlyInAnyOrder("reason", "endDate", "startDate");
        assertThat(of("true", null, null, null).referencedFields()).isEmpty();
    }

    @Test
    void valueDomainIsCheckedAgainstTheFieldType() {
        assertThatThrownBy(() -> FieldConstraints.of("0", "", "", "", "", "", "", "", "M.name")
                .validate(FieldType.STRING, false, "M.name", TYPE_OF))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("numeric");
        assertThatThrownBy(() -> FieldConstraints.of("", "", "\\d+", "", "", "", "", "", "M.n")
                .validate(FieldType.INTEGER, false, "M.n", TYPE_OF))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("STRING");
        assertThatThrownBy(() -> FieldConstraints.of("100", "1", "", "", "", "", "", "", "M.n")
                .validate(FieldType.INTEGER, false, "M.n", TYPE_OF))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no value can satisfy");
        assertThatThrownBy(() -> FieldConstraints.of("zero", "", "", "", "", "", "", "", "M.n")
                .validate(FieldType.INTEGER, false, "M.n", TYPE_OF))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("decimal literal");
        assertThatThrownBy(() -> FieldConstraints.of("", "", "[A-Z", "", "", "", "", "", "M.name")
                .validate(FieldType.STRING, false, "M.name", TYPE_OF))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("valid regular expression");
        List<String> warnings = FieldConstraints.of("", "", "[A-Z]{2}", "", "", "", "", "", "M.name")
                .validate(FieldType.STRING, false, "M.name", TYPE_OF);
        assertThat(warnings).anyMatch(w -> w.contains("constraintMessage"));
    }

    @Test
    void conditionsAreCheckedAgainstTheSiblings() {
        assertThatThrownBy(() -> of("[[\"contry\", \"=\", \"SG\"]]", null, null, null)
                .validate(FieldType.STRING, false, "M.f", TYPE_OF))
                .hasMessageContaining("`contry`").hasMessageContaining("does not exist");
        assertThatThrownBy(() -> of(null, null, null, "[[\"endDate\", \"<\", \"{{ @name }}\"]]")
                .validate(FieldType.DATE, false, "M.endDate", TYPE_OF))
                .hasMessageContaining("not comparable");
        assertThatThrownBy(() -> of(null, null, null, "[[\"endDate\", \"<\", \"{{ @amount }}\"]]")
                .validate(FieldType.DATE, false, "M.endDate", TYPE_OF))
                .hasMessageContaining("not comparable");
        assertThatThrownBy(() -> of("[[\"reason\", \"PARENT OF\", [\"1\"]]]", null, null, null)
                .validate(FieldType.STRING, false, "M.f", TYPE_OF))
                .hasMessageContaining("PARENT OF");
        assertThatThrownBy(() -> of(null, null, null, "[[\"endDate\", \"<\", \"{{ TODAY - PT2H }}\"]]")
                .validate(FieldType.DATE, false, "M.endDate", TYPE_OF))
                .hasMessageContaining("calendar day");
        assertThatThrownBy(() -> of(null, null, null, "[[\"name\", \"<\", \"{{ @name + P1D }}\"]]")
                .validate(FieldType.STRING, false, "M.name", TYPE_OF))
                .hasMessageContaining("date base");
        assertThatThrownBy(() -> of(null, null, null, "[[\"endDate\", \"<\", \"{{ SOMEWHERE }}\"]]")
                .validate(FieldType.DATE, false, "M.endDate", TYPE_OF))
                .hasMessageContaining("unknown placeholder");
        assertThatThrownBy(() -> of("[[\"reason\", \"=\", \"Others\"]]", null, null, null)
                .validate(FieldType.STRING, true, "M.f", TYPE_OF))
                .hasMessageContaining("dynamic");
        // a relation id compares with a number, and a field compares with itself shifted
        assertThatCode(() -> of(null, null, null,
                "[[\"reportsTo\", \"=\", \"{{ @id }}\"], [\"endDate\", \">\", \"{{ @hireDate + P6M }}\"]]")
                .validate(FieldType.MANY_TO_ONE, false, "M.reportsTo", TYPE_OF)).doesNotThrowAnyException();
    }

    @Test
    void onlyRequiredWhenHasAnAlwaysForm() {
        assertThatThrownBy(() -> of(null, "true", null, null)).hasMessageContaining("does not accept \"true\"");
        assertThatThrownBy(() -> of(null, null, "true", null)).hasMessageContaining("does not accept \"true\"");
        assertThatThrownBy(() -> of(null, null, null, "[]")).hasMessageContaining("empty condition");
        assertThatThrownBy(() -> of("[[\"reason\", \"=\"", null, null, null)).hasMessageContaining("not a filter expression");
    }

    @Test
    void aMemberSpelledAsExplicitlyNothingDoesNotDiscardTheRestOfTheRow() {
        // "no condition here" has several spellings in stored rows; none of them may cost the row
        // its value domain, which is what discarding the whole object would do
        for (String nothing : List.of("[]", "{}", "false", "null")) {
            FieldConstraints c = JsonUtils.stringToObject(
                    "{\"min\":\"0\",\"max\":\"100\",\"requiredWhen\":" + nothing + "}", FieldConstraints.class);
            assertThat(c).as("requiredWhen: %s", nothing).isNotNull();
            assertThat(c.min()).isEqualTo("0");
            assertThat(c.max()).isEqualTo("100");
            assertThat(c.requiredWhen()).isNull();
        }
        FieldConstraints hidden = JsonUtils.stringToObject("{\"min\":\"0\",\"hiddenWhen\":[]}", FieldConstraints.class);
        assertThat(hidden).isNotNull();
        assertThat(hidden.min()).isEqualTo("0");
        assertThat(hidden.hiddenWhen()).isNull();
    }

    @Test
    void aConditionThatDoesNotParseCostsTheFieldThatRuleAndNothingElse() {
        // the value domain is a separate declaration; one bad condition must not silently switch it off
        FieldConstraints always = JsonUtils.stringToObject(
                "{\"min\":\"0\",\"pattern\":\"^[A-Z]{2}$\",\"hiddenWhen\":true}", FieldConstraints.class);
        assertThat(always).isNotNull();
        assertThat(always.min()).isEqualTo("0");
        assertThat(always.pattern()).isEqualTo("^[A-Z]{2}$");
        assertThat(always.hiddenWhen()).isNull();

        FieldConstraints malformed = JsonUtils.stringToObject(
                "{\"min\":\"0\",\"requiredWhen\":[[\"reason\", \"=\"]]}", FieldConstraints.class);
        assertThat(malformed).isNotNull();
        assertThat(malformed.min()).isEqualTo("0");
        assertThat(malformed.requiredWhen()).isNull();
    }

    @Test
    void aStoredRowThatDoesNotParseReadsAsNoConstraintsInsteadOfFailingTheLoad() {
        // hand-written rows and older studio payloads must not stop the catalog from loading
        // nothing else declared, so dropping the unusable rule leaves nothing at all
        assertThat(JsonUtils.stringToObject("{\"hiddenWhen\":true}", FieldConstraints.class)).isNull();
        assertThat(JsonUtils.stringToObject("{\"requiredWhen\":[[\"reason\", \"=\"]]}", FieldConstraints.class)).isNull();
        assertThat(JsonUtils.stringToObject("{}", FieldConstraints.class)).isNull();
        // and a valid row of every shape still reads
        FieldConstraints c = JsonUtils.stringToObject(
                "{\"min\":\"0\",\"requiredWhen\":true,\"invalidWhen\":[\"endDate\",\"<\",\"{{ @startDate }}\"],\"hiddenWhen\":\"[[\\\"a\\\", \\\"=\\\", 1]]\"}",
                FieldConstraints.class);
        assertThat(c.min()).isEqualTo("0");
        assertThat(c.requiredWhen().isAlways()).isTrue();
        assertThat(c.invalidWhen()).isEqualTo(Filters.of("[[\"endDate\",\"<\",\"{{ @startDate }}\"]]"));
        assertThat(c.hiddenWhen()).isEqualTo(Filters.of("[[\"a\", \"=\", 1]]"));
    }
}
