package io.softa.framework.orm.jdbc.pipeline.processor;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;

import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.meta.MetaField;

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

    /**
     * Convert the Boolean object
     *
     * @param row Single-row output data
     */
    @Override
    public void processOutputRow(Map<String, Object> row) {
        if (!row.containsKey(fieldName)) {
            return;
        }
        Object value = row.get(fieldName);
        if (value instanceof Boolean) {
            return;
        }
        if (value instanceof Number v && v.intValue() == 1) {
            row.put(fieldName, true);
        } else {
            row.put(fieldName, false);
        }
    }
}
