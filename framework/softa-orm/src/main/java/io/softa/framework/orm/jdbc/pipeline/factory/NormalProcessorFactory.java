package io.softa.framework.orm.jdbc.pipeline.factory;

import io.softa.framework.base.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.jdbc.pipeline.processor.*;
import io.softa.framework.orm.meta.MetaField;

/**
 * Normal field processor factory.
 * Check required, readonly, length, etc. and assign default values.
 */
public class NormalProcessorFactory implements FieldProcessorFactory {

    /**
     * Create a field processor according to the field metadata.
     *
     * @param metaField field metadata object
     * @param accessType access type
     * @return field processor
     */
    @Override
    public FieldProcessor createProcessor(MetaField metaField, AccessType accessType) {
        FieldType fieldType = metaField.getFieldType();
        if (FieldType.STRING.equals(fieldType)) {
            return new StringProcessor(metaField, accessType);
        } else if (FieldType.BOOLEAN.equals(fieldType)) {
            return new BooleanProcessor(metaField, accessType);
        } else if (FieldType.OPTION.equals(fieldType)) {
            // In normal processing, OPTION field is processed as a string with default values,
            // and the `OptionExpandProcessor` processor is used for expand cases.
            return new StringProcessor(metaField, accessType);
        } else if (FieldType.FILE.equals(fieldType)) {
            return new StringProcessor(metaField, accessType);
        } else if (FieldType.NUMERIC_TYPES.contains(fieldType)) {
            // Long, Integer, Double, BigDecimal
            return new NumericProcessor(metaField, accessType);
        } else if (FieldType.DATE.equals(fieldType)) {
            return new DateProcessor(metaField, accessType);
        } else if (FieldType.DATE_TIME.equals(fieldType)) {
            return new DateTimeProcessor(metaField, accessType);
        } else if (FieldType.TIME.equals(fieldType)) {
            return new TimeProcessor(metaField, accessType);
        }
        return null;
    }

}
