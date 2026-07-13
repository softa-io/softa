package io.softa.starter.flow.runtime.handler;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.orm.utils.BeanTool;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.context.FlowVariableContext;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.runtime.nodeconfig.TaskConfigTypes;
import io.softa.starter.flow.runtime.nodeconfig.TaskNodeConfig;
import io.softa.starter.flow.runtime.task.builtin.DefaultTaskExecutorRegistry;
import io.softa.starter.flow.runtime.task.TaskExecutionRequest;
import io.softa.starter.flow.runtime.task.TaskExecutor;

/**
 * Delegates all executor-backed task nodes to registered {@link TaskExecutor} implementations.
 * <p>
 * Supported node types: {@code CREATE_RECORD}, {@code GET_RECORD}, {@code UPDATE_RECORD},
 * {@code DELETE_RECORD}, {@code QUERY_RECORDS}, {@code VALIDATE_DATA}, {@code TRANSFORM},
 * {@code CALL_SERVICE}, {@code CALL_WEBHOOK}, {@code SEND_EMAIL}, {@code SEND_SMS},
 * {@code SEND_INBOX_NOTIFICATION}, {@code QUERY_AI},
 * {@code ASYNC_TASK}, {@code GENERATE_FILE}.
 * </p>
 * <p>
 * For {@code ASYNC_TASK} nodes the result carries {@code waitForAsync=true} so the engine
 * suspends the instance until the task callback arrives.
 * </p>
 */
@Component
public class TaskNodeExecutionHandler implements NodeExecutionHandler {

    private static final Set<FlowNodeType> SUPPORTED_TYPES = EnumSet.of(
            FlowNodeType.CREATE_RECORD,
            FlowNodeType.GET_RECORD,
            FlowNodeType.UPDATE_RECORD,
            FlowNodeType.DELETE_RECORD,
            FlowNodeType.QUERY_RECORDS,
            FlowNodeType.VALIDATE_DATA,
            FlowNodeType.TRANSFORM,
            FlowNodeType.CALL_SERVICE,
            FlowNodeType.CALL_WEBHOOK,
            FlowNodeType.SEND_EMAIL,
            FlowNodeType.SEND_SMS,
            FlowNodeType.SEND_INBOX_NOTIFICATION,
            FlowNodeType.QUERY_AI,
            FlowNodeType.ASYNC_TASK,
            FlowNodeType.GENERATE_FILE
    );

    private final DefaultTaskExecutorRegistry taskExecutorRegistry;

    public TaskNodeExecutionHandler(DefaultTaskExecutorRegistry taskExecutorRegistry) {
        this.taskExecutorRegistry = taskExecutorRegistry;
    }

    @Override
    public boolean supports(FlowNodeType flowNodeType) {
        return SUPPORTED_TYPES.contains(flowNodeType);
    }

    @Override
    public NodeOutcome execute(CompiledFlowNode node, FlowVariableContext ctx) {
        if (!(node.getParsedConfig() instanceof TaskNodeConfig taskConfig)) {
            throw new FlowRuntimeException("Task node '" + node.getNodeId() + "' must configure config.task");
        }

        TaskExecutor executor = taskExecutorRegistry.getExecutor(node.getType());

        Map<String, Object> scope = ctx.toExpressionScope();

        // No generic pre-resolution: executors own their placeholder resolution — type-aware
        // VariableResolver for data executors, interpolate() for schemaless payloads (WebHook, etc.),
        // and self-resolution for the message/AI executors. Node types registered in TaskConfigTypes
        // additionally receive a parsed config DTO on the request.
        Map<String, Object> rawInput = taskConfig.getInput();
        Class<?> configType = TaskConfigTypes.forNodeType(node.getType());
        Object typedConfig = configType == null || rawInput == null
                ? null : BeanTool.objectToObject(rawInput, configType);
        String outputVariable = taskConfig.getOutputVariable();

        String instanceId = scope.get("_instanceId") instanceof String s ? s : null;
        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .flowNodeType(node.getType())
                .nodeId(node.getNodeId())
                .instanceId(instanceId)
                .input(rawInput == null ? Map.of() : rawInput)
                .config(typedConfig)
                .outputVariable(outputVariable)
                .options(taskConfig.getOptions() == null ? Map.of() : taskConfig.getOptions())
                .build();

        Map<String, Object> rawResult = executor.execute(request, scope);
        Map<String, Object> outputs = new LinkedHashMap<>();
        if (StringUtils.hasText(outputVariable)) {
            outputs.put(outputVariable, rawResult);
        } else if (rawResult != null) {
            outputs.putAll(rawResult);
        }

        return FlowNodeType.ASYNC_TASK.equals(node.getType())
                ? new NodeOutcome.WaitAsync(outputs)
                : new NodeOutcome.Completed(outputs);
    }
}
