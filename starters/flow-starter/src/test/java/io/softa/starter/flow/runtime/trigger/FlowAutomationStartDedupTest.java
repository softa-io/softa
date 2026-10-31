package io.softa.starter.flow.runtime.trigger;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.framework.orm.changelog.message.dto.ChangeLog;
import io.softa.starter.flow.design.trigger.EntityChangeTrigger;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.message.ChangeLogTriggerMapper;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.service.FlowInstanceService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Start-dedup is by event: a redelivered change log finds the run it already started for the flow and
 * returns it, while distinct events — even on the same row — each start their own run.
 */
class FlowAutomationStartDedupTest {

    private final FlowRuntimeEngine engine = mock(FlowRuntimeEngine.class);
    private final FlowInstanceService instanceService = mock(FlowInstanceService.class);
    private final DefaultFlowTriggerRegistry registry = mock(DefaultFlowTriggerRegistry.class);
    private final FlowAutomationService service = new FlowAutomationService(registry, engine);

    @BeforeEach
    void wireInstanceService() {
        ReflectionTestUtils.setField(service, "instanceService", instanceService);
    }

    private static FlowInstance instance(String id) {
        FlowInstance i = new FlowInstance();
        i.setInstanceId(id);
        i.setFlowId("audit");
        return i;
    }

    private static FlowTriggerEvent changeOf(String rowId, String uuid) {
        ChangeLog changeLog = new ChangeLog();
        changeLog.setUuid(uuid);
        changeLog.setModel("ExpenseClaim");
        changeLog.setRowId(rowId);
        return ChangeLogTriggerMapper.mapChangeLogs(List.of(changeLog), "user-1").getFirst();
    }

    @Test
    void returnsTheRunTheEventAlreadyStarted() {
        when(instanceService.findByTriggerEvent("audit", "log-1")).thenReturn(Optional.of(instance("inst-1")));
        FlowExecutionState live = new FlowExecutionState();
        live.setInstanceId("inst-1");
        when(engine.getInstance("inst-1")).thenReturn(Optional.of(live));

        assertSame(live, service.findRunOfEvent("audit", "log-1"));
    }

    @Test
    void anEventNotYetRunFindsNothing() {
        when(instanceService.findByTriggerEvent("audit", "log-2")).thenReturn(Optional.empty());

        assertNull(service.findRunOfEvent("audit", "log-2"));
        verifyNoInteractions(engine);
    }

    @Test
    void anEventWithoutIdentityIsNotDeduplicated() {
        assertNull(service.findRunOfEvent("audit", null));
        verifyNoInteractions(instanceService, engine);
    }

    private void watchExpenseClaims() {
        CompiledFlowDefinition audit = CompiledFlowDefinition.builder()
                .flowId("audit").flowName("audit").bundleId(5L)
                .scenario(FlowScenario.AUTOMATION)
                .trigger(new EntityChangeTrigger("ExpenseClaim", List.of(), null))
                .build();
        when(registry.findMatches(any())).thenReturn(List.of(DefaultFlowTriggerRegistry.TriggerMatch.holding(audit)));
        when(engine.start(any())).thenReturn(new FlowExecutionState());
    }

    @Test
    void aChangeLogStartsTheFlowCarryingItsUuidAndARedeliveryDoesNotStartItAgain() {
        watchExpenseClaims();

        service.fireAsyncOnly(changeOf("7", "log-1"));

        ArgumentCaptor<FlowStartRequest> started = ArgumentCaptor.forClass(FlowStartRequest.class);
        verify(engine).start(started.capture());
        assertEquals("log-1", started.getValue().getTriggerEventId());

        // The redelivery finds the run the first delivery started.
        when(instanceService.findByTriggerEvent("audit", "log-1")).thenReturn(Optional.of(instance("inst-1")));
        when(engine.getInstance("inst-1")).thenReturn(Optional.of(new FlowExecutionState()));
        service.fireAsyncOnly(changeOf("7", "log-1"));

        verify(engine, times(1)).start(any());
    }

    @Test
    void anotherChangeOfTheSameRowStartsItsOwnRun() {
        watchExpenseClaims();
        when(instanceService.findByTriggerEvent("audit", "log-1")).thenReturn(Optional.of(instance("inst-1")));
        when(engine.getInstance("inst-1")).thenReturn(Optional.of(new FlowExecutionState()));

        service.fireAsyncOnly(changeOf("7", "log-2"));

        verify(engine).start(any());
    }
}
