package io.softa.framework.orm.jdbc.pipeline.processor;

import io.softa.framework.base.enums.AccessType;
import io.softa.framework.orm.meta.MetaField;
import lombok.extern.slf4j.Slf4j;

/**
 * Boolean field processor
 */
@Slf4j
public class BooleanProcessor extends BaseProcessor {

    /**
     * Field processor object constructor
     *
     * @param metaField field metadata object
     * @param accessType access type
     */
    public BooleanProcessor(MetaField metaField, AccessType accessType) {
        super(metaField, accessType);
    }

}
