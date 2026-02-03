package io.softa.starter.file.excel.handler;

import org.apache.commons.lang3.StringUtils;

import io.softa.framework.base.exception.ValidationException;
import io.softa.framework.base.utils.DateUtils;
import io.softa.framework.orm.meta.MetaField;
import io.softa.starter.file.dto.ImportFieldDTO;

/**
 * DateHandler
 * Compatible with the following date formats:
 *      YYYY-MM-DD: 2024-09-15, 2024-9-5
 *      YYYY/MM/DD
 *      YYYY_MM_DD
 *      YYYY.MM.DD
 *      YYYYMMDD
 *      YYYY    =  YYYY-01-01
 *      YYYY-MM =  YYYY-MM-01: 2024-09, 2024-9
 *      YYYY/MM =  YYYY-MM-01
 *      YYYY_MM =  YYYY-MM-01
 *      YYYY.MM =  YYYY-MM-01
 */
public class DateHandler extends BaseImportHandler {

    public DateHandler(MetaField metaField, ImportFieldDTO importFieldDTO) {
        super(metaField, importFieldDTO);
    }

    /**
     * Handle the value of the date field
     * @param value The value
     * @return The handled value
     */
    public Object handleValue(Object value) {
        if (value instanceof String dateStr && StringUtils.isNotBlank(dateStr)) {
            // Convert the date string to a standard format
            dateStr = dateStr.trim().toLowerCase();
            return this.handleDateString(dateStr);
        }
        return value;
    }

    /**
     * Handle the date string
     * @param dateStr The date string
     * @return The handled date string
     */
    private String handleDateString(String dateStr) {
        String standardDateStr = DateUtils.formatAndValidateDate(dateStr);
        if (standardDateStr != null) {
            return standardDateStr;
        } else {
            throw new ValidationException("The date field `{0}` is incorrect: `{1}`", labelName, dateStr);
        }
    }

}
