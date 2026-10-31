package io.softa.starter.flow.runtime.trigger;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.api.CompileDiagnostic;
import io.softa.starter.flow.compiler.validation.TriggerConsistencyValidator;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.trigger.ApiTrigger;
import io.softa.starter.flow.design.trigger.EntityChangeTrigger;
import io.softa.starter.flow.design.trigger.SubmitTrigger;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.LeaveRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.engine.ModelApprovalHandlerRegistry;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
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
        return CompiledFlowDefinition.builder()
                .flowId(flowId)
                .flowName(flowId)
                .modelName(model)
                .trigger(new SubmitTrigger())
                .build();
    }

    @Test
    void aSubmissionGoesToTheApprovalFlowOfItsModel() {
        CompiledFlowDefinition onChange = CompiledFlowDefinition.builder().flowId("audit").flowName("audit")
                .modelName("LeaveRequest").trigger(new EntityChangeTrigger(List.of(), null)).build();
        DefaultFlowTriggerRegistry registry = registryFor(
                submitFlow("leave", "LeaveRequest"), submitFlow("claim", "ExpenseClaim"), onChange);

        assertEquals(List.of("leave"),
                registry.findSubmitFlows("LeaveRequest").stream().map(CompiledFlowDefinition::getFlowId).toList());
        assertEquals(List.of("claim"),
                registry.findSubmitFlows("ExpenseClaim").stream().map(CompiledFlowDefinition::getFlowId).toList());
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
                .scenario(FlowScenario.VALIDATION).modelName("LeaveRequest").trigger(new SubmitTrigger()).build(), null);
        List<CompileDiagnostic> noModel = validator.validate(DesignFlowDefinition.builder()
                .scenario(FlowScenario.APPROVAL).modelName(" ").trigger(new SubmitTrigger()).build(), null);
        List<CompileDiagnostic> valid = validator.validate(DesignFlowDefinition.builder()
                .scenario(FlowScenario.APPROVAL).modelName("LeaveRequest").trigger(new SubmitTrigger()).build(), null);

        assertEquals(1, wrongScenario.size());
        assertEquals(List.of("MISSING_MODEL"), noModel.stream().map(CompileDiagnostic::code).toList());
        assertTrue(valid.isEmpty());
    }

    @Test
    void aFlowStartedOnNoRecordNamesNoModel() {
        List<CompileDiagnostic> diagnostics = new TriggerConsistencyValidator().validate(DesignFlowDefinition.builder()
                .scenario(FlowScenario.AUTOMATION).modelName("LeaveRequest").trigger(new ApiTrigger(null)).build(), null);

        assertEquals(List.of("MODEL_WITHOUT_RECORD"), diagnostics.stream().map(CompileDiagnostic::code).toList());
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
