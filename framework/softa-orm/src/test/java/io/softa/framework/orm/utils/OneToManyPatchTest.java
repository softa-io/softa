package io.softa.framework.orm.utils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.framework.base.exception.IllegalArgumentException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OneToManyPatchTest {

    @Test
    void readsTheRowsAPatchCreatesUpdatesAndDeletesWhateverTheCaseOfItsKeys() {
        OneToManyPatch patch = OneToManyPatch.parse(Map.of(
                "Create", List.of(Map.of("amount", 10)),
                "update", List.of(Map.of("id", 7, "amount", 20)),
                "DELETE", List.of(8)), "ExpenseClaim", "expenseLineIds");

        assertEquals(List.of(Map.of("amount", 10)), patch.createRows());
        assertEquals(List.of(Map.of("id", 7, "amount", 20)), patch.updateRows());
        assertEquals(List.of(8), patch.deleteIds());
    }

    @Test
    void aPartLeftOutOrSentEmptyHoldsNoRows() {
        Map<String, Object> sent = new HashMap<>();
        sent.put("Create", List.of(Map.of("amount", 10)));
        sent.put("Delete", null);

        OneToManyPatch patch = OneToManyPatch.parse(sent, "ExpenseClaim", "expenseLineIds");

        assertEquals(List.of(), patch.updateRows());
        assertEquals(List.of(), patch.deleteIds());
    }

    @Test
    void aKeyThatIsNoOneToManyOperationAndAPartThatIsNoListAreRefused() {
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> OneToManyPatch.parse(Map.of("Replace", List.of()), "ExpenseClaim", "expenseLineIds"));
        assertTrue(unknown.getMessage().contains("ExpenseClaim:expenseLineIds"), unknown.getMessage());
        // A many-to-many operation is not one of a one-to-many.
        assertThrows(IllegalArgumentException.class,
                () -> OneToManyPatch.parse(Map.of("Add", List.of(1)), "ExpenseClaim", "expenseLineIds"));
        assertThrows(IllegalArgumentException.class,
                () -> OneToManyPatch.parse(Map.of("Create", Map.of("amount", 10)), "ExpenseClaim", "expenseLineIds"));
    }
}
