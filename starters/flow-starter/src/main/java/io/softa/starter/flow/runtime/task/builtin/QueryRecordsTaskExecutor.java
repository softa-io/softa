package io.softa.starter.flow.runtime.task.builtin;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.domain.Page;
import io.softa.framework.orm.service.ModelService;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.nodeconfig.QueryRecordsConfig;
import io.softa.starter.flow.runtime.task.TaskExecutionRequest;
import io.softa.starter.flow.runtime.task.TaskExecutor;

/**
 * Built-in executor for {@link FlowNodeType#QUERY_RECORDS} — paginated model query
 * that returns rows plus pagination metadata.
 * <p>
 * Config example (inside {@code config.task}):
 * <pre>{@code
 * {
 *   "executor": "QueryRecords",
 *   "input": {
 *     "modelName": "Employee",
 *     "fields": ["name", "deptId"],
 *     "filters": ["deptId", "=", "{{ deptId }}"],
 *     "orders": "name ASC",
 *     "page": 1,
 *     "pageSize": 50
 *   },
 *   "outputVariable": "employeePage"
 * }
 * }</pre>
 * <p>
 * Result shape:
 * <pre>{@code
 * {
 *   "rows": [ ... ],
 *   "totalCount": 123,
 *   "totalPages": 3,
 *   "pageNumber": 1,
 *   "pageSize": 50
 * }
 * }</pre>
 * <p>
 * Use {@code GetData} for bounded (non-paginated) lookups, single-row fetches,
 * existence checks, and counts.
 * <p>
 * Disable via {@code flow.task.builtin.query-records.enabled=false} to register a
 * custom {@link TaskExecutor} for {@code QUERY_RECORDS}.
 */
@Component
@ConditionalOnProperty(
        name = "flow.task.builtin.query-records.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class QueryRecordsTaskExecutor extends AbstractTaskExecutor {

    @Autowired
    private ModelService<? extends Serializable> modelService;

    @Override
    public FlowNodeType getSupportedNodeType() {
        return FlowNodeType.QUERY_RECORDS;
    }

    @Override
    public String getExecutor() {
        return "QueryRecords";
    }

    @Override
    public String getName() {
        return "Query Records";
    }

    @Override
    public String getDescription() {
        return "Paginated model query returning rows + totalCount/totalPages/pageNumber/pageSize.";
    }

    @Override
    public Map<String, Object> getConfigSchema() {
        return Map.ofEntries(
                Map.entry("modelName", Map.of("type", "model", "label", "Model", "required", true)),
                Map.entry("fields", Map.of("type", "stringList", "label", "Fields")),
                Map.entry("filters", Map.of("type", "filters", "label", "Filters")),
                Map.entry("orders", Map.of("type", "orders", "label", "Orders")),
                Map.entry("page", Map.of("type", "number", "label", "Page Number", "default", 1)),
                Map.entry("pageSize", Map.of("type", "number", "label", "Page Size", "default", 50))
        );
    }

    @Override
    public Map<String, Object> getDefaultConfig() {
        return Map.of("page", 1, "pageSize", 50);
    }

    @Override
    public String getIcon() {
        return "list";
    }

    @Override
    public int getSortOrder() {
        return 52;
    }

    @Override
    public Map<String, Object> execute(TaskExecutionRequest request, Map<String, Object> variables) {
        QueryRecordsConfig cfg = requireConfig(request, QueryRecordsConfig.class);
        String modelName = requireResolvedString(cfg.getModelName(), "modelName", variables);
        List<String> fields = cfg.getFields();
        Integer pageNumber = cfg.getPage();
        Integer pageSize = cfg.getPageSize();

        Filters filters = resolveFilters(modelName, cfg.getFilters(), variables);
        Orders orders = resolveOrders(cfg.getOrders());

        FlexQuery flexQuery = new FlexQuery(fields, filters, orders);
        Page<Map<String, Object>> page = Page.of(
                pageNumber != null ? pageNumber : 1,
                pageSize != null ? pageSize : 50);
        Page<Map<String, Object>> result = modelService.searchPage(modelName, flexQuery, page);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", result.getRows());
        out.put("totalCount", result.getTotalCount());
        out.put("totalPages", result.getTotalPages());
        out.put("pageNumber", result.getPageNumber());
        out.put("pageSize", result.getPageSize());
        return Collections.unmodifiableMap(out);
    }
}
