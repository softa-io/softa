package io.softa.starter.flow.runtime.task.builtin;

import java.io.Serializable;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.nodeconfig.DeleteDataConfig;
import io.softa.starter.flow.runtime.task.TaskExecutionRequest;

/**
 * Built-in DataTask executor for deleting rows via {@link ModelService}.
 * <p>
 * Rows are identified by a primary key variable and/or filter conditions.
 * Either {@code pkVariable} or {@code filters} must be provided;
 * when both are present they are merged with AND.
 * </p>
 * <p>
 * Config example (inside {@code config.task}):
 * <pre>{@code
 * {
 *   "executor": "DeleteData",
 *   "input": {
 *     "modelName": "DemoModel",
 *     "pkVariable": "{{ ids }}",
 *     "filters": ["active", "=", true]
 *   }
 * }
 * }</pre>
 */
@Component
public class DeleteDataTaskExecutor extends AbstractTaskExecutor {

    @Autowired
    private ModelService<? extends Serializable> modelService;

    @Override
    public FlowNodeType getSupportedNodeType() {
        return FlowNodeType.DELETE_RECORD;
    }

    @Override
    public String getExecutor() {
        return "DeleteData";
    }

    @Override
    public String getName() {
        return "Delete Data";
    }

    @Override
    public String getDescription() {
        return "Delete rows by primary key or filters. Either pkVariable or filters must be provided.";
    }

    @Override
    public Map<String, Object> getConfigSchema() {
        return Map.of(
                "modelName", Map.of("type", "model", "label", "Model", "required", true),
                "pkVariable", Map.of("type", "variableRef", "label", "Primary Key Variable"),
                "filters", Map.of("type", "filters", "label", "Filters")
        );
    }

    @Override
    public String getIcon() {
        return "trash-2";
    }

    @Override
    public int getSortOrder() {
        return 53;
    }

    @Override
    public Map<String, Object> execute(TaskExecutionRequest request, Map<String, Object> variables) {
        DeleteDataConfig cfg = requireConfig(request, DeleteDataConfig.class);
        String modelName = requireResolvedString(cfg.getModelName(), "modelName", variables);
        // pkVariable stays a raw "{{ var }}" placeholder — VariableResolver.getIdsFromPkVariable extracts it.
        String pkVariable = cfg.getPkVariable();

        Filters deleteFilters = buildFilters(modelName, pkVariable, cfg.getFilters(), variables);
        if (Filters.isEmpty(deleteFilters)) {
            return Map.of("deleted", false);
        }

        boolean deleted = modelService.deleteByFilters(modelName, deleteFilters);
        return Map.of("deleted", deleted);
    }
}

