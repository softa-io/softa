package io.softa.starter.flow.runtime.context;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.store.FlowExecutionStateMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * An instance's variables as the store gives them back: the app writes every {@code Long} and {@code BigDecimal}
 * as text, so the record's amounts and ids came back as strings, and a route after the first approval compared
 * them as such — an amount failed the step, an id never matched. They come back as numbers.
 */
class RecordVariablesRestoreNumbersTest {

    private static final String MODEL = "ExpenseClaim";

    private MockedStatic<ModelManager> modelManager;

    @BeforeEach
    void setUp() {
        List<MetaField> fields = List.of(
                field("totalAmount", FieldType.BIG_DECIMAL, null, null),
                field("nights", FieldType.INTEGER, null, null),
                field("departmentId", FieldType.MANY_TO_ONE, "Department", FieldType.LONG),
                // Not resolved into the field: the related model's id says what it stores.
                field("companyId", FieldType.MANY_TO_ONE, "Company", null),
                // A currency is known by its code.
                field("currency", FieldType.MANY_TO_ONE, "Currency", FieldType.STRING),
                field("purpose", FieldType.STRING, null, null),
                field("claimDate", FieldType.DATE, null, null));
        MetaField companyId = field("id", FieldType.LONG, null, null);
        modelManager = mockStatic(ModelManager.class);
        modelManager.when(() -> ModelManager.existModel(MODEL)).thenReturn(true);
        modelManager.when(() -> ModelManager.getModelFields(MODEL)).thenReturn(fields);
        modelManager.when(() -> ModelManager.existField("Company", "id")).thenReturn(true);
        modelManager.when(() -> ModelManager.getModelField("Company", "id")).thenReturn(companyId);
    }

    @AfterEach
    void tearDown() {
        modelManager.close();
    }

    @Test
    void theRecordsAmountsAndIdsComeBackAsNumbersAndItsTextStaysText() {
        Map<String, Object> variables = RecordVariables.restoreNumbers(MODEL, stored());

        assertThat(variables).containsExactlyInAnyOrderEntriesOf(Map.of(
                "totalAmount", new BigDecimal("1234.50"),
                "nights", 3,
                "departmentId", 906576038580977724L,
                "companyId", 12L,
                "currency", "SGD",
                "purpose", "123",
                "claimDate", "2026-11-02",
                "_bundleId", "77"));
    }

    @Test
    void anInstanceIsLoadedWithThem() {
        FlowInstance entity = new FlowInstance();
        entity.setModelName(MODEL);
        entity.setVariables("{\"totalAmount\":\"1234.50\",\"departmentId\":\"906576038580977724\",\"currency\":\"SGD\"}");

        FlowExecutionState state = FlowExecutionStateMapper.toState(entity);

        assertThat(state.getVariables()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "totalAmount", new BigDecimal("1234.50"),
                "departmentId", 906576038580977724L,
                "currency", "SGD"));
    }

    @Test
    void textThatIsNoNumberAndAnInstanceOfNoModelAreLeftAsTheyAre() {
        Map<String, Object> variables = new LinkedHashMap<>(Map.of("totalAmount", "n/a"));

        assertThat(RecordVariables.restoreNumbers(MODEL, variables)).containsEntry("totalAmount", "n/a");
        assertThat(RecordVariables.restoreNumbers(null, new LinkedHashMap<>(Map.of("totalAmount", "1"))))
                .containsEntry("totalAmount", "1");
    }

    /** What the JSON column gives back: the app's mapper writes a Long or a BigDecimal as text. */
    private static Map<String, Object> stored() {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("totalAmount", "1234.50");
        variables.put("nights", 3);
        variables.put("departmentId", "906576038580977724");
        variables.put("companyId", "12");
        variables.put("currency", "SGD");
        variables.put("purpose", "123");
        variables.put("claimDate", "2026-11-02");
        variables.put("_bundleId", "77");
        return variables;
    }

    private static MetaField field(String name, FieldType type, String relatedModel, FieldType relatedFieldType) {
        MetaField field = mock(MetaField.class);
        when(field.getFieldName()).thenReturn(name);
        when(field.getFieldType()).thenReturn(type);
        when(field.getRelatedModel()).thenReturn(relatedModel);
        when(field.getRelatedFieldType()).thenReturn(relatedFieldType);
        return field;
    }
}
