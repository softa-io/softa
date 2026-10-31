package io.softa.starter.flow.runtime.trigger;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.starter.flow.design.DesignFlowDefinition;
import io.softa.starter.flow.design.trigger.ChangeEvent;
import io.softa.starter.flow.design.trigger.EntityChangeTrigger;
import io.softa.starter.flow.entity.FlowEvent;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.message.ChangeLogTriggerMapper;
import io.softa.starter.flow.runtime.api.FlowValidationRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.context.RecordModelFixture;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.trigger.DefaultFlowTriggerRegistry.TriggerMatch;
import io.softa.starter.flow.service.FlowEventService;

import static io.softa.starter.flow.runtime.context.RecordModelFixture.MODEL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A data-change trigger's condition — a rule over the record or an expression — decides whether the change starts
 * the flow, over the variables the flow would start with; a condition that cannot be evaluated is a failure the
 * event log shows, and stops a write or a validation that depends on the flow, never a quiet "no".
 */
class TriggerConditionTest {

    private MockedStatic<ModelManager> modelManager;

    @BeforeEach
    void setUp() {
        modelManager = RecordModelFixture.mockModelManager();
    }

    @AfterEach
    void tearDown() {
        modelManager.close();
    }

    @Test
    void aRuleStartsTheFlowForTheRecordsItMatchesJudgedByTheFieldsTypes() {
        DefaultFlowTriggerRegistry registry = registryFor(flow("long-leave", Filters.of(List.of("days", ">", 3)), null));

        assertEquals(1, registry.findMatchingFlows(changeOf(Map.of("days", new BigDecimal("5")))).size());
        assertTrue(registry.findMatchingFlows(changeOf(Map.of("days", new BigDecimal("2")))).isEmpty());
        assertTrue(registry.findMatchingFlows(changeOf(Map.of())).isEmpty(), "an empty field orders against nothing");
    }

    @Test
    void anExpressionReadsTheVariablesTheFlowStartsWith() {
        DefaultFlowTriggerRegistry registry = registryFor(flow("row-7", null, "days > 3 && _sourceRowId == '7'"));

        assertEquals(1, registry.findMatchingFlows(changeOf(Map.of("days", 5))).size());
        assertTrue(registry.findMatchingFlows(changeOf(Map.of("days", 1))).isEmpty());
    }

    @Test
    void aConditionThatCannotBeEvaluatedIsAFailedMatch() {
        DefaultFlowTriggerRegistry registry = registryFor(flow("broken", null, "noSuchFunction(days)"));

        List<TriggerMatch> matches = registry.findMatches(changeOf(Map.of("days", 5)));

        assertEquals(1, matches.size());
        assertFalse(matches.getFirst().holds());
        assertTrue(matches.getFirst().error().contains("could not be evaluated"), matches.getFirst().error());
        assertTrue(registry.findMatchingFlows(changeOf(Map.of("days", 5))).isEmpty(), "no flow runs on it");
    }

    @Test
    void theFailureIsRecordedAndNoRunStarts() {
        FlowRuntimeEngine engine = mock(FlowRuntimeEngine.class);
        FlowEventService events = mock(FlowEventService.class);
        FlowAutomationService automation = new FlowAutomationService(
                registryFor(flow("broken", null, "noSuchFunction(days)")), engine);
        ReflectionTestUtils.setField(automation, "eventService", events);

        List<FlowTriggerResult> results = automation.fireAsyncOnly(changeOf(Map.of("days", 5)));

        assertEquals(1, results.size());
        assertFalse(results.getFirst().isSuccess());
        verify(engine, never()).start(any());
        ArgumentCaptor<FlowEvent> logged = ArgumentCaptor.forClass(FlowEvent.class);
        verify(events).recordEvent(logged.capture());
        assertFalse(logged.getValue().getSuccess());
        assertTrue(logged.getValue().getErrorMessage().contains("could not be evaluated"));
    }

    @Test
    void aWriteWhoseFlowMustSucceedFailsWithIt() {
        CompiledFlowDefinition guard = flow("guard", null, "noSuchFunction(days)").toBuilder()
                .sync(true).rollbackOnFail(true).build();
        FlowAutomationService automation = new FlowAutomationService(registryFor(guard), mock(FlowRuntimeEngine.class));

        assertThrows(FlowRuntimeException.class, () -> automation.fireSyncOnly(changeOf(Map.of("days", 5))));
    }

    @Test
    void aValidationWhoseConditionCannotBeEvaluatedDoesNotReadAsPassed() {
        CompiledFlowDefinition check = flow("check", null, "noSuchFunction(days)").toBuilder()
                .scenario(FlowScenario.VALIDATION).build();
        FlowRuntimeEngine engine = mock(FlowRuntimeEngine.class);
        FlowAutomationService automation = new FlowAutomationService(registryFor(check), engine);
        FlowValidationRequest request = new FlowValidationRequest();
        request.setSourceModel(MODEL);
        request.setChangeType("CREATE");
        request.setData(Map.of("days", 5));

        assertThrows(FlowRuntimeException.class, () -> automation.evaluateValidation(request));
        verify(engine, never()).evaluate(any());
    }

    @Test
    void aHoldingConditionStartsTheRunWithTheSameVariables() {
        FlowRuntimeEngine engine = mock(FlowRuntimeEngine.class);
        when(engine.start(any())).thenReturn(new FlowExecutionState());
        FlowAutomationService automation = new FlowAutomationService(
                registryFor(flow("long-leave", Filters.of(List.of("days", ">", 3)), null)), engine);

        List<FlowTriggerResult> results = automation.fireAsyncOnly(changeOf(Map.of("days", new BigDecimal("5"))));

        assertTrue(results.getFirst().isSuccess());
        verify(engine).start(any());
    }

    private static CompiledFlowDefinition flow(String id, Filters rule, String expression) {
        return CompiledFlowDefinition.builder()
                .flowId(id).flowName(id).bundleId(1L)
                .scenario(FlowScenario.AUTOMATION)
                .trigger(new EntityChangeTrigger(MODEL, List.of(ChangeEvent.CREATE), expression, rule))
                .build();
    }

    private static FlowTriggerEvent changeOf(Map<String, Object> data) {
        Map<String, Object> parameters = new LinkedHashMap<>(data);
        parameters.put(ChangeLogTriggerMapper.PARAM_ACCESS_TYPE, "CREATE");
        return FlowTriggerEvent.builder()
                .type("EntityChange")
                .sourceModel(MODEL)
                .sourceRowId("7")
                .parameters(parameters)
                .build();
    }

    private static DefaultFlowTriggerRegistry registryFor(CompiledFlowDefinition... flows) {
        return new DefaultFlowTriggerRegistry(new FlowBundleRegistry() {
            @Override public CompiledFlowDefinition register(CompiledFlowDefinition d, DesignFlowDefinition design, String flowId) { return d; }
            @Override public Optional<CompiledFlowDefinition> getByBundleId(Long bundleId) { return Optional.empty(); }
            @Override public Optional<CompiledFlowDefinition> getActiveByFlowId(String flowId) { return Optional.empty(); }
            @Override public List<CompiledFlowDefinition> listRevisionsByFlowId(String flowId) { return List.of(); }
            @Override public Collection<CompiledFlowDefinition> list() { return List.of(flows); }
        });
    }
}
