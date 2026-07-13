package io.softa.starter.flow.runtime.task.builtin;

import java.util.*;
import org.jspecify.annotations.Nullable;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.exception.ValidationException;
import io.softa.framework.base.placeholder.PlaceholderKind;
import io.softa.framework.base.placeholder.PlaceholderToken;
import io.softa.framework.base.placeholder.PlaceholderUtils;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.compute.ComputeUtils;
import io.softa.framework.orm.domain.FilterUnit;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.enums.FilterType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;

/**
 * Utility class for resolving placeholder variables and expressions inside
 * row templates, filters, and primary key variables.
 * <p>
 * Ported from {@code flow-starter}'s {@code FlowUtils} to work with plain
 * {@code Map<String, Object>} variables instead of the legacy {@code NodeContext}.
 * </p>
 */
public final class VariableResolver {

    private VariableResolver() {
    }

    /**
     * Resolve a row data template into a concrete field→value map.
     * Each value in the template can be a constant, a variable placeholder {@code {{ var }}},
     * or an expression placeholder {@code {{ expr }}}.
     *
     * @param modelName  optional model name; when present, expression results are
     *                   converted to the target field type
     * @param rowTemplate the template to resolve
     * @param variables   current execution variables
     * @return resolved field→value map
     */
    public static Map<String, Object> resolveRowTemplate(@Nullable String modelName,
                                                         Map<String, Object> rowTemplate,
                                                         Map<String, Object> variables) {
        Map<String, Object> result = new HashMap<>();
        rowTemplate.forEach((field, value) -> {
            if (value instanceof String str) {
                PlaceholderToken placeholder = PlaceholderUtils.parsePlaceholder(str);
                if (placeholder != null) {
                    switch (placeholder.getKind()) {
                        case VARIABLE -> result.put(field,
                                PlaceholderUtils.extractVariable(placeholder, variables));
                        case EXPRESSION -> result.put(field,
                                executeExpression(modelName, field, placeholder.getContent(), variables));
                        case RESERVED_FIELD -> result.put(field, value);
                    }
                } else {
                    result.put(field, value);
                }
            } else {
                result.put(field, value);
            }
        });
        return result;
    }

    /**
     * Convenience overload for resolving a model-independent data template.
     */
    public static Map<String, Object> resolveDataTemplate(Map<String, Object> dataTemplate,
                                                          Map<String, Object> variables) {
        return resolveRowTemplate(null, dataTemplate, variables);
    }

    /**
     * Resolve placeholder values inside a {@link Filters} tree, replacing variable
     * and expression placeholders with their computed values.
     *
     * @param modelName model name for type conversion
     * @param filters   filters to resolve (mutated in place)
     * @param variables current execution variables
     */
    public static void resolveFilterValue(String modelName, Filters filters, Map<String, Object> variables) {
        if (Filters.isEmpty(filters)) {
            return;
        }
        if (FilterType.LEAF.equals(filters.getType())
                && filters.getFilterUnit() != null
                && filters.getFilterUnit().getValue() instanceof String paramValue) {
            FilterUnit filterUnit = filters.getFilterUnit();
            PlaceholderToken placeholder = PlaceholderUtils.parsePlaceholder(paramValue);
            if (placeholder != null) {
                switch (placeholder.getKind()) {
                    case VARIABLE -> {
                        Object value = PlaceholderUtils.extractVariable(placeholder, variables);
                        filterUnit.setValue(value);
                        validateFilterUnitValue(filterUnit, paramValue);
                    }
                    case EXPRESSION -> {
                        MetaField lastField = ModelManager.getLastFieldOfCascaded(modelName, filterUnit.getField());
                        Object value = executeExpression(modelName, lastField.getFieldName(),
                                placeholder.getContent(), variables);
                        filterUnit.setValue(value);
                        validateFilterUnitValue(filterUnit, paramValue);
                    }
                    case RESERVED_FIELD -> {
                        // no-op
                    }
                }
            }
        } else if (FilterType.TREE.equals(filters.getType()) && filters.getChildren() != null) {
            filters.getChildren().forEach(child -> resolveFilterValue(modelName, child, variables));
        }
    }

    /**
     * Extract primary key ID(s) from a placeholder variable in the execution variables.
     *
     * @param pkVariable the raw pk variable string, e.g. {@code "{{ TriggerParams.id }}"}
     * @param variables  current execution variables
     * @return collection of IDs, or empty if the variable resolves to null
     */
    public static Collection<?> getIdsFromPkVariable(String pkVariable, Map<String, Object> variables) {
        PlaceholderToken placeholder = PlaceholderUtils.parsePlaceholder(pkVariable);
        if (placeholder == null || !PlaceholderKind.VARIABLE.equals(placeholder.getKind())) {
            return Collections.emptyList();
        }
        Object pks = PlaceholderUtils.extractVariable(placeholder, variables);
        if (pks == null) {
            return Collections.emptyList();
        } else if (pks instanceof Collection<?> col) {
            return col;
        } else {
            return Collections.singleton(pks);
        }
    }

    /**
     * Execute a computation expression, optionally converting the result to the
     * model field's target type.
     */
    private static Object executeExpression(@Nullable String modelName, String field,
                                            String expression, Map<String, Object> variables) {
        List<String> dependentVariables;
        try {
            dependentVariables = ComputeUtils.getVariables(expression);
        } catch (ValidationException e) {
            throw new IllegalArgumentException("The expression `{0}` is invalid.", expression);
        }
        List<String> missing = new ArrayList<>(dependentVariables);
        missing.removeAll(variables.keySet());
        Assert.isTrue(missing.isEmpty(),
                "Variables {0} in expression for field {1} do not exist in execution variables.",
                missing, field);
        if (modelName == null || modelName.isBlank()) {
            return ComputeUtils.execute(expression, variables);
        } else {
            MetaField metaField = ModelManager.getModelField(modelName, field);
            return ComputeUtils.execute(expression, variables, metaField.getScale(), metaField.getFieldType());
        }
    }

    private static void validateFilterUnitValue(FilterUnit filterUnit, String paramValue) {
        try {
            FilterUnit.validateFilterUnit(filterUnit);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Validation failed for parameter {0} of filter condition {1}: {2}",
                    paramValue, filterUnit.toString(), e.getMessage());
        }
    }
}


