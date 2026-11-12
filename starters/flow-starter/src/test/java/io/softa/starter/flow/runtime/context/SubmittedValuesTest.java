package io.softa.starter.flow.runtime.context;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;

import static io.softa.starter.flow.runtime.context.RecordModelFixture.MODEL;
import static io.softa.starter.flow.runtime.context.RecordModelFixture.RELATED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A test run's values as the browser sends them — ids and dates as text, a table as the rows a new record's form
 * creates — typed as the record would hold them once saved (zingkey-hcm#2583: a company id sent as text refused the
 * whole test run).
 */
class SubmittedValuesTest {

    private MockedStatic<ModelManager> modelManager;

    @BeforeEach
    void setUp() {
        modelManager = RecordModelFixture.mockModelManager();
        // A table of the record: rows of the related model.
        MetaField lines = mock(MetaField.class);
        when(lines.getFieldName()).thenReturn("lines");
        when(lines.getFieldType()).thenReturn(FieldType.ONE_TO_MANY);
        when(lines.getRelatedModel()).thenReturn(RELATED);
        modelManager.when(() -> ModelManager.existField(MODEL, "lines")).thenReturn(true);
        modelManager.when(() -> ModelManager.getModelField(MODEL, "lines")).thenReturn(lines);
    }

    @AfterEach
    void tearDown() {
        modelManager.close();
    }

    @Test
    void idsDatesAndAmountsSentAsTextAreTypedAsTheRecordHoldsThem() {
        Map<String, Object> sent = new LinkedHashMap<>();
        sent.put("departmentId", "897782239419432990");
        sent.put("days", "2.5");
        sent.put("startDate", "2026-11-12");
        sent.put("startTime", "2026-11-12 09:30:00");
        sent.put("leaveType", "Annual");
        sent.put("reason", "");

        Map<String, Object> typed = SubmittedValues.typed(MODEL, sent);

        assertEquals(897782239419432990L, typed.get("departmentId"));
        assertEquals(new BigDecimal("2.5"), typed.get("days"));
        assertEquals(LocalDate.of(2026, 11, 12), typed.get("startDate"));
        assertEquals(LocalDateTime.of(2026, 11, 12, 9, 30), typed.get("startTime"));
        assertEquals("Annual", typed.get("leaveType"), "an option keeps its code");
        assertEquals("", typed.get("reason"), "a text field keeps its text, empty or not");
    }

    @Test
    void aReferenceAnIsoDateTimeAndAnEmptyAmountAreReadToo() {
        Map<String, Object> sent = new LinkedHashMap<>();
        sent.put("departmentId", Map.of("id", "30", "displayName", "Sales"));
        sent.put("startTime", "2026-11-12T09:30");
        sent.put("days", "");

        Map<String, Object> typed = SubmittedValues.typed(MODEL, sent);

        assertEquals(30L, typed.get("departmentId"));
        assertEquals(LocalDateTime.of(2026, 11, 12, 9, 30), typed.get("startTime"));
        assertNull(typed.get("days"), "an empty text is no value");
        assertTrue(typed.containsKey("days"));
    }

    @Test
    void aTableIsTheRowsANewRecordsFormCreatesEachTypedByItsModel() {
        Map<String, Object> sent = Map.of("lines", Map.of("Create",
                List.of(Map.of("level", "4", "establishedOn", "2020-01-01", "parentId", "7"))));

        Map<String, Object> typed = SubmittedValues.typed(MODEL, sent);

        assertEquals(List.of(Map.of("level", 4, "establishedOn", LocalDate.of(2020, 1, 1), "parentId", 7L)),
                typed.get("lines"));
    }

    @Test
    void aValueItsFieldCannotTakeIsRefusedNamingTheField() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> SubmittedValues.typed(MODEL, Map.of("days", "two")));

        assertTrue(refused.getMessage().contains("days"), refused.getMessage());
    }

    @Test
    void fieldsAndModelsTheMetadataDoesNotHoldAreKeptAsSent() {
        assertEquals(Map.of("note", "x"), SubmittedValues.typed(MODEL, Map.of("note", "x")));
        assertEquals(Map.of("amount", "10"), SubmittedValues.typed("Unknown", Map.of("amount", "10")));
        assertEquals(Map.of(), SubmittedValues.typed(MODEL, null));
    }
}
