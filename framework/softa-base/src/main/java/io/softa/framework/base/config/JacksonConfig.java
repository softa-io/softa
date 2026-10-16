package io.softa.framework.base.config;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.ext.javatime.deser.LocalDateDeserializer;
import tools.jackson.databind.ext.javatime.deser.LocalDateTimeDeserializer;
import tools.jackson.databind.ext.javatime.deser.LocalTimeDeserializer;
import tools.jackson.databind.ext.javatime.ser.LocalDateSerializer;
import tools.jackson.databind.ext.javatime.ser.LocalDateTimeSerializer;
import tools.jackson.databind.ext.javatime.ser.LocalTimeSerializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;
import tools.jackson.databind.type.LogicalType;

import io.softa.framework.base.constant.TimeConstant;

/**
 * Global JsonMapper Configuration
 */
@Configuration
public class JacksonConfig {

    /**
     * Register the LocalDate and LocalDateTime serialization and deserialization formats
     * @return JsonMapper
     */
    @Bean
    public JacksonModule customJacksonModule() {
        // Create a new SimpleModule to add the BigDecimal serialization and deserialization format
        SimpleModule simpleModule = new SimpleModule();
        // Serialize BigDecimal as String to avoid precision loss in JavaScript
        simpleModule.addSerializer(BigDecimal.class, ToStringSerializer.instance);
        // Register LocalDateTime, LocalDate, and LocalTime serializers and deserializers
        simpleModule.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(TimeConstant.DATETIME_FORMATTER))
                .addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(TimeConstant.DATETIME_FORMATTER))
                .addSerializer(LocalDate.class, new LocalDateSerializer(TimeConstant.DATE_FORMATTER))
                .addDeserializer(LocalDate.class, new LocalDateDeserializer(TimeConstant.DATE_FORMATTER))
                .addSerializer(LocalTime.class, new LocalTimeSerializer(TimeConstant.TIME_FORMATTER))
                .addDeserializer(LocalTime.class, new LocalTimeDeserializer(TimeConstant.TIME_FORMATTER));
        // Serialize Long and BigInteger as String to avoid precision loss in JavaScript
        simpleModule.addSerializer(Long.class, ToStringSerializer.instance)
                .addSerializer(Long.TYPE, ToStringSerializer.instance)
                .addSerializer(BigInteger.class, ToStringSerializer.instance);
        return simpleModule;
    }

    /**
     * Global Jackson deserialization policy configuration
     * @return JsonMapperBuilderCustomizer
     */
    @Bean
    public JsonMapperBuilderCustomizer globalJacksonPolicy() {
        return JacksonConfig::applyGlobalPolicy;
    }

    /**
     * The deserialization policy every softa mapper shares:
     * the Spring-managed {@link JsonMapper} through {@link #globalJacksonPolicy()},
     * and the one {@code JsonUtils} builds for itself when no application context is up,
     * so JSON reads the same inside and outside the application.
     *
     * @param builder the mapper builder to configure
     * @return the same builder
     */
    public static JsonMapper.Builder applyGlobalPolicy(JsonMapper.Builder builder) {
        // Ignore unknown properties during deserialization
        builder.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // Form UIs often clear optional selects / numbers / toggles to "" instead of omitting
        // the field or sending null. Default Jackson fails scalar binding with
        // InvalidFormatException (HttpMessageNotReadableException → 500) before the
        // controller runs. Treat empty string as null for these logical types so draft /
        // partial payloads stay readable. Shared by softa CRUD and app @RequestBody
        // mappers — invalid non-empty values still fail (not READ_UNKNOWN_ENUM_VALUES_AS_NULL).
        for (LogicalType type : new LogicalType[] {
                LogicalType.Enum, LogicalType.Integer, LogicalType.Float, LogicalType.Boolean
        }) {
            builder.withCoercionConfig(type, cfg ->
                    cfg.setCoercion(CoercionInputShape.EmptyString, CoercionAction.AsNull));
        }
        return builder;
    }

    // ===== Jackson 2 Compatibility =====
    /**
     * Register the LocalDate and LocalDateTime serialization and deserialization formats
     * @return ObjectMapper
     */
    @Bean
    public ObjectMapper objectMapper() {
        JavaTimeModule javaTimeModule = new JavaTimeModule();
        javaTimeModule.addSerializer(LocalDateTime.class, new com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer(TimeConstant.DATETIME_FORMATTER));
        javaTimeModule.addDeserializer(LocalDateTime.class, new com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer(TimeConstant.DATETIME_FORMATTER));
        javaTimeModule.addSerializer(LocalDate.class, new com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer(TimeConstant.DATE_FORMATTER));
        javaTimeModule.addDeserializer(LocalDate.class, new com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer(TimeConstant.DATE_FORMATTER));
        javaTimeModule.addSerializer(LocalTime.class, new com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer(TimeConstant.TIME_FORMATTER));
        javaTimeModule.addDeserializer(LocalTime.class, new com.fasterxml.jackson.datatype.jsr310.deser.LocalTimeDeserializer(TimeConstant.TIME_FORMATTER));
        // Create a new ObjectMapper
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(javaTimeModule);
        // Ignore unknown properties
        objectMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // Mirror applyGlobalPolicy for callers still on the Jackson 2 ObjectMapper bean.
        for (com.fasterxml.jackson.databind.type.LogicalType type : new com.fasterxml.jackson.databind.type.LogicalType[] {
                com.fasterxml.jackson.databind.type.LogicalType.Enum,
                com.fasterxml.jackson.databind.type.LogicalType.Integer,
                com.fasterxml.jackson.databind.type.LogicalType.Float,
                com.fasterxml.jackson.databind.type.LogicalType.Boolean
        }) {
            objectMapper.coercionConfigFor(type)
                    .setCoercion(
                            com.fasterxml.jackson.databind.cfg.CoercionInputShape.EmptyString,
                            com.fasterxml.jackson.databind.cfg.CoercionAction.AsNull);
        }
        return objectMapper;
    }
}
