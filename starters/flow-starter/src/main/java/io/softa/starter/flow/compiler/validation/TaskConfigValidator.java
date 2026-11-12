package io.softa.starter.flow.compiler.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.springframework.util.StringUtils;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.Orders;
import io.softa.framework.orm.meta.FieldPathUse;
import io.softa.framework.orm.meta.FieldPaths;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.utils.BeanTool;
import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.compiler.FlowGraphContext;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.FlowGraphNode;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.bundle.NodeConfigParser;
import io.softa.starter.flow.runtime.nodeconfig.AsyncTaskConfig;
import io.softa.starter.flow.runtime.nodeconfig.CreateDataConfig;
import io.softa.starter.flow.runtime.nodeconfig.DeleteDataConfig;
import io.softa.starter.flow.runtime.nodeconfig.ExtractTransformConfig;
import io.softa.starter.flow.runtime.nodeconfig.GenerateFileConfig;
import io.softa.starter.flow.runtime.nodeconfig.GetDataConfig;
import io.softa.starter.flow.runtime.nodeconfig.QueryAiConfig;
import io.softa.starter.flow.runtime.nodeconfig.QueryRecordsConfig;
import io.softa.starter.flow.runtime.nodeconfig.SendEmailConfig;
import io.softa.starter.flow.runtime.nodeconfig.SendInboxNotificationConfig;
import io.softa.starter.flow.runtime.nodeconfig.TaskConfigTypes;
import io.softa.starter.flow.runtime.nodeconfig.TaskNodeConfig;
import io.softa.starter.flow.runtime.nodeconfig.UpdateDataConfig;
import io.softa.starter.flow.runtime.nodeconfig.ValidateDataConfig;
import io.softa.starter.flow.runtime.nodeconfig.WebHookConfig;

/**
 * Validates task node config at compile (publish) time.
 * <p>
 * Two layers: (1) shape validation for the <em>typed</em> node types (those in {@link TaskConfigTypes}) —
 * their raw input is parsed into the executor's config DTO and its required fields are checked, a node with no
 * input at all included — and (2) for the record nodes, the model they name and the field paths their query names,
 * checked against the model's metadata the way the query will use them ({@link FieldPaths}). So a
 * misconfigured node fails at publish instead of mid-execution. The executor is otherwise fully
 * determined by {@link FlowNodeType}.
 */
public class TaskConfigValidator implements FlowValidator {

    private static final String INVALID_TASK_CONFIG = "INVALID_TASK_CONFIG";
    private static final String MISSING_REQUIRED_INPUT = "MISSING_REQUIRED_INPUT";
    private static final String UNKNOWN_MODEL = "UNKNOWN_MODEL";
    private static final String INVALID_FIELD_PATH = "INVALID_FIELD_PATH";

    @Override
    public List<CompileDiagnostic> validate(DesignFlowDefinition definition, FlowGraphContext context) {
        List<CompileDiagnostic> d = new ArrayList<>();
        for (FlowGraphNode node : context.nodeMap().values()) {
            validateTypedConfig(node, d);
        }
        return d;
    }

