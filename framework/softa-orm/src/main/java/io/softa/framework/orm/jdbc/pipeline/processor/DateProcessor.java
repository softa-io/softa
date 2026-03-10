package io.softa.framework.orm.jdbc.pipeline.processor;

import java.time.LocalDate;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

import io.softa.framework.base.constant.EnvConstant;
import io.softa.framework.base.utils.DateUtils;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.meta.MetaField;

/**
 * Date field processor.
 * Both input and output are formatted to LocalDate for a DATE type.
 */
public class DateProcessor extends BaseProcessor {

    /**
     * Field processor object constructor
     *
     * @param metaField field metadata object
     * @param accessType access type
     */
    public DateProcessor(MetaField metaField, AccessType accessType) {
        super(metaField, accessType);
    }

    /**
     * Single-row data formatting processing.
     *
     * @param row Single-row data to be created/updated
     */
    public void processInputRow(Map<String, Object> row) {
        boolean isContain = row.containsKey(fieldName);
        checkReadonly(isContain);
        Object value = row.get(fieldName);
        if (AccessType.CREATE.equals(accessType)) {
            checkRequired(value);
            row.computeIfAbsent(fieldName, _ -> {
                String defaultValueUpper = StringUtils.upperCase(metaField.getDefaultValue());
                if (EnvConstant.TODAY.equals(defaultValueUpper) || EnvConstant.NOW.equals(defaultValueUpper)) {
                    // Assign the current time as the default value.
                    return EnvConstant.getToday();
                } else if (EnvConstant.YESTERDAY.equals(defaultValueUpper)) {
                    return EnvConstant.getYesterday();
                }
                else {
                    return metaField.getDefaultValueObject();
                }
            });
        } else if (isContain) {
            // UPDATE: Check if the required field is set to null.
            checkRequired(value);
        }
    }

    /**
     * Convert the date object output to LocalDate, compatible with LocalDate, Date, null.
     *
     * @param row Single-row output data
     */
    @Override
    public void processOutputRow(Map<String, Object> row) {
        if (!row.containsKey(fieldName)) {
            return;
        }
        LocalDate date = DateUtils.dateToLocalDate(row.get(fieldName));
        row.put(fieldName, date);
    }
}
