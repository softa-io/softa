package io.softa.starter.studio.template.generator;

import java.io.StringWriter;
import java.util.Map;
import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.template.PebbleTemplate;

import io.softa.framework.base.exception.IllegalArgumentException;

/**
 * Template engine wrapper based on Pebble.
 * <p>
 * Provides a unified API for rendering templates from the classpath.
 * Used for both Java code generation ({@code templates/code/*.peb})
 * and SQL DDL generation ({@code templates/sql/mysql/*.peb}).
 * <p>
 * Pebble uses {@code {{ var }}} / {@code {% if %}} syntax, which is consistent
 * with the project-wide placeholder convention ({@code {{ }} }).
 */
public class TemplateEngine {

    private static final PebbleEngine ENGINE = new PebbleEngine.Builder()
            .autoEscaping(false)      // SQL and Java code must not be HTML-escaped
            .strictVariables(false)   // Allow missing variables to render as empty
            .cacheActive(true)        // Cache compiled templates for performance
            .build();

    private TemplateEngine() {}

    /**
     * Render a classpath template with the given data model.
     *
     * @param templatePath classpath-relative template path, e.g. "templates/code/entity/{{modelName}}.java.peb"
     * @param context      data model (Map of variable names to values)
     * @return rendered text
     */
    public static String render(String templatePath, Map<String, Object> context) {
        try {
            return renderTemplate(ENGINE.getTemplate(templatePath), context);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to render template {0}: {1}", templatePath, e.getMessage());
        }
    }

    /**
     * Render a raw Pebble template string with the given data model.
     *
     * @param templateContent raw Pebble template content
     * @param context         data model (Map of variable names to values)
     * @return rendered text
     */
    public static String renderString(String templateContent, Map<String, Object> context) {
        try {
            return renderTemplate(ENGINE.getLiteralTemplate(templateContent), context);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to render raw template: {0}", e.getMessage());
        }
    }

    private static String renderTemplate(PebbleTemplate template, Map<String, Object> context) throws Exception {
        StringWriter writer = new StringWriter();
        template.evaluate(writer, context);
        return writer.toString();
    }

}
