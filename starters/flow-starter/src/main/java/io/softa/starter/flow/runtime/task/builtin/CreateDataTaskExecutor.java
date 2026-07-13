package io.softa.starter.flow.runtime.task.builtin;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.service.ModelService;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.nodeconfig.CreateDataConfig;
import io.softa.starter.flow.runtime.task.TaskExecutionRequest;

/**
 * Built-in DataTask executor for creating a single row via {@link ModelService}.
 * <p>
 * Config example (inside {@code config.task}):
 * <pre>{@code
 * {
 *   "executor": "CreateData",
 *   "input": {
 *     "modelName": "SysModel",
 *     "rowTemplate": {
 *       "name": "{{ modelName }}",
 *       "fieldType": "DateTime"
 *     }
 *   }
 * }
 * }</pre>
 */
@Component
public class CreateDataTaskExecutor extends AbstractTaskExecutor {

    @Autowired
    private ModelService<? extends Serializable> modelService;

    @Override
    public FlowNodeType getSupportedNodeType() {
        return FlowNodeType.CREATE_RECORD;
    }

    @Override
    public String getExecutor() {
        return "CreateData";
    }

    @Override
    public String getName() {
        return "Create Data";
    }

    @Override
    public String getDescription() {
        return "Create a single row based on a model name and a row template with placeholder support.";
    }

    @Override
    public Map<String, Object> getConfigSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("modelName", Map.of("type", "model", "label", "Model", "required", true));
        // sourceField: the widget loads its options (model fields) from this sibling field's value
        schema.put("rowTemplate", Map.of("type", "fieldMapping", "label", "Row Template", "required", true,
                "sourceField", "modelName"));
        return schema;
    }

    @Override
    public String getIcon() {
        return "plus-square";
    }

    @Override
    public int getSortOrder() {
        return 50;
    }

    @Override
    public Map<String, Object> execute(TaskExecutionRequest request, Map<String, Object> variables) {
        CreateDataConfig cfg = requireConfig(request, CreateDataConfig.class);
        String modelName = requireResolvedString(cfg.getModelName(), "modelName", variables);
        Map<String, Object> rowTemplate = cfg.getRowTemplate();
        if (rowTemplate == null || rowTemplate.isEmpty()) {
            throw new IllegalArgumentException("CreateData executor requires input.rowTemplate");
        }

        // Raw row template reaches VariableResolver, so its field-type-aware coercion actually runs.
        Map<String, Object> rowMap = VariableResolver.resolveRowTemplate(modelName, rowTemplate, variables);
        Serializable id = modelService.createOne(modelName, rowMap);
        return Map.of("id", id);
    }
}

