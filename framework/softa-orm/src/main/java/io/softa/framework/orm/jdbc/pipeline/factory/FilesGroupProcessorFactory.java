package io.softa.framework.orm.jdbc.pipeline.factory;

import io.softa.framework.base.enums.AccessType;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.jdbc.pipeline.processor.FieldProcessor;
import io.softa.framework.orm.jdbc.pipeline.processor.FilesGroupProcessor;
import io.softa.framework.orm.meta.MetaField;

/**
 * The processor factory of creating FilesGroupProcessor, which is processing File fields and MultiFile fields.
 */
public class FilesGroupProcessorFactory implements FieldProcessorFactory {

    private final ConvertType convertType;

    // the FilesGroupProcessor object to process File fields and MultiFile fields
    private FilesGroupProcessor filesGroupProcessor;

    public FilesGroupProcessorFactory(ConvertType convertType) {
        this.convertType = convertType;
    }

    /**
     * Create a field processor according to the field metadata.
     *
     * @param metaField field metadata object
     * @param accessType access type
     */
    @Override
    public FieldProcessor createProcessor(MetaField metaField, AccessType accessType) {
        FieldType fieldType = metaField.getFieldType();
        if (ConvertType.REFERENCE.equals(convertType) && FieldType.FILE_TYPES.contains(fieldType)) {
            if (this.filesGroupProcessor == null) {
                this.filesGroupProcessor = new FilesGroupProcessor(metaField, accessType);
                return this.filesGroupProcessor;
            } else {
                this.filesGroupProcessor.addFileField(metaField);
            }
        }
        return null;
    }

}
