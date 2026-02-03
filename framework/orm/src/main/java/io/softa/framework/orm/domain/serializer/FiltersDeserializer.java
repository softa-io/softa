package io.softa.framework.orm.domain.serializer;

import io.softa.framework.base.exception.JSONException;
import io.softa.framework.orm.domain.Filters;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Filters deserialization method, reuse of(String), of(List) methods, compatible with strings and List objects.
 */
public class FiltersDeserializer extends ValueDeserializer<Filters> {

    /**
     * @param p    Parsed used for reading JSON content
     * @param ctxt Context that can be used to access information about
     *             this deserialization activity.
     * @return Deserialized value
     */
    @Override
    public Filters deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
        // Use the Filters.of() method to create a filters object from a JSON string
        JsonToken currentToken = p.currentToken();
        if (currentToken == JsonToken.VALUE_STRING) {
            // If the current token is a string, call the Filters.of() method directly
            String jsonString = p.readValueAs(String.class);
            return Filters.of(jsonString);
        } else if (currentToken == JsonToken.START_ARRAY) {
            // If the current token is an array, parse the entire array into an ArrayList,
            // then call the Filters.of() method
            List<Object> list = p.readValueAs(new TypeReference<ArrayList<Object>>() {});
            return Filters.of(list);
        } else {
            throw new JSONException("The parameter does not support deserialization into a Filters object: {0}", p.readValueAs(Object.class));
        }
    }
}
