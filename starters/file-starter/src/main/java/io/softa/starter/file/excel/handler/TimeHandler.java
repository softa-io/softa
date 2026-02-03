package io.softa.starter.file.excel.handler;

import org.apache.commons.lang3.StringUtils;

import io.softa.framework.base.exception.ValidationException;
import io.softa.framework.base.utils.DateUtils;
import io.softa.framework.orm.meta.MetaField;
import io.softa.starter.file.dto.ImportFieldDTO;

/**
 * TimeHandler
 * Compatible with the following time formats:
 * HH:MM:SS
 * HH:MM
 */
public class TimeHandler extends BaseImportHandler {

    public TimeHandler(MetaField metaField, ImportFieldDTO importFieldDTO) {
        super(metaField, importFieldDTO);
    }

    /**
     * Handle the value of the time field
     *
     * @param value The value
     * @return The handled value
     */
    public Object handleValue(Object value) {
        if (value instanceof String timeStr && StringUtils.isNotBlank(timeStr)) {
            // Convert the time string to a standard format
            timeStr = timeStr.trim().toLowerCase();
            return this.handleTimeString(timeStr);
        }
        return value;
    }

    /**
     * Handle the time string
     *
     * @param timeStr The time string
     * @return The handled time string
     */
    private String handleTimeString(String timeStr) {
        String standardTimeStr = DateUtils.formatAndValidateTime(timeStr);
        if (standardTimeStr != null) {
            return standardTimeStr;
        } else {
            throw new ValidationException("The Time field `{0}` is incorrect: `{1}`", labelName, timeStr);
        }
    }

}
