package io.softa.starter.flow.runtime.descriptor;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

import io.softa.starter.flow.design.FlowNodeDescriptor;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.task.TaskExecutor;

/**
 * The node descriptors the designer's palette and property panels read: the built-in structural nodes'
 * ({@link BuiltinNodeDescriptorProvider}) and one per registered {@link TaskExecutor}, offered to the scenarios its
 * node type allows. A task node's fields are its executor's, and an {@code outputVariable} besides: the variable its
 * result is written to, which later nodes and routes read it by — without one, the result's keys land in the
 * variables as they are, and two nodes writing {@code result} overwrite each other.
 * <p>
 * Each node type has one descriptor: a second fails the boot rather than silently discarding one.
 */
@Component
public class FlowNodeDescriptorRegistry {

    /** The field every task node takes for the variable its result is written to. */
    static final String OUTPUT_VARIABLE = "outputVariable";

    private final Map<String, FlowNodeDescriptor> byType;

    public FlowNodeDescriptorRegistry(BuiltinNodeDescriptorProvider builtin, List<TaskExecutor> executors) {
        this.byType = Stream.concat(builtin.getDescriptors().stream(),
                        executors.stream().map(FlowNodeDescriptorRegistry::describe))
                .collect(Collectors.toMap(
                        node -> node.type().getType(),
                        Function.identity(),
                        (a, b) -> {
                            throw new IllegalStateException(
                                    "Duplicate FlowNodeDescriptor registered for type '"
                                            + a.type() + "': found both '" + a.label()
                                            + "' and '" + b.label() + "'");
                        }));
    }

    public Collection<FlowNodeDescriptor> list() {
        return byType.values().stream()
                .sorted(Comparator.comparingInt(FlowNodeDescriptor::sortOrder)
                        .thenComparing(FlowNodeDescriptor::type))
                .toList();
    }

    public List<FlowNodeDescriptor> listByScenario(FlowScenario scenario) {
        return byType.values().stream()
                .filter(d -> d.allowedScenarios().contains(scenario))
                .sorted(Comparator.comparingInt(FlowNodeDescriptor::sortOrder)
                        .thenComparing(FlowNodeDescriptor::type))
                .toList();
    }

    private static FlowNodeDescriptor describe(TaskExecutor executor) {
        Map<String, Object> schema = new LinkedHashMap<>(executor.getConfigSchema());
        Map<String, Object> outputVariable = new LinkedHashMap<>();
        outputVariable.put("type", "string");
        outputVariable.put("label", "Output variable");
        schema.putIfAbsent(OUTPUT_VARIABLE, outputVariable);
        return FlowNodeDescriptor.of(
                executor.getSupportedNodeType(),
                executor.getName(),
                executor.getDescription(),
                executor.getIcon(),
                executor.getSortOrder(),
                null,
                schema,
                executor.getDefaultConfig() != null ? executor.getDefaultConfig() : Map.of(),
                executor.getSupportedNodeType().getScenarios()
        );
    }
}
