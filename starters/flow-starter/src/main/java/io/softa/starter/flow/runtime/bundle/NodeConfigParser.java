package io.softa.starter.flow.runtime.bundle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import io.softa.framework.orm.utils.BeanTool;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.nodeconfig.*;

/**
 * Parses a node's raw config map into its strongly-typed {@code *NodeConfig}.
 * <p>
 * Called from two places that must stay in sync: the compiler
 * ({@code NodeTransformer.compileNodes}) when a design graph is compiled, and
 * {@link FlowBundleMapper#toDefinition} when a persisted bundle is deserialized —
 * {@code CompiledFlowNode#getParsedConfig()} is {@code @JsonIgnore} and must be
 * rebuilt on every load or node handlers pattern-matching on it would fail.
 */
public final class NodeConfigParser {

    /** A task node's own keys. Whatever else a config without {@code input} holds is its executor's input. */
    private static final Set<String> TASK_NODE_KEYS = Set.of("task", "input", "outputVariable", "outputMapping", "options");

    private NodeConfigParser() {}

    public static Object parse(FlowNodeType type, Map<String, Object> config) {
        if (type == null || config == null || config.isEmpty()) {
            return null;
        }
        return switch (type) {
            case APPROVAL      -> BeanTool.objectToObject(config, ApprovalNodeConfig.class);
            case SCRIPT        -> BeanTool.objectToObject(config, ScriptNodeConfig.class);
            case SUBFLOW       -> BeanTool.objectToObject(config, SubflowNodeConfig.class);
            case RETURN_VALUE  -> BeanTool.objectToObject(config, ReturnValueNodeConfig.class);
            case FOR_EACH      -> BeanTool.objectToObject(config, ForEachNodeConfig.class);
            case HUMAN_TASK    -> BeanTool.objectToObject(config, HumanTaskNodeConfig.class);
            case TIMER         -> BeanTool.objectToObject(config, TimerNodeConfig.class);
            // All automated task nodes share TaskNodeConfig
            case CREATE_RECORD, GET_RECORD, UPDATE_RECORD, DELETE_RECORD,
                 QUERY_RECORDS, VALIDATE_DATA, TRANSFORM, CALL_SERVICE,
                 CALL_WEBHOOK, SEND_EMAIL, SEND_SMS, SEND_INBOX_NOTIFICATION,
                 QUERY_AI, ASYNC_TASK, GENERATE_FILE -> taskConfig(config);
            // Structural / routing nodes have no typed config
            default -> null;
        };
    }

    /**
     * A task node's config, optionally wrapped in {@code task}: its executor's input under {@code input}, or — as the
     * designer writes it, one key per field the executor's descriptor lists — at the top level beside the node's own
     * keys. Read only from {@code input}, a designed node handed its executor nothing, and publishing passed it
     * without a check of its required fields (zingkey-hcm#2384).
     */
    @SuppressWarnings("unchecked")
    private static TaskNodeConfig taskConfig(Map<String, Object> config) {
        Map<String, Object> source = config.get("task") instanceof Map<?, ?> task ? (Map<String, Object>) task : config;
        TaskNodeConfig parsed = BeanTool.objectToObject(source, TaskNodeConfig.class);
        if (parsed.getInput() == null && !source.containsKey("input")) {
            Map<String, Object> input = new LinkedHashMap<>(source);
            input.keySet().removeAll(TASK_NODE_KEYS);
            parsed.setInput(input.isEmpty() ? null : input);
        }
        return parsed;
    }
}
