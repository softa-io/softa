package io.softa.starter.flow.runtime.descriptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import io.softa.starter.flow.design.FlowNodeDescriptor;
import io.softa.starter.flow.design.FlowNodeDescriptorProvider;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.task.TaskExecutor;

/**
 * Converts each registered {@link TaskExecutor} into a {@link FlowNodeDescriptor}
 * so that task-based node types appear automatically in the frontend palette.
 */
@Component
public class TaskExecutorDescriptorProvider implements FlowNodeDescriptorProvider {

    private static final Set<FlowScenario> ALL_SCENARIOS = Set.of(FlowScenario.values());

    private final List<TaskExecutor> executors;

    public TaskExecutorDescriptorProvider(List<TaskExecutor> executors) {
        this.executors = executors;
    }

    @Override
    public List<FlowNodeDescriptor> getDescriptors() {
        return executors.stream()
                .map(this::toDescriptor)
                .toList();
    }

    private FlowNodeDescriptor toDescriptor(TaskExecutor executor) {
        return FlowNodeDescriptor.of(
                executor.getSupportedNodeType(),
                executor.getName(),
                executor.getDescription(),
                executor.getIcon(),
                executor.getSortOrder(),
                null,
                executor.getConfigSchema(),
                executor.getDefaultConfig() != null ? executor.getDefaultConfig() : Map.of(),
                ALL_SCENARIOS,
                executor.isProductionReady()
        );
    }
}