    /**
     * Parse a typed node type's raw input into its DTO and check required fields / arg-type arity. A node with no
     * config, or with none beyond the node's own keys, is checked as an empty input: each required field it lacks is
     * reported where the designer shows it, as for a node that filled in some of them.
     */
    private void validateTypedConfig(FlowGraphNode node, List<CompileDiagnostic> d) {
        Class<?> configType = TaskConfigTypes.forNodeType(node.getType());
        if (configType == null) {
            return;
        }
        Map<String, Object> input = extractInput(node);
        if (input == null) {
            input = Map.of();
        }
        String at = inputPath(node);
        Object dto;
        try {
            dto = BeanTool.objectToObject(input, configType);
        } catch (RuntimeException e) {
            d.add(CompileDiagnostic.nodeLevel(node.getId(), node.getType().getType(), INVALID_TASK_CONFIG,
                    "Task node '" + node.displayName() + "' has malformed "
                            + at.substring(0, at.length() - 1) + ": " + e.getMessage()));
            return;
        }
        switch (dto) {
            case CreateDataConfig c -> {
                requireText(node, at + "modelName", c.getModelName(), d);
                requireMap(node, at + "rowTemplate", c.getRowTemplate(), d);
                checkQuery(node, at, c.getModelName(), null, null, null, d);
            }
            case UpdateDataConfig u -> {
                requireText(node, at + "modelName", u.getModelName(), d);
                requireMap(node, at + "rowTemplate", u.getRowTemplate(), d);
                checkQuery(node, at, u.getModelName(), null, u.getFilters(), null, d);
            }
            case GetDataConfig g -> {
                requireText(node, at + "modelName", g.getModelName(), d);
                requireText(node, at + "getDataType", g.getGetDataType(), d);
                checkQuery(node, at, g.getModelName(), g.getFields(), g.getFilters(), g.getOrders(), d);
            }
            case DeleteDataConfig del -> {
                requireText(node, at + "modelName", del.getModelName(), d);
                checkQuery(node, at, del.getModelName(), null, del.getFilters(), null, d);
            }
            case QueryRecordsConfig q -> {
                requireText(node, at + "modelName", q.getModelName(), d);
                checkQuery(node, at, q.getModelName(), q.getFields(), q.getFilters(), q.getOrders(), d);
            }
            case SendEmailConfig mail -> requirePresent(node, at + "to", mail.getTo(), d);
            case SendInboxNotificationConfig inbox -> {
                requirePresent(node, at + "recipientIds", inbox.getRecipientIds(), d);
                requireText(node, at + "title", inbox.getTitle(), d);
                requireText(node, at + "content", inbox.getContent(), d);
            }
            case GenerateFileConfig file -> requirePresent(node, at + "templateId", file.getTemplateId(), d);
            case ValidateDataConfig v -> {
                requireText(node, at + "expression", v.getExpression(), d);
                requireText(node, at + "exceptionMsg", v.getExceptionMsg(), d);
            }
            case ExtractTransformConfig t -> {
                requireText(node, at + "collectionVariable", t.getCollectionVariable(), d);
                requireText(node, at + "itemKey", t.getItemKey(), d);
            }
            case QueryAiConfig ai -> requireText(node, at + "queryContent", ai.getQueryContent(), d);
            case WebHookConfig hook -> requireText(node, at + "url", hook.getUrl(), d);
            case AsyncTaskConfig async ->
                    requireText(node, at + "asyncTaskHandlerCode", async.getAsyncTaskHandlerCode(), d);
            default -> { /* no DTO-specific rules */ }
        }
    }

    /**
     * Where a task node's input fields sit, for the diagnostics: under {@code input} when the design nests them, else
     * at the top level beside the node's own keys, as the designer writes them — in either case inside {@code task}
     * when the config wraps itself in one.
     */
    private static String inputPath(FlowGraphNode node) {
        Map<?, ?> config = node.getConfig();
        String base = "config.";
        if (config != null && config.get("task") instanceof Map<?, ?> task) {
            config = task;
            base = "config.task.";
        }
        return config != null && config.get("input") instanceof Map<?, ?> ? base + "input." : base;
    }

