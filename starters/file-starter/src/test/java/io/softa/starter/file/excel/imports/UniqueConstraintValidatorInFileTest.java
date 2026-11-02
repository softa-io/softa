package io.softa.starter.file.excel.imports;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.softa.framework.base.exception.ValidationException;
import io.softa.framework.orm.constant.FileConstant;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two rows of one sheet carrying the same unique key.
 *
 * <p>The database pre-check answers "does this already exist" and says nothing about the file itself,
 * so this was previously not checked at all. It is not a database problem: under
 * {@code CREATE_OR_UPDATE} the first row creates the record and the second updates it, and the sheet
 * quietly resolves to <b>last</b> wins — the opposite of what the templates specify. An operator who
 * pastes a corrected row above the original ends up with the original.
 */
class UniqueConstraintValidatorInFileTest {

    private final UniqueConstraintValidator validator = new UniqueConstraintValidator();

    @Test
    void theFirstRowWithAKeyWinsAndTheRestAreReturnedWithAReason() {
        List<Map<String, Object>> rows = rows(
                row("code", "ADR001", "line1", "first"),
                row("code", "ADR002", "line1", "other"),
                row("code", "ADR001", "line1", "second"));

        validator.markInFileDuplicates(List.of("code"), rows, true);

        assertThat(rows.get(0)).as("the first occurrence takes effect")
                .doesNotContainKey(FileConstant.FAILED_REASON);
        assertThat(rows.get(1)).as("a different key is untouched")
                .doesNotContainKey(FileConstant.FAILED_REASON);
        assertThat(rows.get(2).get(FileConstant.FAILED_REASON).toString())
                .as("and the operator is told why the row did not land")
                .contains("An earlier row in this file already has code=ADR001");
    }

    @Test
    void aBlankKeyIsNeverADuplicateOfAnother() {
        // A blank key is what a new record looks like — the child templates say so outright: "为空代表
        // 新增". Two new records are two records, not one record written twice.
        List<Map<String, Object>> rows = rows(
                row("code", "", "line1", "new one"),
                row("code", "", "line1", "new two"),
                row("code", null, "line1", "new three"));

        validator.markInFileDuplicates(List.of("code"), rows, true);

        assertThat(rows).allSatisfy(row ->
                assertThat(row).doesNotContainKey(FileConstant.FAILED_REASON));
    }

    @Test
    void aRowThatAlreadyFailedIsNotConsideredTheWinner() {
        // Otherwise a row rejected earlier in the pipeline would still claim the key, and the good row
        // below it would be dropped as its duplicate — losing both.
        Map<String, Object> broken = row("code", "ADR001", "line1", "bad");
        broken.put(FileConstant.FAILED_REASON, "Employee not found: E9");
        List<Map<String, Object>> rows = rows(broken, row("code", "ADR001", "line1", "good"));

        validator.markInFileDuplicates(List.of("code"), rows, true);

        assertThat(rows.get(1)).as("the surviving row keeps the key")
                .doesNotContainKey(FileConstant.FAILED_REASON);
    }

    @Test
    void allFieldsOfACompositeKeyHaveToMatch() {
        List<Map<String, Object>> rows = rows(
                row("employeeId", 1L, "type", "HOME"),
                row("employeeId", 1L, "type", "WORK"),
                row("employeeId", 2L, "type", "HOME"),
                row("employeeId", 1L, "type", "HOME"));

        validator.markInFileDuplicates(List.of("employeeId", "type"), rows, true);

        assertThat(rows.get(0)).doesNotContainKey(FileConstant.FAILED_REASON);
        assertThat(rows.get(1)).doesNotContainKey(FileConstant.FAILED_REASON);
        assertThat(rows.get(2)).doesNotContainKey(FileConstant.FAILED_REASON);
        assertThat(rows.get(3)).containsKey(FileConstant.FAILED_REASON);
    }

    @Test
    void aTemplateThatDeclaresNoUniqueKeyIsLeftAlone() {
        // Nothing identifies a record, so nothing can be said to repeat.
        List<Map<String, Object>> rows = rows(row("code", "ADR001"), row("code", "ADR001"));

        validator.markInFileDuplicates(List.of(), rows, true);

        assertThat(rows).allSatisfy(row ->
                assertThat(row).doesNotContainKey(FileConstant.FAILED_REASON));
    }

    @Test
    void withoutSkipExceptionTheWholeImportStops() {
        // Same bargain as every other check in this pipeline: skipException is the template author
        // saying whether one bad row costs the batch.
        List<Map<String, Object>> rows = rows(row("code", "ADR001"), row("code", "ADR001"));

        assertThatThrownBy(() -> validator.markInFileDuplicates(List.of("code"), rows, false))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("code=ADR001");
    }

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }

    @SafeVarargs
    private static List<Map<String, Object>> rows(Map<String, Object>... rows) {
        return new ArrayList<>(List.of(rows));
    }
}
