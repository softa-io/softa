package io.softa.starter.flow.runtime.handler;

import java.util.LinkedHashMap;
import java.util.Map;
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
 * Delegates all executor-backed task nodes — the node types {@link TaskConfigTypes} lists — to registered
 * {@link TaskExecutor} implementations.
 * <p>
 * For {@code ASYNC_TASK} nodes the result carries {@code waitForAsync=true} so the engine
 * suspends the instance until the task callback arrives.
 * </p>
 */
@Component
public class TaskNodeExecutionHandler implements NodeExecutionHandler {

    private final DefaultTaskExecutorRegistry taskExecutorRegistry;

    public TaskNodeExecutionHandler(DefaultTaskExecutorRegistry taskExecutorRegistry) {
        this.taskExecutorRegistry = taskExecutorRegistry;
    }

    @Override
    public boolean supports(FlowNodeType flowNodeType) {
        return TaskConfigTypes.forNodeType(flowNodeType) != null;
    }

    @Override
    public NodeOutcome execute(CompiledFlowNode node, FlowVariableContext ctx) {
        if (!(node.getParsedConfig() instanceof TaskNodeConfig taskConfig)) {
            throw new FlowRuntimeException("Task node '" + node.displayName() + "' must configure config.task");
        }

        TaskExecutor executor = taskExecutorRegistry.getExecutor(node.getType());

        Map<String, Object> scope = ctx.toExpressionScope();

        // No generic pre-resolution: executors own their placeholder resolution — type-aware
        // VariableResolver for data executors, interpolate() for schemaless payloads (WebHook, etc.),
        // and self-resolution for the message/AI executors. Node types registered in TaskConfigTypes
        // additionally receive a parsed config DTO on the request.
        Map<String, Object> rawInput = taskConfig.getInput();
        Class<?> configType = TaskConfigTypes.forNodeType(node.getType());
        Object typedConfig = rawInput == null ? null : BeanTool.objectToObject(rawInput, configType);
        String outputVariable = taskConfig.getOutputVariable();

        String instanceId = scope.get("_instanceId") instanceof String s ? s : null;
        TaskExecutionRequest request = TaskExecutionRequest.builder()
                .nodeId(node.getNodeId())
                .instanceId(instanceId)
                .input(rawInput == null ? Map.of() : rawInput)
                .config(typedConfig)
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
