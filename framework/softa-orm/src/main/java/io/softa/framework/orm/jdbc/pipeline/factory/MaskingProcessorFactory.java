package io.softa.framework.orm.jdbc.pipeline.factory;

import io.softa.framework.base.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.jdbc.pipeline.processor.FieldProcessor;
import io.softa.framework.orm.jdbc.pipeline.processor.MaskingProcessor;
import io.softa.framework.orm.meta.MetaField;

/**
 * Masking field processor factory.
 * Used only in output data processing.
 */
public class MaskingProcessorFactory implements FieldProcessorFactory {

    /**
     * Create a field processor according to the field metadata.
     *
     * @param metaField field metadata object
     * @param accessType access type
     * @return masking processor
     */
    @Override
    public FieldProcessor createProcessor(MetaField metaField, AccessType accessType) {
        FieldType fieldType = metaField.getFieldType();
        if (FieldType.STRING.equals(fieldType) && metaField.getMaskingType() != null) {
            return new MaskingProcessor(metaField, accessType);
        }
        return null;
    }

}
