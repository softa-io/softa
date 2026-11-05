package io.softa.framework.orm.jdbc.pipeline.processor;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;

import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.enums.AccessType;
import io.softa.framework.orm.enums.ConvertType;
import io.softa.framework.orm.enums.FieldType;
import io.softa.framework.orm.meta.MetaField;

/**
 * Processor for multiple string fields. Such as MultiString and MultiOption fields.
 */
public class MultiStringProcessor extends BaseProcessor {

    private final ConvertType convertType;

    public MultiStringProcessor(MetaField metaField, AccessType accessType, ConvertType convertType) {
        super(metaField, accessType);
        this.convertType = convertType;
    }

    /**
     * Convert the multiple value to a string for storage.
     *
     * @param row Single-row data to be updated
     */
    @Override
    public void processInputRow(Map<String, Object> row) {
        boolean isContain = row.containsKey(fieldName);
        checkReadonly(isContain);
        Object value = row.get(fieldName);
        if (value instanceof List<?> listValue) {
            List<Object> items = listValue.stream().map(MultiStringProcessor::toStoredItem).toList();
            row.put(fieldName, StringUtils.join(items, ","));
        } else if (value instanceof String) {
            row.put(fieldName, value);
        } else if (AccessType.CREATE.equals(accessType)) {
            checkNotBlank(value);
            row.computeIfAbsent(fieldName, k -> metaField.getDefaultValueObject());
        }
    }

    /**
     * An enum element is stored as its item code ({@code @JsonValue}), as {@link StringProcessor} stores
     * a single option, so the read side resolves it back. Any other element keeps its string form.
     *
     * @param item One element of the multiple value
     * @return The item code of an enum, otherwise the element itself
     */
    private static Object toStoredItem(Object item) {
        return item instanceof Enum ? JsonUtils.getMapper().convertValue(item, String.class) : item;
    }

    /**
     * Convert the string value of the MultiString field to a list.
     *
     * @param row Single-row output data
     */
    @Override
    public void processOutputRow(Map<String, Object> row) {
        if (!row.containsKey(fieldName)) {
            return;
        } else if (ConvertType.DISPLAY.equals(convertType) && FieldType.MULTI_STRING.equals(metaField.getFieldType())) {
            return;
        }
        String value = (String) row.get(fieldName);
        Object result = StringUtils.isBlank(value) ? null : Arrays.asList(StringUtils.split(value, ","));
        row.put(fieldName, result);
    }

}
