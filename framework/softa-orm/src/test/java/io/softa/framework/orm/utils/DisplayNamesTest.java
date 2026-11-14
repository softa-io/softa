package io.softa.framework.orm.utils;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.softa.framework.orm.vo.ModelReference;
import io.softa.framework.orm.vo.OptionReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A display name reads the way a person would: a relation by its name, an option by its label, a list by its
 * items — never an object's toString, which is how a salary item's version once read
 * "ModelReference(id=891754571066507286, displayName=Basic Monthly Salary) / 2026-10-01".
 */
class DisplayNamesTest {

    @Test
    void aRelationAmongTheDisplayFieldsReadsAsItsName() {
        Map<String, Object> version = Map.of(
                "salaryProfileItemId", ModelReference.of(891754571066507286L, "Basic Monthly Salary"),
                "effectiveStartDate", LocalDate.of(2026, 10, 1));

        assertThat(DisplayNames.of(version, List.of("salaryProfileItemId", "effectiveStartDate")))
                .isEqualTo("Basic Monthly Salary / 2026-10-01");
    }

    @Test
    void anOptionReadsAsItsLabelAndARelationWithNoNameAsItsId() {
        Map<String, Object> exception = Map.of(
                "attendanceDate", LocalDate.of(2026, 10, 30),
                "exceptionType", OptionReference.of("Late", "Late Arrival"),
                "attendanceGroupId", ModelReference.of(3L, null));

        assertThat(DisplayNames.of(exception, List.of("attendanceDate", "exceptionType", "attendanceGroupId")))
                .isEqualTo("2026-10-30 / Late Arrival / 3");
    }

    @Test
    void anOptionWithNoLabelReadsAsItsCode() {
        assertThat(DisplayNames.textOf(OptionReference.of("Late", null))).isEqualTo("Late");
    }

    @Test
    void aManyValuedFieldReadsAsItsItemsAndBlankFieldsAreLeftOut() {
        Map<String, Object> row = new HashMap<>();
        row.put("code", "");
        row.put("name", null);
        row.put("tags", List.of(OptionReference.of("A", "Alpha"), ModelReference.of(2L, "Beta")));
        row.put("owner", Map.of("id", 9L, "displayName", "Ann Lee"));

        assertThat(DisplayNames.of(row, List.of("code", "name", "tags", "owner"))).isEqualTo("Alpha, Beta / Ann Lee");
    }

    @Test
    void aRowWithNothingToShowHasAnEmptyName() {
        assertThat(DisplayNames.of(new HashMap<>(), List.of("code", "name"))).isEmpty();
    }
}
