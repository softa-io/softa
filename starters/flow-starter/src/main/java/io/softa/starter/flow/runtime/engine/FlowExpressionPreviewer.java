package io.softa.starter.flow.runtime.engine;

import java.util.LinkedHashMap;
import java.util.Map;

import io.softa.framework.base.placeholder.PlaceholderToken;
import io.softa.framework.base.placeholder.PlaceholderUtils;
import io.softa.framework.orm.compute.ComputeUtils;
import io.softa.starter.flow.dto.FlowExpressionPreview;
import io.softa.starter.flow.dto.FlowExpressionPreviewRequest;

/**
 * Evaluates what the designer tries — an expression, or a text with {@code {{ }}} placeholders — over sample
 * variables, on the engine a flow runs with and the way a flow reads it: an expression wrapped whole in
 * {@code {{ }}} is the expression; a template that is one placeholder gives that placeholder's value, any other is
 * interpolated into text.
 */
public final class FlowExpressionPreviewer {

    private FlowExpressionPreviewer() {
    }

    public static FlowExpressionPreview preview(FlowExpressionPreviewRequest request) {
        Map<String, Object> variables = request.variables() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(request.variables());
        try {
            Object result = request.template()
                    ? interpolate(request.expression(), variables)
                    : ComputeUtils.execute(unwrapped(request.expression()), variables);
            return new FlowExpressionPreview(true, result, result == null ? null : result.getClass().getSimpleName(),
                    null);
        } catch (RuntimeException e) {
            return new FlowExpressionPreview(false, null, null, e.getMessage());
        }
    }

    private static Object interpolate(String template, Map<String, Object> variables) {
        PlaceholderToken whole = PlaceholderUtils.parsePlaceholder(template.trim());
        if (whole != null && PlaceholderUtils.extractVariables(template).size() == 1) {
            return ComputeUtils.execute(whole.getContent(), variables);
        }
        return ComputeUtils.stringInterpolation(template, variables);
    }

    private static String unwrapped(String expression) {
        PlaceholderToken whole = PlaceholderUtils.parsePlaceholder(expression.trim());
        return whole == null ? expression : whole.getContent();
    }
}
