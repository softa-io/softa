package io.softa.starter.flow.runtime.trigger;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.trigger.ApiTrigger;
import io.softa.starter.flow.design.trigger.SubflowTrigger;
import io.softa.starter.flow.design.trigger.TriggerSource;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A trigger is its flow's start contract: an Api flow is started explicitly by id and a Subflow flow only
 * from a parent, so no trigger event starts either — the registry never matches them, and the generic
 * trigger entry refuses those event types.
 */
class TriggerStartContractTest {

    private DefaultFlowTriggerRegistry registryFor(CompiledFlowDefinition... flows) {
        FlowBundleRegistry bundleRegistry = new FlowBundleRegistry() {
            @Override public CompiledFlowDefinition register(CompiledFlowDefinition d, DesignFlowDefinition design, String flowId) { return d; }
            @Override public Optional<CompiledFlowDefinition> getByBundleId(Long bundleId) { return Optional.empty(); }
            @Override public Optional<CompiledFlowDefinition> getActiveByFlowId(String flowId) { return Optional.empty(); }
            @Override public Collection<CompiledFlowDefinition> list() { return List.of(flows); }
        };
        return new DefaultFlowTriggerRegistry(bundleRegistry);
    }

    private static CompiledFlowDefinition automation(String flowId, TriggerSource trigger) {
        return CompiledFlowDefinition.builder()
                .flowId(flowId)
                .flowName(flowId)
                .scenario(FlowScenario.AUTOMATION)
                .trigger(trigger)
                .build();
    }

    @Test
    void noEventStartsAnApiOrSubflowFlow() {
        DefaultFlowTriggerRegistry registry = registryFor(
                automation("on-demand", new ApiTrigger(null)), automation("child", new SubflowTrigger()));

        assertTrue(registry.findMatchingFlows(FlowTriggerEvent.builder().type("Api").build()).isEmpty());
        assertTrue(registry.findMatchingFlows(FlowTriggerEvent.builder().type("Subflow").build()).isEmpty());
    }
}
