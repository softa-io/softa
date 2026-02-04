package io.softa.framework.web.config;

import io.softa.framework.base.constant.TimeConstant;
import jakarta.annotation.Nonnull;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.format.datetime.standard.DateTimeFormatterRegistrar;
import org.springframework.validation.Validator;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Base Web Config
 */
@Configuration
public class BaseWebConfig implements WebMvcConfigurer {

    private final MessageSource messageSource;

    public BaseWebConfig(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    /**
     * Define the validation message as key and get the translation.
     */
    @Override
    public Validator getValidator() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.setValidationMessageSource(messageSource);
        return validator;
    }

    /**
     * Handles formatting during the data binding process in the Spring MVC framework.
     * Allows passing String request parameters and serializes them into LocalDateTime, LocalDate, and LocalTime.
     *
     * @param registry the formatter registry
     */
    @Override
    public void addFormatters(@Nonnull FormatterRegistry registry) {
        DateTimeFormatterRegistrar registrar = new DateTimeFormatterRegistrar();
        registrar.setDateTimeFormatter(TimeConstant.DATETIME_FORMATTER);
        registrar.setDateFormatter(TimeConstant.DATE_FORMATTER);
        registrar.setTimeFormatter(TimeConstant.TIME_FORMATTER);
        registrar.registerFormatters(registry);
    }

    /**
     * Enable access to Swagger static resources.
     * @param registry registry
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/swagger-ui/**")
                .addResourceLocations("classpath:/META-INF/resources/swagger-ui/");
        registry.addResourceHandler("/v3/api-docs/**")
                .addResourceLocations("classpath:/META-INF/resources/");
    }

}
