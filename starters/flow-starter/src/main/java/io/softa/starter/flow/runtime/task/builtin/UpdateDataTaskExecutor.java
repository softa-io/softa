package io.softa.starter.flow.runtime.task.builtin;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.nodeconfig.UpdateDataConfig;
import io.softa.starter.flow.runtime.task.TaskExecutionRequest;

/**
 * Built-in DataTask executor for updating rows via {@link ModelService}.
 * <p>
 * Rows are identified by a primary key variable and/or filter conditions.
 * Field values in the row template support placeholder expressions.
 * </p>
 * <p>
 * Config example (inside {@code config.task}):
 * <pre>{@code
 * {
 *   "executor": "UpdateData",
 *   "input": {
 *     "modelName": "Employee",
 *     "pkVariable": "{{ employeeId }}",
 *     "filters": ["active", "=", true],
 *     "rowTemplate": {
 *       "name": "{{ newName }}",
 *       "deptId": "{{ deptId }}"
 *     }
 *   }
 * }
 * }</pre>
 */
@Component
public class UpdateDataTaskExecutor extends AbstractTaskExecutor {

    @Autowired
    private ModelService<? extends Serializable> modelService;

    @Override
    public FlowNodeType getSupportedNodeType() {
        return FlowNodeType.UPDATE_RECORD;
    }

    @Override
    public String getExecutor() {
        return "UpdateData";
    }

    @Override
    public String getName() {
        return "Update Data";
    }

    @Override
    public String getDescription() {
        return "Update rows by primary key or filters with a row template supporting placeholder expressions.";
    }

    @Override
    public Map<String, Object> getConfigSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("modelName", Map.of("type", "model", "label", "Model", "required", true));
        // sourceField: the widget loads its options (model fields) from this sibling field's value
        schema.put("rowTemplate", Map.of("type", "fieldMapping", "label", "Row Template", "required", true,
                "sourceField", "modelName"));
        schema.put("pkVariable", Map.of("type", "variableRef", "label", "Primary Key Variable"));
        schema.put("filters", Map.of("type", "filters", "label", "Filters"));
        return schema;
    }

    @Override
    public String getIcon() {
        return "edit";
    }

    @Override
    public int getSortOrder() {
        return 52;
    }

    @Override
    public Map<String, Object> execute(TaskExecutionRequest request, Map<String, Object> variables) {
        UpdateDataConfig cfg = requireConfig(request, UpdateDataConfig.class);
        String modelName = requireResolvedString(cfg.getModelName(), "modelName", variables);
        // pkVariable stays a raw "{{ var }}" placeholder — VariableResolver.getIdsFromPkVariable extracts it.
        String pkVariable = cfg.getPkVariable();
        Map<String, Object> rowTemplate = cfg.getRowTemplate();
        if (rowTemplate == null || rowTemplate.isEmpty()) {
            throw new IllegalArgumentException("UpdateData executor requires input.rowTemplate");
        }

        Filters updateFilters = buildFilters(modelName, pkVariable, cfg.getFilters(), variables);
        if (Filters.isEmpty(updateFilters)) {
            return Map.of("affected", 0);
        }

        Map<String, Object> rowMap = VariableResolver.resolveRowTemplate(modelName, rowTemplate, variables);
        int affected = modelService.updateByFilter(modelName, updateFilters, rowMap);
        return Map.of("affected", affected);
    }
}

