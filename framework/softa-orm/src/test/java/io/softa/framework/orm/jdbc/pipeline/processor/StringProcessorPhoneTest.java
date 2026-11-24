package io.softa.framework.orm.jdbc.pipeline.processor;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.enums.WidgetType;
import io.softa.framework.orm.meta.MetaField;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A phone field stores one spelling of a number. A New Zealand mobile typed the way it is written at home, after the
 * calling code — "+64 021 123 4678" — used to be stored as "+640211234678", which no sign-in with the standard
 * "+64211234678" found, and the other way round.
 */
class StringProcessorPhoneTest {

    private static MetaField field(WidgetType widgetType) {
        MetaField metaField = new MetaField();
        ReflectionTestUtils.setField(metaField, "modelName", "Employee");
        ReflectionTestUtils.setField(metaField, "fieldName", "workPhone");
        ReflectionTestUtils.setField(metaField, "columnName", "work_phone");
        ReflectionTestUtils.setField(metaField, "fieldType", FieldType.STRING);
        ReflectionTestUtils.setField(metaField, "length", 32);
        ReflectionTestUtils.setField(metaField, "widgetType", widgetType);
        return metaField;
    }

    private static Object written(WidgetType widgetType, String value) {
        Map<String, Object> row = new HashMap<>();
        row.put("workPhone", value);
        new StringProcessor(field(widgetType), AccessType.UPDATE).processInputRow(row);
        return row.get("workPhone");
    }

    @Test
    void aPhoneFieldStoresTheNumberInItsOneSpelling_trunkPrefixAndSeparatorsDropped() {
        assertEquals("+64211234678", written(WidgetType.PHONE, "+64 021 123 4678"));
        assertEquals("+64211234678", written(WidgetType.PHONE, "+640211234678"));
        assertEquals("+6591234567", written(WidgetType.PHONE, " +65 9123-4567 "));
    }

    @Test
    void whatIsNoNumberWithItsCallingCode_isLeftForThePatternToJudge() {
        // No pattern on this field: kept as it was, only trimmed.
        assertEquals("91234567", written(WidgetType.PHONE, " 91234567 "));
    }

    @Test
    void anyOtherFieldIsNotTouched() {
        assertEquals("+64 021 123 4678", written(null, "+64 021 123 4678"));
    }
}
