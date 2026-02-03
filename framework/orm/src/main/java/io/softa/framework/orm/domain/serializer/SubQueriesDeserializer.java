package io.softa.framework.orm.domain.serializer;

import io.softa.framework.base.utils.JsonUtils;
import io.softa.framework.orm.domain.SubQueries;
import org.apache.commons.lang3.StringUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Deserialize a JSON string into an SubQueries object.
 */
public class SubQueriesDeserializer extends ValueDeserializer<SubQueries> {

    /**
     * @param p    Parsed used for reading JSON content
     * @param ctxt Context that can be used to access information about
     *             this deserialization activity.
     * @return Deserialized value
     */
    @Override
    public SubQueries deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
        JsonToken currentToken = p.currentToken();
        if (JsonToken.VALUE_STRING.equals(currentToken)) {
            String jsonString = p.readValueAs(String.class);
            if (StringUtils.isNotBlank(jsonString)) {
                return JsonUtils.stringToObject(jsonString, SubQueries.class);
            }
        }
        return null;
    }
}
