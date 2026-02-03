package io.softa.framework.orm.domain.serializer;

import io.softa.framework.orm.domain.Filters;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/**
 * Filters serialization method is the same as toString()
 */
public class FiltersSerializer extends ValueSerializer<Filters> {

    /**
     * Method that can be called to ask implementation to serialize
     * values of type this serializer handles.
     * @param filters     Value to serialize; can <b>not</b> be null.
     * @param gen         Generator used to output resulting Json content
     * @param serializers Provider that can be used to get serializers for
     *                    serializing Objects value contains, if any.
     */
    @Override
    public void serialize(Filters filters, JsonGenerator gen, SerializationContext serializers) throws JacksonException {
        // Use the Filters.toString() method to generate a JSON string
        gen.writeRawValue(filters.toString());
    }
}
