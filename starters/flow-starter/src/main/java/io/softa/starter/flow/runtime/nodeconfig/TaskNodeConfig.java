package io.softa.starter.flow.runtime.nodeconfig;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Shared config for all automated task nodes (CREATE_RECORD, GET_RECORD, UPDATE_RECORD,
 * DELETE_RECORD, QUERY_RECORDS, VALIDATE_DATA, TRANSFORM, CALL_SERVICE, CALL_WEBHOOK,
 * SEND_EMAIL, SEND_SMS, SEND_INBOX_NOTIFICATION, QUERY_AI, ASYNC_TASK, GENERATE_FILE).
 * <p>
 * The {@code executor} field has been removed — the {@code FlowNodeType} itself now
 * identifies which {@code TaskExecutor} handles the node, eliminating the
 * former two-key (kind + executor) lookup.
 *
 * <pre>{@code
 * {
 *   "input": {
 *     "modelName": "Order",
 *     "filter": "{{ orderId }}"
 *   },
 *   "outputVariable": "order",
 *   "outputMapping": {
 *     "orderStatus": "status"
 *   },
 *   "options": {
 *     "timeoutSeconds": 10
 *   }
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskNodeConfig {

    /**
     * Executor input payload. String values support {@code {{ expr }}} interpolation
     * against the current {@link io.softa.starter.flow.runtime.context.FlowVariableContext}.
     */
    private Map<String, Object> input;

    /**
     * (Optional) Write the executor's entire output map under this key in {@code vars}.
     */
    private String outputVariable;

    /**
     * (Optional) Fine-grained output field → var key mapping.
     * Each entry copies {@code output[outputField]} into {@code vars[varKey]}.
     * <pre>{ "varKey": "outputField" }</pre>
     */
    private Map<String, String> outputMapping;

    /**
     * Executor-specific options (e.g. {@code timeoutSeconds}, {@code async}).
     * Interpreted by the concrete {@code TaskExecutor} implementation.
     */
    private Map<String, Object> options;
}
