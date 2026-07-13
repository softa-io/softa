package io.softa.starter.flow.runtime.task.builtin;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.runtime.nodeconfig.TaskConfigTypes;
import io.softa.starter.flow.runtime.task.TaskExecutor;

/**
 * Spring-backed task executor registry enforcing the 1:1 {@link FlowNodeType} contract.
 * <p>
 * Duplicate registrations (two executors with the same {@code getSupportedNodeType()})
 * cause an {@link IllegalStateException} at startup so misconfigurations are caught early.
 * Every registered executor's node type must also have a {@link TaskConfigTypes} entry —
 * an executor without a typed config DTO would silently escape compile-time validation.
 * </p>
 */
@Component
public class DefaultTaskExecutorRegistry {

    private final Map<FlowNodeType, TaskExecutor> executorMap;

    public DefaultTaskExecutorRegistry(List<TaskExecutor> executors) {
        for (TaskExecutor executor : executors) {
            if (TaskConfigTypes.forNodeType(executor.getSupportedNodeType()) == null) {
                throw new IllegalStateException("TaskExecutor '" + executor.getExecutor()
                        + "' targets node type " + executor.getSupportedNodeType()
                        + " which has no TaskConfigTypes entry — register its typed config DTO"
                        + " so compile-time validation covers it");
            }
        }
        this.executorMap = executors.stream()
                .collect(Collectors.toMap(
                        TaskExecutor::getSupportedNodeType,
                        Function.identity(),
                        (a, b) -> {
                            throw new IllegalStateException(
                                    "Duplicate TaskExecutor registered for FlowNodeType '"
                                            + a.getSupportedNodeType() + "': '"
                                            + a.getExecutor() + "' and '" + b.getExecutor() + "'");
                        }));
    }

    public TaskExecutor getExecutor(FlowNodeType flowNodeType) {
        TaskExecutor executor = executorMap.get(flowNodeType);
        if (executor == null) {
            throw new FlowRuntimeException("No task executor registered for node type " + flowNodeType);
        }
        return executor;
    }
}
