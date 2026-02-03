package io.softa.framework.orm.jdbc.pipeline.processor;

import tools.jackson.databind.JsonNode;
import io.softa.framework.base.enums.AccessType;
import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.exception.JSONException;
import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.meta.MetaField;
import org.apache.commons.lang3.StringUtils;

import java.util.Map;

/**
 * JSON field processor
 */
public class JsonProcessor extends BaseProcessor {

    public JsonProcessor(MetaField metaField, AccessType accessType) {
        super(metaField, accessType);
    }

    /**
     * Convert the JSON object to a string and store it in the database.
     *
     * @param row Single-row data to be created/updated
     */
    @Override
    public void processInputRow(Map<String, Object> row) {
        checkReadonly(row);
        Object value = row.get(fieldName);
        if (value != null) {
            row.put(fieldName, JsonUtils.objectToString(value));
        } else if (AccessType.CREATE.equals(accessType)) {
            checkRequired(row);
            row.computeIfAbsent(fieldName, k -> metaField.getDefaultValueObject());
        } else if (row.containsKey(fieldName)) {
            // The field is set to null, check if it is a required field.
            checkRequired(row);
        }
    }

    /**
     * Convert the string value to a JsonNode object and replace the original value.
     *
     * @param row Single-row data to be read
     */
    @Override
    public void processOutputRow(Map<String, Object> row) {
        if (!row.containsKey(fieldName) || !(row.get(fieldName) instanceof String strValue)) {
            return;
        }
        try {
            if (StringUtils.isBlank(strValue)) {
                row.put(fieldName, null);
            } else {
                row.put(fieldName, JsonUtils.stringToObject(strValue, JsonNode.class));
            }
        } catch (JSONException e) {
            throw new IllegalArgumentException("The value of field {0}: {1} is not a valid JSON string: {2}.",
                    fieldName, row.get(fieldName), e.getMessage());
        } catch (ClassCastException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }
}
