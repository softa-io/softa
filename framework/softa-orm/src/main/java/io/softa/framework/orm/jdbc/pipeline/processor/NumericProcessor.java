package io.softa.framework.orm.jdbc.pipeline.processor;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;

/**
 * Numeric field processor.
 * Used to process Integer, Long, Double, BigDecimal fieldTypes.
 */
@Slf4j
public class NumericProcessor extends BaseProcessor {

    private final FieldType fieldType;

    /**
     * NumericProcessor constructor
     *
     * @param metaField field metadata object
     * @param accessType access type
     */
    public NumericProcessor(MetaField metaField, AccessType accessType) {
        super(metaField, accessType);
        this.fieldType = metaField.getFieldType();
    }

    /**
     * Formatting of single-row data.
     *
     * @param row Single-row data to be updated
     */
    @Override
    public void processInputRow(Map<String, Object> row) {
        boolean isContain = row.containsKey(fieldName);
        checkReadonly(isContain);
        Object value = row.get(fieldName);
        if (isContain && value != null) {
            row.put(fieldName, formatInputNumeric(value));
        } else if (AccessType.CREATE.equals(accessType)) {
            checkRequired(null);
            row.computeIfAbsent(fieldName, k -> metaField.getDefaultValueObject());
        } else if (isContain) {
            checkRequired(null);
        }
    }

    /**
     * Convert the number type according to the fieldType, such as Integer to Long
     *
     * @param number number object
     * @return converted object
     */
    private Object formatInputNumeric(Object number) {
        if (FieldType.LONG.equals(fieldType)) {
            if (number instanceof Integer intValue) {
                number = intValue.longValue();
            } else if (number instanceof String stringValue) {
                try {
                    number = Long.parseLong(stringValue);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("The fieldType of {0} is {1}, but the input value is {2}.",
                            fieldName, fieldType, number);
                }
            }
        } else if (FieldType.DOUBLE.equals(fieldType)) {
            if (number instanceof Integer intValue) {
                number = intValue.doubleValue();
            } else if (number instanceof Long longValue) {
                number = longValue.doubleValue();
            }
        }
        return number;
    }
}
