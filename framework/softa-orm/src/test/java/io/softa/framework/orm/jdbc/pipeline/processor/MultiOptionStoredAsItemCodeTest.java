package io.softa.framework.orm.jdbc.pipeline.processor;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonValue;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.core.JacksonException;

import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.utils.BeanTool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A multiple-value field stores an enum element as its item code, as a single option field does.
 *
 * <p>An entity's {@code List<enum>} property reaches the processor holding the enum constants
 * themselves. They used to be joined by {@code toString()}, so a {@code @JsonValue} enum was stored
 * by constant name ({@code NEEDS_REVIEW}) instead of item code ({@code NeedsReview}): the option
 * expansion could not find the item and showed the raw name, and reading the row back into the
 * entity failed, because Jackson reads a {@code @JsonValue} enum from its code only.
 * {@link StringProcessor} already stored a single option by code; each element now takes the same
 * conversion.
 */
class MultiOptionStoredAsItemCodeTest {

    private static final String FIELD = "flags";

    /** An option set the way an entity declares it: the code on a {@code @JsonValue} field. */
    enum Flag {
        NEEDS_REVIEW("NeedsReview"),
        OVER_LIMIT("OverLimit");

        @JsonValue
        private final String code;

        Flag(String code) {
            this.code = code;
        }
    }

    /** An enum with no {@code @JsonValue}: its constant name is its code. */
    enum PlainFlag {
        LATE, EARLY
    }

    /** The entity a stored row is read back into. */
    static class ExpenseLine {
        private List<Flag> flags;
    }

    private static MetaField field() {
        MetaField metaField = new MetaField();
        ReflectionTestUtils.setField(metaField, "modelName", "ExpenseLine");
        ReflectionTestUtils.setField(metaField, "fieldName", FIELD);
        ReflectionTestUtils.setField(metaField, "columnName", FIELD);
        ReflectionTestUtils.setField(metaField, "fieldType", FieldType.MULTI_OPTION);
        ReflectionTestUtils.setField(metaField, "optionSetCode", "Flag");
        return metaField;
    }

    private static MultiStringProcessor processor(AccessType access) {
        return new MultiStringProcessor(field(), access, ConvertType.TYPE_CAST);
    }

    private static Map<String, Object> row(Object value) {
        Map<String, Object> row = new HashMap<>();
        row.put(FIELD, value);
        return row;
    }

    /** The value the processor hands to the database for a write of {@code value}. */
    private static Object write(AccessType access, Object value) {
        Map<String, Object> row = row(value);
        processor(access).processInputRow(row);
        return row.get(FIELD);
    }

    /** The value the processor hands back for a stored column value. */
    private static Object read(Object stored) {
        Map<String, Object> row = row(stored);
        processor(AccessType.READ).processOutputRow(row);
        return row.get(FIELD);
    }

    @Test
    void anEnumListIsStoredAsItemCodes() {
        List<Flag> flags = List.of(Flag.NEEDS_REVIEW, Flag.OVER_LIMIT);

        assertEquals("NeedsReview,OverLimit", write(AccessType.CREATE, flags));
        assertEquals("NeedsReview,OverLimit", write(AccessType.UPDATE, flags));
    }

    @Test
    void whatIsStoredReadsBackAsTheSameEnums() {
        // The way a row goes back into the entity: the processor splits the column into strings,
        // then BeanTool converts each one to the element type of the List<enum> property.
        List<Flag> flags = List.of(Flag.OVER_LIMIT, Flag.NEEDS_REVIEW);

        Object strings = read(write(AccessType.UPDATE, flags));

        assertEquals(List.of("OverLimit", "NeedsReview"), strings);
        assertEquals(flags, BeanTool.convertPropertyValue(ExpenseLine.class, FIELD, strings));
    }

    @Test
    void theConstantNamesStoredBeforeCannotBeReadBack() {
        // Why the code matters: what the processor used to store fails the same read.
        Object strings = read("NEEDS_REVIEW,OVER_LIMIT");

        assertThrows(JacksonException.class, () -> BeanTool.convertPropertyValue(ExpenseLine.class, FIELD, strings));
    }

    @Test
    void anEnumWithoutJsonValueIsStoredByNameAsBefore() {
        // Its code is its name, so rows already stored from such an enum keep their format.
        assertEquals("LATE,EARLY", write(AccessType.CREATE, List.of(PlainFlag.LATE, PlainFlag.EARLY)));
    }

    @Test
    void aStringListIsStoredAsBefore() {
        assertEquals("a,b", write(AccessType.CREATE, List.of("a", "b")));
        // A null element still joins as an empty segment.
        assertEquals("a,", write(AccessType.UPDATE, Arrays.asList("a", null)));
    }

    @Test
    void nullAndAnEmptyListAreStoredAsBefore() {
        assertEquals("", write(AccessType.CREATE, List.of()));
        assertNull(write(AccessType.UPDATE, null));

        // A create that leaves the field out takes the default, none here.
        Map<String, Object> created = new HashMap<>();
        processor(AccessType.CREATE).processInputRow(created);
        assertNull(created.get(FIELD));
    }
}
