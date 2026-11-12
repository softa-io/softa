package io.softa.framework.base.placeholder;

import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.extension.AbstractExtension;
import io.pebbletemplates.pebble.extension.AbstractNodeVisitor;
import io.pebbletemplates.pebble.extension.Filter;
import io.pebbletemplates.pebble.extension.NodeVisitor;
import io.pebbletemplates.pebble.extension.NodeVisitorFactory;
import io.pebbletemplates.pebble.node.PrintNode;
import io.pebbletemplates.pebble.node.expression.Expression;
import io.pebbletemplates.pebble.node.expression.FilterExpression;
import io.pebbletemplates.pebble.node.expression.FilterInvocationExpression;
import io.pebbletemplates.pebble.node.expression.TernaryExpression;
import io.pebbletemplates.pebble.template.EvaluationContext;
import io.pebbletemplates.pebble.template.EvaluationContextImpl;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import io.pebbletemplates.pebble.template.PebbleTemplateImpl;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.utils.DateUtils;

/**
 * Template engine wrapper based on Pebble, exposing two escaping profiles:
 * <ul>
 *   <li>{@link #render} / {@link #renderFilePath} — verbatim output, for targets
 *       that are not HTML: SQL DDL generation ({@code templates/sql/mysql/*.peb}),
 *       Java code generation ({@code templates/code/*.peb}), plain-text bodies.</li>
 *   <li>{@link #renderHtml} — HTML content (mail HTML bodies, rich-text document
 *       templates): every {@code {{ }}} output is HTML-escaped, so data values
 *       cannot inject markup. Template authors embed a trusted HTML fragment
 *       explicitly with the {@code raw} filter: {@code {{ fragment | raw }}}.</li>
 * </ul>
 * Pebble uses {@code {{ var }}} / {@code {% if %}} syntax, which is consistent
 * with the project-wide placeholder convention ({@code {{ }} }).
 * <p>
 * Both profiles print a date or time in the format the API serializes it with
 * ({@link DateUtils#formatTemporal}): {@code {{ createdTime }}} gives
 * {@code 2026-11-12 13:00:40}, not the ISO {@code toString()}. Only the printed
 * value is formatted — a filter, a test or a comparison still receives the date
 * object, so {@code {{ hireDate | date("dd MMM yyyy") }}} keeps working.
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

    private static final PebbleEngine HTML_ENGINE = new PebbleEngine.Builder()
            .autoEscaping(true)       // HTML-escape every {{ }} output; opt out per-value with | raw
            .strictVariables(false)   // Allow missing variables to render as empty
            .cacheActive(true)        // Cache compiled templates for performance
            .extension(new TemplateEngineExtension())
            .build();

    /**
     * Custom Pebble extension registering shared filters and the platform format for printed
     * dates and times.
     */
    private static final class TemplateEngineExtension extends AbstractExtension {
        @Override
        public Map<String, Filter> getFilters() {
            return Map.of("sqlLiteral", new SqlLiteralFilter());
        }

        @Override
        public List<NodeVisitorFactory> getNodeVisitors() {
            return List.of(template -> new TemporalOutputVisitor((PebbleTemplateImpl) template));
        }
    }

    /**
     * Formats the value of every {@code {{ }}} output at the point it is printed, so a date or time
     * reads as the API writes it.
     *
     * <p>Pebble runs an extension's visitors after its own escaper, which has by then wrapped each
     * unsafe output in an {@code escape} filter — and that filter turns any value into text. So the
     * formatting goes inside the wrapper, where the value is still the date object, and what gets
     * escaped stays exactly what the escaper decided. Each branch of a conditional is handled on its
     * own, the way the escaper treats them.
     */
    private static final class TemporalOutputVisitor extends AbstractNodeVisitor {

        /** The filter Pebble's escaper wraps an output in. */
        private static final String ESCAPE_FILTER = "escape";

        TemporalOutputVisitor(PebbleTemplateImpl template) {
            super(template);
        }

        @Override
        public void visit(PrintNode node) {
            node.setExpression(formatPrinted(node.getExpression()));
        }

        private static Expression<?> formatPrinted(Expression<?> printed) {
            if (printed instanceof TernaryExpression ternary) {
                ternary.setExpression2(formatPrinted(ternary.getExpression2()));
                ternary.setExpression3(formatPrinted(ternary.getExpression3()));
                return ternary;
            }
            if (printed instanceof FilterExpression filter
                    && filter.getRightExpression() instanceof FilterInvocationExpression invocation
                    && ESCAPE_FILTER.equals(invocation.getFilterName())) {
                filter.setLeft(formatPrinted(filter.getLeftExpression()));
                return filter;
            }
            return new TemporalText(printed);
        }
    }

    /** The value of {@code value}, a date or time turned into its platform text. */
    private record TemporalText(Expression<?> value) implements Expression<Object> {
        @Override
        public Object evaluate(PebbleTemplateImpl self, EvaluationContextImpl context) {
            return DateUtils.formatTemporal(value.evaluate(self, context));
        }

        @Override
        public int getLineNumber() {
            return value.getLineNumber();
        }

        @Override
        public void accept(NodeVisitor visitor) {
            visitor.visit(this);
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
     * Render a raw Pebble template string as HTML content with the given data model.
     * Every {@code {{ }}} output is HTML-escaped; a template embeds a trusted HTML
     * fragment explicitly with {@code {{ fragment | raw }}}.
     *
     * @param templateContent raw Pebble template content producing HTML
     * @param context         data model (Map of variable names to values)
     * @return rendered HTML
     */
    public static String renderHtml(String templateContent, Map<String, Object> context) {
        try {
            return renderTemplate(HTML_ENGINE.getLiteralTemplate(templateContent), context);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to render HTML template: {0}", e.getMessage());
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
