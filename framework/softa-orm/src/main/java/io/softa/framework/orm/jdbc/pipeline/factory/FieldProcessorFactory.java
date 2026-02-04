package io.softa.framework.orm.jdbc.pipeline.factory;

import io.softa.framework.base.enums.AccessType;
import io.softa.framework.orm.jdbc.pipeline.processor.FieldProcessor;
import io.softa.framework.orm.meta.MetaField;

/**
 * Field processor factory interface
 */
public interface FieldProcessorFactory {

    /**
     * Create a field processor according to the field metadata.
     *
     * @param metaField field metadata object
     * @param accessType access type
     */
    FieldProcessor createProcessor(MetaField metaField, AccessType accessType);

}
