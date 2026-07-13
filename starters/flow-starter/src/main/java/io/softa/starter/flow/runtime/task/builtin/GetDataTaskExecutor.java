package io.softa.starter.flow.runtime.task.builtin;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.flow.enums.ActionGetDataType;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.nodeconfig.GetDataConfig;
import io.softa.starter.flow.runtime.task.TaskExecutionRequest;

import static io.softa.framework.base.constant.BaseConstant.MAX_BATCH_SIZE;

/**
 * Built-in DataTask executor for querying data via {@link ModelService}.
 * <p>
 * Supports multiple retrieval modes: single row, multiple rows,
 * single field value, field value list, existence check, and count.
 * </p>
 * <p>
 * Config example (inside {@code config.task}):
 * <pre>{@code
 * {
 *   "executor": "GetData",
 *   "input": {
 *     "modelName": "Employee",
 *     "getDataType": "SingleRow",
 *     "fields": ["name", "deptId"],
 *     "filters": ["deptId", "=", "{{ deptId }}"],
 *     "limitSize": 100
 *   },
 *   "outputVariable": "employeeResult"
 * }
 * }</pre>
 */
@Component
public class GetDataTaskExecutor extends AbstractTaskExecutor {

    // Routing sets derived from ActionGetDataType (the single source of truth) — never hardcoded.
    private static final Set<String> COUNT_TYPES =
            Set.of(ActionGetDataType.EXIST.getCode(), ActionGetDataType.COUNT.getCode());
    private static final Set<String> SINGLE_ROW_TYPES =
            Set.of(ActionGetDataType.SINGLE_ROW.getCode(), ActionGetDataType.ONE_FIELD_VALUE.getCode());
    private static final Set<String> MULTI_ROW_TYPES =
            Set.of(ActionGetDataType.MULTI_ROWS.getCode(), ActionGetDataType.ONE_FIELD_VALUES.getCode());

    @Autowired
    private ModelService<? extends Serializable> modelService;

    @Override
    public FlowNodeType getSupportedNodeType() {
        return FlowNodeType.GET_RECORD;
    }

    @Override
    public String getExecutor() {
        return "GetData";
    }

    @Override
    public String getName() {
        return "Get Data";
    }

    @Override
    public String getDescription() {
        return "Query data by model name, filters, fields, and orders. "
                + "Supports single row, multi rows, field value, existence, and count.";
    }

    @Override
    public Map<String, Object> getConfigSchema() {
        return Map.ofEntries(
                Map.entry("modelName", Map.of("type", "model", "label", "Model", "required", true)),
                Map.entry("getDataType", Map.of("type", "enum", "label", "Query Type", "required", true,
                        "options", Arrays.stream(ActionGetDataType.values()).map(ActionGetDataType::getCode).toList())),
                Map.entry("fields", Map.of("type", "stringList", "label", "Fields")),
                Map.entry("filters", Map.of("type", "filters", "label", "Filters")),
                Map.entry("orders", Map.of("type", "orders", "label", "Orders")),
                Map.entry("limitSize", Map.of("type", "number", "label", "Limit Size"))
        );
    }

    @Override
    public String getIcon() {
        return "search";
    }

    @Override
    public int getSortOrder() {
        return 51;
    }

    @Override
    public Map<String, Object> execute(TaskExecutionRequest request, Map<String, Object> variables) {
        GetDataConfig cfg = requireConfig(request, GetDataConfig.class);
        String modelName = requireResolvedString(cfg.getModelName(), "modelName", variables);
        String getDataType = requireResolvedString(cfg.getGetDataType(), "getDataType", variables);
        List<String> fields = cfg.getFields();
        Integer limitSize = cfg.getLimitSize();

        // Raw filters/orders reach the type-aware VariableResolver (not pre-resolved by the handler).
        Filters filters = resolveFilters(modelName, cfg.getFilters(), variables);
        Orders orders = resolveOrders(cfg.getOrders());

        Object result;
        if (COUNT_TYPES.contains(getDataType)) {
            result = executeCount(modelName, getDataType, filters);
        } else if (SINGLE_ROW_TYPES.contains(getDataType)) {
            result = executeSingleRow(modelName, getDataType, fields, filters, orders);
        } else if (MULTI_ROW_TYPES.contains(getDataType)) {
            result = executeMultiRows(modelName, getDataType, fields, filters, orders, limitSize);
        } else {
            throw new IllegalArgumentException("Unsupported getDataType: " + getDataType);
        }

        return Collections.singletonMap("result", result);
    }

    private Object executeCount(String modelName, String getDataType, Filters filters) {
        long count = modelService.count(modelName, filters);
        if (ActionGetDataType.COUNT.getCode().equals(getDataType)) {
            return count;
        } else {
            // Exist
            return count > 0;
        }
    }

    private Object executeSingleRow(String modelName, String getDataType,
                                    List<String> fields, Filters filters, Orders orders) {
        FlexQuery flexQuery = new FlexQuery(fields, filters, orders);
        flexQuery.setLimitSize(1);
        Optional<Map<String, Object>> row = modelService.searchOne(modelName, flexQuery);
        if (ActionGetDataType.ONE_FIELD_VALUE.getCode().equals(getDataType) && row.isPresent() && fields != null && !fields.isEmpty()) {
            return row.get().get(fields.getFirst());
        }
        return row.orElse(null);
    }

    private Object executeMultiRows(String modelName, String getDataType,
                                    List<String> fields, Filters filters, Orders orders,
                                    Integer limitSize) {
        FlexQuery flexQuery = new FlexQuery(fields, filters, orders);
        flexQuery.setLimitSize(limitSize != null ? limitSize : MAX_BATCH_SIZE);
        List<Map<String, Object>> rows = modelService.searchList(modelName, flexQuery);
        if (!rows.isEmpty() && ActionGetDataType.ONE_FIELD_VALUES.getCode().equals(getDataType) && fields != null && !fields.isEmpty()) {
            return rows.stream()
                    .map(row -> row.get(fields.getFirst()))
                    .collect(Collectors.toSet());
        }
        return rows;
    }
}


