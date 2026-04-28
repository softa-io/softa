package io.softa.framework.orm.config;

import org.apache.pulsar.client.api.Schema;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.pulsar.core.DefaultSchemaResolver;
import org.springframework.pulsar.core.Resolved;
import org.springframework.pulsar.core.SchemaResolver;

@Configuration
public class PulsarSchemaConfig {

    @Bean
    public SchemaResolver schemaResolver() {
        return new DefaultSchemaResolver() {
            @Override
            @SuppressWarnings({"unchecked", "rawtypes"})
            public <T> @NonNull Resolved<Schema<T>> resolveSchema(Class<?> messageClass, boolean returnDefault) {
                // Keep the default schema resolution for primitive types, String, and byte[]
                Resolved<Schema<T>> resolved = super.resolveSchema(messageClass, false);
                if (resolved.value().isPresent() || messageClass == null) {
                    return resolved;
                }
                // For other types, use the custom PulsarJsonSchema
                return Resolved.of((Schema) new PulsarJsonSchema<>(messageClass));
            }
        };
    }
}
