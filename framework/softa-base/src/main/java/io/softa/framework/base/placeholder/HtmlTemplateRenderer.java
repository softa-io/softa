package io.softa.framework.base.placeholder;

import java.io.StringReader;
import java.io.StringWriter;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateExceptionHandler;

import io.softa.framework.base.constant.StringConstant;
import io.softa.framework.base.exception.SystemException;

/**
 * HTML template renderer using FreeMarker.
 * <p>
 * Converts {@code {{ var }}} placeholders to FreeMarker {@code ${var}} syntax,
 * then renders the template with the provided data model.
 * <p>
 * This utility centralizes FreeMarker configuration and the placeholder conversion
 * so that any module (file export, email, etc.) can render HTML templates consistently
 * without duplicating the setup.
 * <p>
 * Requires {@code spring-boot-starter-freemarker} on the classpath.
 *
 * <pre>{@code
 * String html = HtmlTemplateRenderer.render("<p>Hello, {{ name }}</p>", dataMap);
 * }</pre>
 */
public final class HtmlTemplateRenderer {

    private HtmlTemplateRenderer() {}

    private static final Configuration FREEMARKER_CONFIG;

    static {
        FREEMARKER_CONFIG = new Configuration(Configuration.VERSION_2_3_28);
        FREEMARKER_CONFIG.setDefaultEncoding("UTF-8");
        FREEMARKER_CONFIG.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
    }

    /**
     * Render an HTML template string with the given data model.
     * <p>
     * Placeholder syntax {@code {{ var }}} is converted to FreeMarker {@code ${var}}
     * before rendering, keeping user-facing templates consistent with the project-wide
     * {@link StringConstant#PLACEHOLDER_PREFIX} / {@link StringConstant#PLACEHOLDER_SUFFIX} convention.
     *
     * @param htmlTemplate the HTML template content with {@code {{ var }}} placeholders
     * @param data         the data model (Map or POJO) to render the template
     * @return the rendered HTML string
     * @throws SystemException if FreeMarker rendering fails
     */
    public static String render(String htmlTemplate, Object data) {
        String ftlContent = htmlTemplate
                .replace(StringConstant.PLACEHOLDER_PREFIX, "${")
                .replace(StringConstant.PLACEHOLDER_SUFFIX, "}");
        try {
            Template template = new Template("htmlTemplate", new StringReader(ftlContent), FREEMARKER_CONFIG);
            StringWriter writer = new StringWriter();
            template.process(data, writer);
            return writer.toString();
        } catch (Exception e) {
            throw new SystemException("Failed to render HTML template with FreeMarker.", e);
        }
    }
}

