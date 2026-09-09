package io.softa.framework.orm.jdbc.pipeline.processor;

import java.util.Map;
import org.apache.commons.lang3.StringUtils;

import io.softa.framework.base.constant.StringConstant;
import io.softa.framework.base.exception.BusinessException;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ValueConstraints;

/**
 * String field processor
 */
public class StringProcessor extends BaseProcessor {

    public StringProcessor(MetaField metaField, AccessType accessType) {
        super(metaField, accessType);
    }

    /**
     * Check if the string length exceeds the limit, excluding default length, which might be 0.
     * @param value String data
     */
    private void checkStringLength(String value) {
        // Null-safe + null-tolerant: a field with no declared/resolved length
        // (e.g. a no-code STRING the studio resolver hasn't sized) skips the
        // app-level check and lets the column's own width enforce — never NPEs
        // on the unboxing. Annotation-lane fields always have a resolved length.
        if (metaField.getLength() != null && metaField.getLength() > 0
                && value.length() > metaField.getLength()) {
            throw new BusinessException("""
                    Model field {0}: {1} length exceeds the limit of {2}. The actual length is {3}, value: {4}""",
                    metaField.getModelName(), metaField.getFieldName(), metaField.getLength(), value.length(), value);
        }
    }

    /**
     * String field length check
     */
    @Override
    public void processInputRow(Map<String, Object> row) {
        boolean isContain = row.containsKey(fieldName);
        checkReadonly(isContain);
        Object obj = row.get(fieldName);
        String value;
        if (obj instanceof Enum) {
            value = JsonUtils.getMapper().convertValue(obj, String.class);
        } else if (obj instanceof String strValue) {
            value = strValue;
        } else {
            value = obj == null ? null : String.valueOf(obj);
        }
        if (StringUtils.isNotBlank(value)) {
            // Remove the leading and trailing spaces
            value = value.trim();
            checkStringLength(value);
            // Trimmed first: a pattern describes the value, and the surrounding space is not part of
            // it — anchoring would otherwise reject a cell someone pasted with a trailing space.
            ValueConstraints.checkPattern(metaField, value);
            if (metaField.getMaskingType() != null && value.contains(StringConstant.MASKING_SYMBOL)) {
                // If the masking field contains `****`,
                // throw an exception to prevent from setting the field incorrectly.
                throw new BusinessException("""
                        Model field {0}: {1} is a masking field, and cannot be set including `****`.""",
                        metaField.getModelName(), metaField.getFieldName());
            }
        } else if (AccessType.CREATE.equals(accessType)) {
            checkRequired(value);
            row.computeIfAbsent(fieldName, k -> metaField.getDefaultValueObject());
            return;
        } else if (isContain) {
            // If the field is set to null, check if it is a required field.
            checkRequired(value);
        }
        row.put(fieldName, value);
    }

    /**
     * Process single-row output data.
     *
     * @param row The single-row output data
     */
    @Override
    public void processOutputRow(Map<String, Object> row) {
        Object obj = row.get(fieldName);
        if (obj != null && !(obj instanceof String)) {
            row.put(fieldName, String.valueOf(obj));
        }
    }
}
