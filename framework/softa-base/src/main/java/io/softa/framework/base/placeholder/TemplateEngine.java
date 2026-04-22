package io.softa.framework.base.placeholder;

import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.extension.AbstractExtension;
import io.pebbletemplates.pebble.extension.Filter;
import io.pebbletemplates.pebble.template.EvaluationContext;
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
 * <p>
 * Built-in filters:
 * <ul>
 *   <li>{@code sqlLiteral} — escapes a value for safe embedding inside a single-quoted
 *       SQL literal (e.g. a {@code COMMENT '...'} clause). Returns {@code ""} for null.
 *       Usage: {@code COMMENT '{{ description | sqlLiteral }}'}.</li>
 * </ul>
 */
public class TemplateEngine {

    private static final PebbleEngine ENGINE = new PebbleEngine.Builder()
            .autoEscaping(false)      // SQL and Java code must not be HTML-escaped
            .strictVariables(false)   // Allow missing variables to render as empty
            .cacheActive(true)        // Cache compiled templates for performance
            .extension(new TemplateEngineExtension())
            .build();

    /**
     * Custom Pebble extension registering shared filters.
     */
    private static final class TemplateEngineExtension extends AbstractExtension {
        @Override
        public Map<String, Filter> getFilters() {
            return Map.of("sqlLiteral", new SqlLiteralFilter());
        }
    }

    /**
     * Pebble filter that escapes a value for safe embedding inside a single-quoted
     * SQL literal. It doubles every {@code '} so the enclosing quotes of the template
     * literal (e.g. {@code COMMENT '...'}) stay balanced regardless of the input.
     * <p>
     * Null and empty values render as empty string. Backslashes are left untouched
     * because MySQL {@code NO_BACKSLASH_ESCAPES} mode is not assumed; the surrounding
     * {@code '...'} quoting is the only contract this filter enforces.
     */
    private static final class SqlLiteralFilter implements Filter {
        @Override
        public List<String> getArgumentNames() {
            return List.of();
        }

        @Override
        public Object apply(Object input,
                            Map<String, Object> args,
                            PebbleTemplate self,
                            EvaluationContext context,
                            int lineNumber) {
            if (input == null) {
                return "";
            }
            return input.toString().replace("'", "''");
        }
    }

    private TemplateEngine() {}

    /**
     * Render a raw Pebble template string with the given data model.
     *
     * @param templateContent raw Pebble template content
     * @param context         data model (Map of variable names to values)
     * @return rendered text
     */
    public static String render(String templateContent, Map<String, Object> context) {
        try {
            return renderTemplate(ENGINE.getLiteralTemplate(templateContent), context);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to render raw template: {0}", e.getMessage());
        }
    }

    /**
     * Render a classpath template with the given data model.
     *
     * @param templatePath classpath-relative template path, e.g. "templates/code/entity/{{modelName}}.java.peb"
     * @param context      data model (Map of variable names to values)
     * @return rendered text
     */
    public static String renderFilePath(String templatePath, Map<String, Object> context) {
        try {
            return renderTemplate(ENGINE.getTemplate(templatePath), context);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to render template {0}: {1}", templatePath, e.getMessage());
        }
    }

    private static String renderTemplate(PebbleTemplate template, Map<String, Object> context) throws Exception {
        StringWriter writer = new StringWriter();
        template.evaluate(writer, context);
        return writer.toString();
    }

}
