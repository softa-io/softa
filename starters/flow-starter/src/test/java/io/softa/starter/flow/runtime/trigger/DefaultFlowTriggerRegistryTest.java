package io.softa.starter.flow.runtime.trigger;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.trigger.ChangeEvent;
import io.softa.starter.flow.design.trigger.CronTrigger;
import io.softa.starter.flow.design.trigger.EntityChangeTrigger;
import io.softa.starter.flow.design.trigger.FieldChangeTrigger;
import io.softa.starter.flow.message.ChangeLogTriggerMapper;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that {@link DefaultFlowTriggerRegistry} honors an {@link EntityChangeTrigger}'s
 * {@code events} filter (R5 fix). Before the fix, {@code events} was inert and a
 * CREATE-only trigger fired on every access type.
 */
class DefaultFlowTriggerRegistryTest {

    private DefaultFlowTriggerRegistry registryFor(CompiledFlowDefinition... flows) {
        FlowBundleRegistry bundleRegistry = new FlowBundleRegistry() {
            @Override public CompiledFlowDefinition register(CompiledFlowDefinition d, DesignFlowDefinition design, String flowId) { return d; }
            @Override public Optional<CompiledFlowDefinition> getByBundleId(Long bundleId) { return Optional.empty(); }
            @Override public Optional<CompiledFlowDefinition> getActiveByFlowId(String flowId) { return Optional.empty(); }
            @Override public List<CompiledFlowDefinition> listRevisionsByFlowId(String flowId) { return List.of(); }
            @Override public Collection<CompiledFlowDefinition> list() { return List.of(flows); }
        };
        return new DefaultFlowTriggerRegistry(bundleRegistry);
    }

    private CompiledFlowDefinition entityFlow(String code, String model, List<ChangeEvent> events) {
        return CompiledFlowDefinition.builder()
                .flowId(code)
                .modelName(model).trigger(new EntityChangeTrigger(events, null))
                .build();
    }

    private CompiledFlowDefinition entityFlow(String code, String model, List<ChangeEvent> events, Long tenantId) {
        return CompiledFlowDefinition.builder()
                .flowId(code)
                .tenantId(tenantId)
                .modelName(model).trigger(new EntityChangeTrigger(events, null))
                .build();
    }

    private CompiledFlowDefinition fieldChangeFlow(String code, String model, List<String> fieldNames) {
        return CompiledFlowDefinition.builder()
                .flowId(code)
                .modelName(model).trigger(new FieldChangeTrigger(fieldNames))
                .build();
    }

    @Test
    void filtersByTenantAtMatchTime() {
        DefaultFlowTriggerRegistry registry = registryFor(
                entityFlow("tenantA", "Order", List.of(ChangeEvent.CREATE), 7L),
                entityFlow("tenantB", "Order", List.of(ChangeEvent.CREATE), 9L),
                entityFlow("platform", "Order", List.of(ChangeEvent.CREATE), 0L));
        Context ctx = new Context();
        ctx.setTenantId(7L);
        ContextHolder.runWith(ctx, () -> {
            List<CompiledFlowDefinition> matched = registry.findMatchingFlows(entityEvent("Order", "CREATE"));
            // tenant 7 fires its own flow + the platform-shared (tenant 0) flow, never tenant 9's
            assertEquals(2, matched.size());
            assertTrue(matched.stream().anyMatch(f -> "tenantA".equals(f.getFlowId())));
            assertTrue(matched.stream().anyMatch(f -> "platform".equals(f.getFlowId())));
            assertTrue(matched.stream().noneMatch(f -> "tenantB".equals(f.getFlowId())));
        });
    }

    private FlowTriggerEvent entityEvent(String model, String accessType) {
        return FlowTriggerEvent.builder()
                .type("EntityChange")
                .sourceModel(model)
                .parameters(Map.of(ChangeLogTriggerMapper.PARAM_ACCESS_TYPE, accessType))
                .build();
    }

    @Test
    void createOnlyTriggerFiresOnCreate() {
        DefaultFlowTriggerRegistry registry = registryFor(entityFlow("f", "Order", List.of(ChangeEvent.CREATE)));
        assertEquals(1, registry.findMatchingFlows(entityEvent("Order", "CREATE")).size());
    }

