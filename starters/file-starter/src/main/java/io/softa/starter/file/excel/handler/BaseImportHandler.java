package io.softa.starter.file.excel.handler;

import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

import io.softa.framework.base.exception.ValidationException;
import io.softa.framework.orm.constant.FileConstant;
import io.softa.framework.orm.meta.MetaField;
import io.softa.starter.file.dto.ImportFieldDTO;

/**
 * BaseImportHandler
 */
public abstract class BaseImportHandler {

    protected final MetaField metaField;
    protected final String modelName;
    protected final String fieldName;
    protected final String labelName;
    protected final ImportFieldDTO importFieldDTO;

    public BaseImportHandler(MetaField metaField, ImportFieldDTO importFieldDTO) {
        this.metaField = metaField;
        this.modelName = metaField.getModelName();
        this.fieldName = metaField.getFieldName();
        this.labelName = metaField.getLabelName();
        this.importFieldDTO = importFieldDTO;
    }

    /**
     * Handle the rows.
     * Catch the ValidationException and set the failed reason to the row.
     *
     * @param rows The rows
     */
    public void handleRows(List<Map<String, Object>> rows) {
        rows.forEach(row -> {
            try {
                handleRow(row);
            } catch (ValidationException e) {
                String failedReason = "";
                if (row.get(FileConstant.FAILED_REASON) != null) {
                    failedReason = row.get(FileConstant.FAILED_REASON) + "; ";
                }
                failedReason += e.getMessage();
                row.put(FileConstant.FAILED_REASON, failedReason);
            }
        });
    }

    /**
     * Handle the row
     * Properties of the row will be modified:
     *      - Check if the field is required
     *      - Set the default value if the field is empty and the default value is set
     *      - Remove the field if the field is empty and ignoreEmpty is true
     *
     * @param row The row
     */
    public void handleRow(Map<String, Object> row) {
        Object value = row.get(fieldName);
        boolean isEmpty = valueIsEmpty(value);
        if (isEmpty) {
            checkRequired();
            if (importFieldDTO.getDefaultValue() != null) {
                row.put(fieldName, importFieldDTO.getDefaultValue());
            } else if (Boolean.TRUE.equals(importFieldDTO.getIgnoreEmpty())) {
                row.remove(fieldName);
            }
        } else {
            row.put(fieldName, handleValue(value));
        }
    }

    /**
     * Handle the value of the field
     *
     * @param value The value
     * @return The handled value
     */
    public Object handleValue(Object value) {
        // handle value
        return value;
    }

    /**
     * Check whether the value is empty
     *
     * @param value The value
     * @return Whether the value is empty
     */
    public boolean valueIsEmpty(Object value) {
        return value == null || (value instanceof String valueStr && StringUtils.isBlank(valueStr));
    }

    /**
     * Check required
     */
    public void checkRequired() {
        if (Boolean.TRUE.equals(importFieldDTO.getRequired())) {
            throw new ValidationException("The field `{0}` is required", labelName);
        }
    }

}
