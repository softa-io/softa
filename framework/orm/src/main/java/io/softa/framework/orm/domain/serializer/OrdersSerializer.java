package io.softa.framework.orm.domain.serializer;

import io.softa.framework.orm.domain.Orders;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/**
 * Serialize an Orders object into a JSON string.
 */
public class OrdersSerializer extends ValueSerializer<Orders> {

    /**
     * Method that can be called to ask implementation to serialize
     * values of type this serializer handles.
     * @param orders       Value to serialize; can <b>not</b> be null.
     * @param gen         Generator used to output resulting Json content
     * @param serializers Provider that can be used to get serializers for
     *                    serializing Objects value contains, if any.
     */
    @Override
    public void serialize(Orders orders, JsonGenerator gen, SerializationContext serializers) throws JacksonException {
        // Generate a JSON string in the form of [["field1", "ASC"], ["field2", "DESC"]] using Orders' orderList
        gen.writePOJO(orders.getOrderList());
    }
}
