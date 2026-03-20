package io.softa.starter.flow.utils;

import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.base.exception.ValidationException;
import io.softa.framework.base.placeholder.PlaceholderToken;
import io.softa.framework.base.placeholder.PlaceholderUtils;
import io.softa.framework.base.utils.Assert;
import io.softa.framework.orm.compute.ComputeUtils;
import io.softa.framework.orm.domain.FilterUnit;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.enums.FilterType;
import io.softa.framework.orm.meta.MetaField;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.flow.entity.FlowNode;
import io.softa.starter.flow.node.NodeContext;

/**
 * Flow utility class.
 * Providing methods for extracting variables, executing expressions, and resolving data templates.
 */
@Slf4j
public class FlowUtils {

    /**
     * Get the primary key list from the context based on the primary key variable.
     *
     * @param flowNode Flow node
     * @param pkToken Parsed placeholder token for the primary key variable
     * @param nodeContext Node context
     * @return ids Primary key list
     */
    public static Collection<?> getIdsFromPkVariable(FlowNode flowNode, PlaceholderToken pkToken, NodeContext nodeContext) {
        // Variable placeholders `{{ }}` are resolved from the flow environment.
        Object pks = PlaceholderUtils.extractVariable(pkToken, nodeContext.getEnv());
        if (pks == null) {
            return Collections.emptyList();
        } else if (pks instanceof Collection<?> pksCol) {
            return pksCol;
        } else {
            return Collections.singleton(pks);
        }
    }

    /**
     * Resolve a model-independent data Map based on the data template,
     * where the field value supports constants, variables, and calculation formulas.
     *
     * @param dataTemplate Data template
     * @param nodeContext Node context
     * @return New or updated data record
     */
    public static Map<String, Object> resolveDataTemplate(Map<String, Object> dataTemplate, NodeContext nodeContext) {
        return resolveRowTemplate(null, dataTemplate, nodeContext);
    }

    /**
     * Generate a new or updated data record based on the model row data template,
     * where the field value supports constants, variables, and calculation formulas.
     *
     * @param modelName Model name of the data to be operated, when it is empty,
     *                  the result of the calculation formula is not converted.
     * @param rowTemplate Model row data template
     * @param nodeContext Node context
     * @return New or updated model data
     */
    public static Map<String, Object> resolveRowTemplate(@Nullable String modelName, Map<String, Object> rowTemplate,
                                                         NodeContext nodeContext) {
        Map<String, Object> rowMap = new HashMap<>();
        rowTemplate.forEach((field, value) -> {
            if (value instanceof String str) {
                PlaceholderToken placeholder = PlaceholderUtils.parsePlaceholder(str);
                if (placeholder != null) {
                    switch (placeholder.getKind()) {
                        case VARIABLE -> {
                            // Resolve simple variable placeholders `{{ TriggerParams.id }}` from the environment.
                            Object fieldValue = PlaceholderUtils.extractVariable(placeholder, nodeContext.getEnv());
                            rowMap.put(field, fieldValue);
                        }
                        case EXPRESSION -> {
                            // Evaluate expression placeholders `{{ expr }}` and convert the result to the target field type.
                            Object result = executeExpression(modelName, field, placeholder.getContent(), nodeContext);
                            rowMap.put(field, result);
                        }
                        case RESERVED_FIELD -> rowMap.put(field, value);
                    }
                } else {
                    // When the value is a constant, directly assign the value to the field.
                    rowMap.put(field, value);
                }
            } else {
                // When the value is a constant, directly assign the value to the field.
                rowMap.put(field, value);
            }
        });
        return rowMap;
    }

    /**
     * Execute the calculation expression.
     * The variables in the calculation expression must all be in the node context variables.
     *
     * @param modelName Model name of the data to be operated, when it is empty,
     *                  the result of the calculation formula is not converted.
     * @param field Field name
     * @param expression Calculation expression content (already extracted from the placeholder)
     * @param nodeContext Node context
     * @return Calculation expression result
     */
    public static Object executeExpression(@Nullable String modelName, String field, String expression,
                                           NodeContext nodeContext) {
        // Determine if the variables in the calculation formula exist in the node context.
        List<String> dependentVariables;
        try {
            dependentVariables = ComputeUtils.getVariables(expression);
        } catch (ValidationException e) {
            throw new IllegalArgumentException("The expression `{0}` is invalid.", expression);
        }
        dependentVariables.removeAll(nodeContext.keySet());
        Assert.isTrue(dependentVariables.isEmpty(), """
                        The variables {1} appear in the calculated expression for the data template parameter field {0},
                        do not exist in the node context.""", dependentVariables);
        // When modelName is empty, the result of the calculation formula is not converted.
        if (StringUtils.isBlank(modelName)) {
            return ComputeUtils.execute(expression, nodeContext.getEnv());
        } else {
            MetaField metaField = ModelManager.getModelField(modelName, field);
            return ComputeUtils.execute(expression, nodeContext.getEnv(), metaField.getScale(), metaField.getFieldType());
        }
    }

    /**
     * Convert placeholders in Filters to actual values.
     *
     * @param modelName Model name of the current node parameter
     * @param filters Filters
     * @param nodeContext Node context
     */
    public static void resolveFilterValue(String modelName, Filters filters, NodeContext nodeContext) {
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
                        // Resolve simple variable placeholders `{{ TriggerParams.id }}` from the environment.
                        Object value = PlaceholderUtils.extractVariable(placeholder, nodeContext.getEnv());
                        filterUnit.setValue(value);
                        validateFilterUnitValue(filterUnit, paramValue);
                    }
                    case EXPRESSION -> {
                        // Execute the calculation expression `{{ expr }}`, where the field in FilterUnit allows cascaded definition,
                        // and the type of the last field is used as the actual assignment type.
                        MetaField lastField = ModelManager.getLastFieldOfCascaded(modelName, filterUnit.getField());
                        Object value = executeExpression(modelName, lastField.getFieldName(), placeholder.getContent(), nodeContext);
                        filterUnit.setValue(value);
                        validateFilterUnitValue(filterUnit, paramValue);
                    }
                    case RESERVED_FIELD -> {
                    }
                }
            }
        } else if (FilterType.TREE.equals(filters.getType()) && filters.getChildren() != null) {
            List<Filters> children = filters.getChildren();
            children.forEach(child -> resolveFilterValue(modelName, child, nodeContext));
        }
    }

    /**
     * Validate the legality of the value in filterUnit.
     *
     * @param filterUnit FilterUnit object
     * @param paramValue Filter parameter value
     */
    private static void validateFilterUnitValue(FilterUnit filterUnit, String paramValue) {
        try {
            FilterUnit.validateFilterUnit(filterUnit);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Validation failed for the parameter {0} of filter condition {1}: {2}",
                    paramValue, filterUnit.toString(), e.getMessage());
        }
    }

}
