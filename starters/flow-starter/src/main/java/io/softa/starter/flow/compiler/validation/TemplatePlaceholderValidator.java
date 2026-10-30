package io.softa.starter.flow.compiler.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.softa.framework.base.constant.StringConstant;
import io.softa.framework.base.placeholder.PlaceholderKind;
import io.softa.framework.base.placeholder.PlaceholderToken;
import io.softa.framework.base.placeholder.PlaceholderUtils;
import io.softa.framework.orm.compute.ComputeUtils;
import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.compiler.FlowGraphContext;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphNode;

/**
 * A node's settings interpolate every {@code {{ }}} placeholder they hold as the node runs — a mail's subject, a
 * webhook's URL, a mapping's value — so each must be one that runs: an expression that compiles, a variable, or a
 * reserved field reference ({@code {{ @name }}}), and closed. Without this a typo publishes and fails the instance
 * that reaches the node.
 */
public class TemplatePlaceholderValidator implements FlowValidator {

    private static final String INVALID_TEMPLATE_PLACEHOLDER = "INVALID_TEMPLATE_PLACEHOLDER";

    @Override
    public List<CompileDiagnostic> validate(DesignFlowDefinition definition, FlowGraphContext context) {
        List<CompileDiagnostic> d = new ArrayList<>();
        for (FlowGraphNode node : context.nodeMap().values()) {
            if (node.getConfig() != null) {
                check(node, "", node.getConfig(), d);
            }
        }
        return d;
    }

    private static void check(FlowGraphNode node, String path, Object value, List<CompileDiagnostic> d) {
        switch (value) {
            case String text when text.contains(StringConstant.PLACEHOLDER_PREFIX) -> checkText(node, path, text, d);
            case Map<?, ?> map -> map.forEach((key, entry) ->
                    check(node, path.isEmpty() ? String.valueOf(key) : path + "." + key, entry, d));
            case List<?> list -> {
                for (int i = 0; i < list.size(); i++) {
                    check(node, path + "[" + i + "]", list.get(i), d);
                }
            }
            case null, default -> {
            }
        }
    }

    private static void checkText(FlowGraphNode node, String path, String text, List<CompileDiagnostic> d) {
        String name = "Node '" + Objects.requireNonNullElse(node.getLabel(), node.getId()) + "' (" + path + ")";
        int open = text.lastIndexOf(StringConstant.PLACEHOLDER_PREFIX);
        if (text.indexOf(StringConstant.PLACEHOLDER_SUFFIX, open) < 0) {
            d.add(diagnostic(node, name + ": a placeholder opens with {{ and is not closed with }}"));
        }
        for (String content : PlaceholderUtils.extractVariables(text)) {
            String refusal = refusal(content);
            if (refusal != null) {
                d.add(diagnostic(node, name + ": the placeholder {{ " + content + " }} " + refusal));
            }
        }
    }

    /** Why the placeholder would not run, or {@code null}. */
    private static String refusal(String content) {
        if (content.isEmpty()) {
            return "is empty";
        }
        PlaceholderToken token;
        try {
            token = PlaceholderUtils.parsePlaceholder(StringConstant.PLACEHOLDER_PREFIX + content
                    + StringConstant.PLACEHOLDER_SUFFIX);
        } catch (RuntimeException e) {
            return "is not a valid reference: " + e.getMessage();
        }
        return token == null || token.getKind() != PlaceholderKind.EXPRESSION || ComputeUtils.validateExpression(content)
                ? null
                : "does not compile";
    }

    private static CompileDiagnostic diagnostic(FlowGraphNode node, String message) {
        return CompileDiagnostic.nodeLevel(node.getId(), node.getType().getType(), INVALID_TEMPLATE_PLACEHOLDER, message);
    }
}
