package io.softa.starter.flow.runtime.bundle;

import java.util.Map;

import io.softa.framework.orm.utils.BeanTool;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.nodeconfig.*;

/**
 * Parses a node's raw config map into its strongly-typed {@code *NodeConfig}.
 * <p>
 * Called from two places that must stay in sync: the compiler
 * ({@code NodeTransformer.compileNodes}) when a design graph is compiled, and
 * {@link FlowBundleMapper#toDefinition} when a persisted bundle is deserialized —
 * {@link CompiledFlowNode#getParsedConfig()} is {@code @JsonIgnore} and must be
 * rebuilt on every load or node handlers pattern-matching on it would fail.
 */
public final class NodeConfigParser {

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
                 QUERY_AI, ASYNC_TASK, GENERATE_FILE -> {
                Object taskObj = config.get("task");
                yield (taskObj instanceof Map<?, ?>) ? BeanTool.objectToObject(taskObj, TaskNodeConfig.class)
                        : BeanTool.objectToObject(config, TaskNodeConfig.class);
            }
            // Structural / routing nodes have no typed config
            default -> null;
        };
    }
}
