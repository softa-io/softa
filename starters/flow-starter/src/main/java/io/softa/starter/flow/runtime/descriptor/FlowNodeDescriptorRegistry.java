package io.softa.starter.flow.runtime.descriptor;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

import io.softa.starter.flow.design.FlowNodeDescriptor;
import io.softa.starter.flow.design.FlowNodeDescriptorProvider;
import io.softa.starter.flow.enums.FlowScenario;

/**
 * Aggregates all {@link FlowNodeDescriptorProvider}s and exposes lookup methods.
 * <p>
 * Enforces the 1:1 contract: each FlowNodeType may have at most one descriptor.
 * Duplicate registrations cause a startup failure rather than silently discarding one.
 * </p>
 */
@Component
public class FlowNodeDescriptorRegistry {

    private final Map<String, FlowNodeDescriptor> byType;

    public FlowNodeDescriptorRegistry(List<FlowNodeDescriptorProvider> providers) {
        this.byType = providers.stream()
                .flatMap(p -> p.getDescriptors().stream())
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

    public Optional<FlowNodeDescriptor> get(String type) {
        return Optional.ofNullable(byType.get(type));
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
}