    /**
     * A record node's model must be one of this application's, and what its query names must be paths that model's
     * query can use: the filters as a WHERE takes them, the orders as an ORDER BY does, the fields as a read does —
     * {@link FieldPaths}, the rules the query builders apply. The filters and orders are coerced as the executor
     * coerces them ({@code AbstractTaskExecutor}), so one that cannot be read fails here as it would on the run;
     * placeholders sit in values, which the run resolves, never in the paths checked here.
     */
    private void checkQuery(FlowGraphNode node, String at, @Nullable String modelName, @Nullable List<String> fields,
                            @Nullable Object filters, @Nullable Object orders, List<CompileDiagnostic> d) {
        if (!StringUtils.hasText(modelName)) {
            // Reported as a missing required input.
            return;
        }
        if (!ModelManager.existModel(modelName)) {
            d.add(CompileDiagnostic.fieldLevel(node.getId(), node.getType().getType(), at + "modelName",
                    UNKNOWN_MODEL, "Task node '" + node.displayName() + "' names model '" + modelName
                            + "', which is not a model of this application"));
            return;
        }
        if (fields != null) {
            for (String field : fields) {
                String problem = FieldPaths.problem(modelName, field, FieldPathUse.READ);
                if (problem != null) {
                    pathProblem(node, at + "fields", FieldPaths.named(field, problem), d);
                }
            }
        }
        Filters filter = coerce(node, at + "filters", filters, Filters.class, d);
        FieldPaths.filterProblems(modelName, filter).forEach(problem -> pathProblem(node, at + "filters", problem, d));
        Orders order = coerce(node, at + "orders", orders, Orders.class, d);
        FieldPaths.orderProblems(modelName, order).forEach(problem -> pathProblem(node, at + "orders", problem, d));
    }

    private static <T> @Nullable T coerce(FlowGraphNode node, String field, @Nullable Object raw, Class<T> type,
                                          List<CompileDiagnostic> d) {
        if (raw == null || type.isInstance(raw)) {
            return type.cast(raw);
        }
        try {
            return BeanTool.objectToObject(raw, type);
        } catch (RuntimeException e) {
            d.add(CompileDiagnostic.fieldLevel(node.getId(), node.getType().getType(), field, INVALID_TASK_CONFIG,
                    "Task node '" + node.displayName() + "' has malformed " + field + ": " + e.getMessage()));
            return null;
        }
    }

    private static void pathProblem(FlowGraphNode node, String field, String problem, List<CompileDiagnostic> d) {
        d.add(CompileDiagnostic.fieldLevel(node.getId(), node.getType().getType(), field, INVALID_FIELD_PATH,
                "Task node '" + node.displayName() + "' has a path its query cannot use in " + field + " — " + problem));
    }

    private Map<String, Object> extractInput(FlowGraphNode node) {
        Object parsed = NodeConfigParser.parse(node.getType(), node.getConfig());
        return parsed instanceof TaskNodeConfig tnc ? tnc.getInput() : null;
    }

    private void requireText(FlowGraphNode node, String field, String value, List<CompileDiagnostic> d) {
        if (!StringUtils.hasText(value)) {
            d.add(CompileDiagnostic.fieldLevel(node.getId(), node.getType().getType(), field,
                    MISSING_REQUIRED_INPUT, "Task node '" + node.displayName() + "' requires " + field));
        }
    }

    private void requireMap(FlowGraphNode node, String field, Map<?, ?> value, List<CompileDiagnostic> d) {
        if (value == null || value.isEmpty()) {
            d.add(CompileDiagnostic.fieldLevel(node.getId(), node.getType().getType(), field,
                    MISSING_REQUIRED_INPUT, "Task node '" + node.displayName() + "' requires " + field));
        }
    }

    /**
     * Presence check for polymorphic fields (a literal list, or a {@code {{ expr }}} string
     * resolving to one at runtime): null, a blank string, and an empty collection all fail.
     */
    private void requirePresent(FlowGraphNode node, String field, Object value, List<CompileDiagnostic> d) {
        boolean present = switch (value) {
            case null -> false;
            case String s -> StringUtils.hasText(s);
            case java.util.Collection<?> c -> !c.isEmpty();
            default -> true;
        };
        if (!present) {
            d.add(CompileDiagnostic.fieldLevel(node.getId(), node.getType().getType(), field,
                    MISSING_REQUIRED_INPUT, "Task node '" + node.displayName() + "' requires " + field));
        }
    }
}
