package io.softa.starter.flow.runtime.trigger;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.domain.Filters;
import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.compiler.validation.TriggerConsistencyValidator;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.trigger.EntityChangeTrigger;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.LeaveRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.engine.ModelApprovalHandlerRegistry;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.runtime.spi.ModelApprovalHandler;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * A submission goes to its document's approval flow — chosen by the record's model alone — and never to a flow a
 * generic trigger event names; the REST trigger refuses it and the compiler keeps it on Approval flows, with the
 * flow's model going with its trigger.
 */
class SubmitTriggerTest {

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

    private static CompiledFlowDefinition submitFlow(String flowId, String model) {
        return submitFlow(flowId, new SubmitTrigger(model));
    }

    private static CompiledFlowDefinition submitFlow(String flowId, SubmitTrigger trigger) {
        return CompiledFlowDefinition.builder()
                .flowId(flowId)
                .flowName(flowId)
                .trigger(trigger)
                .build();
    }

    @Test
    void aSubmissionGoesToTheApprovalFlowOfItsModel() {
        CompiledFlowDefinition onChange = CompiledFlowDefinition.builder().flowId("audit").flowName("audit")
                .trigger(new EntityChangeTrigger("LeaveRequest", List.of(), null)).build();
        DefaultFlowTriggerRegistry registry = registryFor(
                submitFlow("leave", "LeaveRequest"), submitFlow("claim", "ExpenseClaim"), onChange);

        assertEquals(List.of("leave"), flowIds(registry.findSubmitFlows("LeaveRequest", Map.of())));
        assertEquals(List.of("claim"), flowIds(registry.findSubmitFlows("ExpenseClaim", Map.of())));
    }

    @Test
    void aDocumentsApprovalFlowsTakeTheRecordsTheirConditionsHoldFor() {
        DefaultFlowTriggerRegistry registry = registryFor(
                submitFlow("leave", new SubmitTrigger("LeaveRequest", "leaveRequestType == 'Leave'")),
                submitFlow("cancel", new SubmitTrigger("LeaveRequest", null,
                        Filters.of(List.of("leaveRequestType", "=", "CancelLeave")))),
                submitFlow("any", "ExpenseClaim"));

        assertEquals(List.of("leave"), flowIds(registry.findSubmitFlows("LeaveRequest",
                Map.of("leaveRequestType", "Leave"))));
        assertEquals(List.of("cancel"), flowIds(registry.findSubmitFlows("LeaveRequest",
                Map.of("leaveRequestType", "CancelLeave"))));
        assertEquals(List.of("any"), flowIds(registry.findSubmitFlows("ExpenseClaim", Map.of("amount", 10))),
                "a flow without a condition takes every record");
    }

    @Test
    void aSubmitConditionThatCannotBeEvaluatedIsAFault() {
        DefaultFlowTriggerRegistry registry = registryFor(
                submitFlow("broken", new SubmitTrigger("LeaveRequest", "nosuchfunction(days)")));

        assertThrows(FlowRuntimeException.class, () -> registry.findSubmitFlows("LeaveRequest", Map.of("days", 2)));
    }

    private static List<String> flowIds(List<CompiledFlowDefinition> flows) {
        return flows.stream().map(CompiledFlowDefinition::getFlowId).toList();
    }

    @Test
    void aGenericTriggerEventNeverStartsASubmitFlow() {
        DefaultFlowTriggerRegistry registry = registryFor(submitFlow("annual", "LeaveRequest"));
        FlowTriggerEvent event = FlowTriggerEvent.builder().type("Submit").sourceModel("LeaveRequest").build();

        assertTrue(registry.findMatchingFlows(event).isEmpty());
    }

    @Test
    void theTriggerEndpointRefusesTheSubmitType() {
        FlowAutomationService automation = new FlowAutomationService(registryFor(), mock(FlowRuntimeEngine.class));
        FlowTriggerEvent event = FlowTriggerEvent.builder().type("Submit").sourceModel("LeaveRequest").build();

        assertThrows(FlowActionValidationException.class, () -> automation.fire(event));
    }

    @Test
    void theCompilerKeepsSubmitTriggersOnApprovalFlowsThatNameTheirModel() {
        TriggerConsistencyValidator validator = new TriggerConsistencyValidator();

        List<CompileDiagnostic> wrongScenario = validator.validate(DesignFlowDefinition.builder()
                .scenario(FlowScenario.VALIDATION).trigger(new SubmitTrigger("LeaveRequest")).build(), null);
        List<CompileDiagnostic> noModel = validator.validate(DesignFlowDefinition.builder()
                .scenario(FlowScenario.APPROVAL).trigger(new SubmitTrigger(" ")).build(), null);
        List<CompileDiagnostic> valid = validator.validate(DesignFlowDefinition.builder()
                .scenario(FlowScenario.APPROVAL).trigger(new SubmitTrigger("LeaveRequest")).build(), null);

        assertEquals(1, wrongScenario.size());
        assertEquals(List.of("MISSING_MODEL"), noModel.stream().map(CompileDiagnostic::code).toList());
        assertTrue(valid.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void twoHandlersForOneModelAreRefused() {
        ModelApprovalHandler<LeaveRequest> first = mock(ModelApprovalHandler.class);
        ModelApprovalHandler<LeaveRequest> second = mock(ModelApprovalHandler.class);
        when(first.modelClass()).thenReturn(LeaveRequest.class);
        when(second.modelClass()).thenReturn(LeaveRequest.class);

        ModelApprovalHandlerRegistry registry = ModelApprovalHandlerRegistry.of(List.of(first, second));

        assertThrows(IllegalStateException.class, registry::afterSingletonsInstantiated);
    }
}
