package io.softa.framework.orm.config;

import java.nio.charset.StandardCharsets;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.api.SchemaSerializationException;
import org.apache.pulsar.common.schema.SchemaInfo;
import org.apache.pulsar.common.schema.SchemaType;

import io.softa.framework.base.utils.JsonUtils;

public final class PulsarJsonSchema<T> implements Schema<T> {

    private final Class<T> clazz;
    private final SchemaInfo schemaInfo;

    public PulsarJsonSchema(Class<T> clazz) {
        this.clazz = clazz;
        this.schemaInfo = SchemaInfo.builder()
                .name(clazz.getName())
                .type(SchemaType.BYTES)
                .schema(new byte[0])
                .build();
    }

    @Override
    public byte[] encode(T message) {
        try {
            String json = JsonUtils.objectToString(message);
            return json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new SchemaSerializationException(e);
        }
    }

    @Override
    public T decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            return JsonUtils.stringToObject(new String(bytes, StandardCharsets.UTF_8), clazz);
        } catch (Exception e) {
            throw new SchemaSerializationException(e);
        }
    }

    @Override
    public SchemaInfo getSchemaInfo() {
        return schemaInfo;
    }

    @Override
    public Schema<T> clone() {
        return new PulsarJsonSchema<>(clazz);
    }

}
