package io.softa.starter.flow.runtime.task.builtin;

import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import io.softa.framework.base.placeholder.PlaceholderKind;
import io.softa.framework.base.placeholder.PlaceholderToken;
import io.softa.framework.base.placeholder.PlaceholderUtils;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.nodeconfig.ExtractTransformConfig;
import io.softa.starter.flow.runtime.task.TaskExecutionRequest;

/**
 * Built-in ServiceTask executor for extracting a field value set from a collection variable.
 * <p>
 * Given a collection variable (e.g. a list of row maps), extracts the specified
 * {@code itemKey} field from each element and returns the deduplicated set.
 * </p>
 * <p>
 * Config example (inside {@code config.task}):
 * <pre>{@code
 * {
 *   "executor": "ExtractTransform",
 *   "input": {
 *     "collectionVariable": "{{ employeeList }}",
 *     "itemKey": "deptId"
 *   },
 *   "outputVariable": "deptIds"
 * }
 * }</pre>
 */
@Component
public class ExtractTransformTaskExecutor extends AbstractTaskExecutor {

    @Override
    public FlowNodeType getSupportedNodeType() {
        return FlowNodeType.TRANSFORM;
    }

    @Override
    public String getExecutor() {
        return "ExtractTransform";
    }

    @Override
    public String getName() {
        return "Extract Transform";
    }

    @Override
    public String getDescription() {
        return "Extract a field value set from a collection variable.";
    }

    @Override
    public Map<String, Object> getConfigSchema() {
        return Map.of(
                "collectionVariable", Map.of("type", "variableRef", "label", "Collection Variable", "required", true),
                "itemKey", Map.of("type", "string", "label", "Item Key", "required", true)
        );
    }

    @Override
    public String getIcon() {
        return "shuffle";
    }

    @Override
    public int getSortOrder() {
        return 71;
    }

    @Override
    public Map<String, Object> execute(TaskExecutionRequest request, Map<String, Object> variables) {
        ExtractTransformConfig cfg = requireConfig(request, ExtractTransformConfig.class);
        String collectionVariable = requireText(cfg.getCollectionVariable(), "collectionVariable");
        String itemKey = requireText(cfg.getItemKey(), "itemKey");

        // Resolve the collection variable from execution variables
        PlaceholderToken placeholder = PlaceholderUtils.parsePlaceholder(collectionVariable);
        Object variableValue;
        if (placeholder != null && PlaceholderKind.VARIABLE.equals(placeholder.getKind())) {
            variableValue = PlaceholderUtils.extractVariable(placeholder, variables);
        } else {
            // Treat as a direct variable key
            variableValue = variables.get(collectionVariable);
        }

        if (variableValue == null || (variableValue instanceof Collection<?> col && CollectionUtils.isEmpty(col))) {
            return Map.of("result", Collections.emptySet());
        }
        if (variableValue instanceof Collection<?> col) {
            Set<Object> result = new LinkedHashSet<>();
            col.forEach(row -> {
                if (row instanceof Map<?, ?> rowMap) {
                    Object val = rowMap.get(itemKey);
                    if (val != null) {
                        result.add(val);
                    }
                }
            });
            return Map.of("result", result);
        }
        throw new IllegalArgumentException(
                "ExtractTransform: the collection variable '" + collectionVariable + "' is not a collection: " + variableValue);
    }
}

