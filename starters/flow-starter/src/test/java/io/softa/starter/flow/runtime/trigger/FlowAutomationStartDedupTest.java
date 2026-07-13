package io.softa.starter.flow.runtime.trigger;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.runtime.engine.FlowRuntimeEngine;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.service.FlowInstanceService;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Start-dedup: a change-log redelivery must not open a second ACTIVE instance for the
 * same business row + flow. A re-submitted row whose prior instance is terminal may start a new one.
 */
class FlowAutomationStartDedupTest {

    private final FlowRuntimeEngine engine = mock(FlowRuntimeEngine.class);
    private final FlowInstanceService instanceService = mock(FlowInstanceService.class);
    private final FlowAutomationService service = new FlowAutomationService(null, engine);

    @BeforeEach
    void wireInstanceService() {
        ReflectionTestUtils.setField(service, "instanceService", instanceService);
    }

    private static FlowInstance instance(String id, String flowCode, FlowExecutionStatus status) {
        FlowInstance i = new FlowInstance();
        i.setInstanceId(id);
        i.setFlowCode(flowCode);
        i.setModelName("LeaveRequest");
        i.setRowId("7");
        i.setStatus(status);
        return i;
    }

    @Test
    void returnsTheActiveInstanceForTheSameRowAndFlow() {
        when(instanceService.findByModelNameAndRowId("LeaveRequest", "7"))
                .thenReturn(List.of(instance("inst-1", "leave", FlowExecutionStatus.WAITING)));
        FlowExecutionState live = new FlowExecutionState();
        live.setInstanceId("inst-1");
        when(engine.getInstance("inst-1")).thenReturn(Optional.of(live));

        assertSame(live, service.findActiveInstanceState("leave", "LeaveRequest", "7"));
    }

    @Test
    void ignoresTerminalInstancesSoAResubmitCanStartAfresh() {
        when(instanceService.findByModelNameAndRowId("LeaveRequest", "7"))
                .thenReturn(List.of(instance("old", "leave", FlowExecutionStatus.COMPLETED)));

        assertNull(service.findActiveInstanceState("leave", "LeaveRequest", "7"));
        verifyNoInteractions(engine);
    }

    @Test
    void ignoresActiveInstancesOfADifferentFlow() {
        when(instanceService.findByModelNameAndRowId("LeaveRequest", "7"))
                .thenReturn(List.of(instance("x", "other-flow", FlowExecutionStatus.WAITING)));

        assertNull(service.findActiveInstanceState("leave", "LeaveRequest", "7"));
        verifyNoInteractions(engine);
    }

    @Test
    void noBusinessRowMeansNoDedup() {
        assertNull(service.findActiveInstanceState("leave", null, null));
        verifyNoInteractions(instanceService, engine);
    }
}
