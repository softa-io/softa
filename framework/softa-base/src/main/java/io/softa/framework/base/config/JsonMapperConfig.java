package io.softa.framework.base.config;

import io.softa.framework.base.constant.TimeConstant;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ext.javatime.deser.LocalDateDeserializer;
import tools.jackson.databind.ext.javatime.deser.LocalDateTimeDeserializer;
import tools.jackson.databind.ext.javatime.deser.LocalTimeDeserializer;
import tools.jackson.databind.ext.javatime.ser.LocalDateSerializer;
import tools.jackson.databind.ext.javatime.ser.LocalDateTimeSerializer;
import tools.jackson.databind.ext.javatime.ser.LocalTimeSerializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * Global JsonMapper Configuration
 */
@Configuration
public class JsonMapperConfig {

    /**
     * Register the LocalDate and LocalDateTime serialization and deserialization formats
     * @return JsonMapper
     */
    @Bean
    public JsonMapper jsonMapper() {
        // Create a new SimpleModule to add the BigDecimal serialization and deserialization format
        SimpleModule simpleModule = new SimpleModule()
        .addSerializer(BigDecimal.class, ToStringSerializer.instance)
        .addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(TimeConstant.DATETIME_FORMATTER))
        .addDeserializer(LocalDateTime.class, new LocalDateTimeDeserializer(TimeConstant.DATETIME_FORMATTER))
        .addSerializer(LocalDate.class, new LocalDateSerializer(TimeConstant.DATE_FORMATTER))
        .addDeserializer(LocalDate.class, new LocalDateDeserializer(TimeConstant.DATE_FORMATTER))
        .addSerializer(LocalTime.class, new LocalTimeSerializer(TimeConstant.TIME_FORMATTER))
        .addDeserializer(LocalTime.class, new LocalTimeDeserializer(TimeConstant.TIME_FORMATTER));

        // Build a JsonMapper, register the SimpleModule and ignore unknown properties
        return JsonMapper.builder()
                .addModule(simpleModule)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .build();
    }
}
