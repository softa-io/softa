package io.softa.framework.orm.jdbc.pipeline.processor;

import java.time.LocalDateTime;
import java.util.Map;

import io.softa.framework.base.constant.EnvConstant;
import io.softa.framework.base.enums.AccessType;
import io.softa.framework.base.utils.DateUtils;
import io.softa.framework.orm.meta.MetaField;

/**
 * DateTime field processor.
 * Both input and output are formatted to LocalDateTime for DateTime type.
 */
public class DateTimeProcessor extends BaseProcessor {

    /**
     * Field processor object constructor
     *
     * @param metaField field metadata object
     * @param accessType access type
     */
    public DateTimeProcessor(MetaField metaField, AccessType accessType) {
        super(metaField, accessType);
    }

    /**
     * Single-row data formatting processing.
     *
     * @param row Single-row data to be created/updated
     */
    public void processInputRow(Map<String, Object> row) {
        checkReadonly(row);
        if (AccessType.CREATE.equals(accessType)) {
            checkRequired(row);
            row.computeIfAbsent(fieldName, _ -> {
                if (EnvConstant.NOW.equalsIgnoreCase(metaField.getDefaultValue())) {
                    // Assign the current time as the default value.
                    return LocalDateTime.now();
                } else {
                    return metaField.getDefaultValueObject();
                }
            });
        } else if (row.containsKey(fieldName) && row.get(fieldName) == null) {
            // Check if the required field is set to null.
            checkRequired(row);
        }
    }

    /**
     * Convert the DateTime object to LocalDateTime, and compatible with LocalDateTime, Date, and null.
     *
     * @param row Single-row output data
     */
    @Override
    public void processOutputRow(Map<String, Object> row) {
        if (!row.containsKey(fieldName)) {
            return;
        }
        LocalDateTime dateTime = DateUtils.dateToLocalDateTime(row.get(fieldName));
        row.put(fieldName, dateTime);
    }
}
