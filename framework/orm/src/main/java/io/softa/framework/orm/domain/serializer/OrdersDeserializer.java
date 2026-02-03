package io.softa.framework.orm.domain.serializer;

import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.domain.Orders;

/**
 * Deserialize a string list into an Orders object.
 */
public class OrdersDeserializer extends ValueDeserializer<Orders> {

    /**
     * @param p    Parsed used for reading JSON content
     * @param ctxt Context that can be used to access information about
     *             this deserialization activity.
     * @return Deserialized value
     */
    @Override
    public Orders deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
        JsonToken currentToken = p.currentToken();
        if (JsonToken.VALUE_STRING.equals(currentToken)) {
            // Use the Orders.of() method to create an Orders object from a JSON string
            String jsonString = p.readValueAs(String.class);
            return Orders.of(jsonString);
        } else if (JsonToken.START_ARRAY.equals(currentToken)) {
            // If the current token is an array, parse the entire array into an ArrayList, then call the Orders.of() method.
            List<Object> list = p.readValueAs(new TypeReference<>() {});
            return Orders.of(list);
        } else {
            throw new IllegalArgumentException("Sorting parameters do not support deserialization into Orders objects: {0}", p.readValueAs(Object.class));
        }
    }
}
