package io.softa.framework.orm.jdbc.pipeline.processor;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.dto.DTOFieldObject;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A DTO column's amounts keep their scale on the way out: a field declared {@code BigDecimal} receives {@code 502.00} as written, not the double {@code 502.0}.
 * The processor reads the column into a tree that already holds decimals, so the tree-to-DTO conversion has nothing left to lose. A JSON column is read as before.
 */
class JsonProcessorDecimalTest {

    private static final String MODEL = "Order";
    private static final String FIELD = "summary";
    private static final String JSON = "{\"amount\":502.00,\"count\":30}";

    /** The DTO the column is declared as. */
    private record Summary(BigDecimal amount, Integer count) implements DTOFieldObject {}

    private static MetaField field(FieldType fieldType) {
        MetaField metaField = new MetaField();
        ReflectionTestUtils.setField(metaField, "modelName", MODEL);
        ReflectionTestUtils.setField(metaField, "fieldName", FIELD);
        ReflectionTestUtils.setField(metaField, "columnName", FIELD);
        ReflectionTestUtils.setField(metaField, "label", "Summary");
        ReflectionTestUtils.setField(metaField, "fieldType", fieldType);
        return metaField;
    }

    private static JsonNode read(FieldType fieldType) {
        Map<String, Object> row = new HashMap<>();
        row.put(FIELD, JSON);
        new JsonProcessor(field(fieldType), AccessType.READ).processOutputRow(row);
        return (JsonNode) row.get(FIELD);
    }

    /** A field declared BigDecimal receives the amount with the scale it was written with. */
    @Test
    void dtoColumn_bigDecimalField_keepsItsScale() {
        Summary summary = JsonUtils.jsonNodeToObject(read(FieldType.DTO), Summary.class);
        assertEquals(new BigDecimal("502.00"), summary.amount());
        assertEquals(30, summary.count());
    }

    /** The tree a DTO column is read into already holds the amount as a decimal and leaves integers integral. */
    @Test
    void dtoColumn_treeHoldsDecimals() {
        JsonNode tree = read(FieldType.DTO);
        assertTrue(tree.get("amount").isBigDecimal());
        assertEquals(new BigDecimal("502.00"), tree.get("amount").decimalValue());
        assertTrue(tree.get("count").isIntegralNumber());
    }

    /** A JSON column is read as before: its floats are doubles. */
    @Test
    void jsonColumn_readsAsBefore() {
        JsonNode tree = read(FieldType.JSON);
        assertFalse(tree.get("amount").isBigDecimal());
        assertTrue(tree.get("amount").isDouble());
    }
}
