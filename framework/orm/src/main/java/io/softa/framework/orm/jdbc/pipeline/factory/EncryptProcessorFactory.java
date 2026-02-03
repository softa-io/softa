package io.softa.framework.orm.jdbc.pipeline.factory;

import io.softa.framework.base.enums.AccessType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.jdbc.pipeline.processor.EncryptedProcessor;
import io.softa.framework.orm.jdbc.pipeline.processor.FieldProcessor;
import io.softa.framework.orm.meta.MetaField;

/**
 * Encrypted field processor factory, encrypt before storage and decrypt when output.
 */
public class EncryptProcessorFactory implements FieldProcessorFactory {

    /**
     * Create a field processor according to the field metadata.
     *
     * @param metaField field metadata object
     * @param accessType access type
     * @return encrypted processor
     */
    @Override
    public FieldProcessor createProcessor(MetaField metaField, AccessType accessType) {
        FieldType fieldType = metaField.getFieldType();
        if (FieldType.STRING.equals(fieldType) && metaField.isEncrypted()) {
            return new EncryptedProcessor(metaField, accessType);
        }
        return null;
    }

}