    @Test
    void createOnlyTriggerDoesNotFireOnUpdateOrDelete() {
        DefaultFlowTriggerRegistry registry = registryFor(entityFlow("f", "Order", List.of(ChangeEvent.CREATE)));
        assertTrue(registry.findMatchingFlows(entityEvent("Order", "UPDATE")).isEmpty());
        assertTrue(registry.findMatchingFlows(entityEvent("Order", "DELETE")).isEmpty());
    }

    @Test
    void emptyEventsFilterMatchesEveryChangeType() {
        DefaultFlowTriggerRegistry registry = registryFor(entityFlow("f", "Order", List.of()));
        assertEquals(1, registry.findMatchingFlows(entityEvent("Order", "CREATE")).size());
        assertEquals(1, registry.findMatchingFlows(entityEvent("Order", "UPDATE")).size());
        assertEquals(1, registry.findMatchingFlows(entityEvent("Order", "DELETE")).size());
    }

    @Test
    void nullEventsFilterMatchesEveryChangeType() {
        DefaultFlowTriggerRegistry registry = registryFor(entityFlow("f", "Order", null));
        assertEquals(1, registry.findMatchingFlows(entityEvent("Order", "UPDATE")).size());
    }

    @Test
    void triggerNeverFiresOnRead() {
        DefaultFlowTriggerRegistry registry = registryFor(entityFlow("f", "Order", List.of(ChangeEvent.CREATE, ChangeEvent.UPDATE, ChangeEvent.DELETE)));
        assertTrue(registry.findMatchingFlows(entityEvent("Order", "READ")).isEmpty());
    }

    @Test
    void modelFilterStillApplies() {
        DefaultFlowTriggerRegistry registry = registryFor(entityFlow("f", "Order", List.of(ChangeEvent.CREATE)));
        assertTrue(registry.findMatchingFlows(entityEvent("Customer", "CREATE")).isEmpty());
    }

    /**
     * A Cron flow is started by its own schedule only: the Cron event every cron tick used to become no longer
     * matches it, nor does any other event.
     */
    @Test
    void cronTriggerIsNeverMatchedByAnEvent() {
        CompiledFlowDefinition cronFlow = CompiledFlowDefinition.builder()
                .flowId("report")
                .trigger(new CronTrigger("0 0 9 ? * MON-FRI"))
                .build();
        DefaultFlowTriggerRegistry registry = registryFor(cronFlow);
        FlowTriggerEvent cronTick = FlowTriggerEvent.builder()
                .type("Cron")
                .sourceModel("cron:6")
                .parameters(Map.of("_cronName", "flow_timer_sweep"))
                .build();

        assertTrue(registry.findMatchingFlows(cronTick).isEmpty());
        assertTrue(registry.findMatchingFlows(entityEvent("Order", "CREATE")).isEmpty());
    }

    @Test
    void fieldChangeTriggerMatchesConfiguredChangedField() {
        DefaultFlowTriggerRegistry registry = registryFor(fieldChangeFlow("f", "Order", List.of("amount")));
        FlowTriggerEvent event = FlowTriggerEvent.builder()
                .type("FieldChange")
                .sourceModel("Order")
                .parameters(Map.of(DefaultFlowTriggerRegistry.PARAM_CHANGED_FIELDS, List.of("amount")))
                .build();

        assertEquals(1, registry.findMatchingFlows(event).size());
    }

    @Test
    void fieldChangeTriggerDoesNotMatchOtherModelOrField() {
        DefaultFlowTriggerRegistry registry = registryFor(fieldChangeFlow("f", "Order", List.of("amount")));
        FlowTriggerEvent otherField = FlowTriggerEvent.builder()
                .type("FieldChange")
                .sourceModel("Order")
                .parameters(Map.of(DefaultFlowTriggerRegistry.PARAM_CHANGED_FIELDS, List.of("status")))
                .build();
        FlowTriggerEvent otherModel = FlowTriggerEvent.builder()
                .type("FieldChange")
                .sourceModel("Customer")
                .parameters(Map.of(DefaultFlowTriggerRegistry.PARAM_CHANGED_FIELDS, List.of("amount")))
                .build();

        assertTrue(registry.findMatchingFlows(otherField).isEmpty());
        assertTrue(registry.findMatchingFlows(otherModel).isEmpty());
    }
}
