package io.softa.starter.flow.runtime.engine;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.store.FlowInstanceStore;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Failure semantics of the timer/async resume paths, mirroring {@code start}: the FAILED
 * record is persisted in its own transaction and the exception rethrown so the failed
 * attempt's partial business writes roll back instead of committing alongside the failure.
 */
class FlowRuntimeFacadeResumeTest {

    private final FlowActionContextService contextService = mock(FlowActionContextService.class);
    private final FlowExecutionOrchestrator orchestrator = mock(FlowExecutionOrchestrator.class);
    private final FlowInstanceStore instanceStore = mock(FlowInstanceStore.class);

    private FlowRuntimeFacade facade() {
        return new FlowRuntimeFacade(contextService, orchestrator, instanceStore, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private FlowExecutionState waitingState() {
        return FlowExecutionState.builder()
                .instanceId("inst-1")
                .bundleId(7L)
                .flowCode("leave")
                .build();
    }

    @Test
    void resumeFailureRecordsFailedInNewTransactionAndRethrows() {
        FlowExecutionState state = waitingState();
        when(instanceStore.get("inst-1")).thenReturn(Optional.of(state));
        when(contextService.resolveDefinition(7L))
                .thenReturn(CompiledFlowDefinition.builder().flowCode("leave").build());
        doThrow(new IllegalStateException("node blew up"))
                .when(orchestrator).resumeFromSuspendedNode(eq(state), any(), eq("async-1"), anyMap());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> facade().resumeAsyncTask("inst-1", "async-1", Map.of()));

        assertEquals("node blew up", thrown.getMessage());
        verify(orchestrator).failState(eq(state), eq("leave"), isNull(), eq("node blew up"));
        verify(contextService).persistStateInNewTransaction(state);
        verify(contextService, never()).persistState(state);
    }

    @Test
    void successfulResumePersistsInCallerTransaction() {
        FlowExecutionState state = waitingState();
        when(instanceStore.get("inst-1")).thenReturn(Optional.of(state));
        when(contextService.resolveDefinition(7L))
                .thenReturn(CompiledFlowDefinition.builder().flowCode("leave").build());

        FlowExecutionState result = facade().resumeTimer("inst-1", "timer-1");

        assertSame(state, result);
        verify(contextService).persistState(state);
        verify(contextService, never()).persistStateInNewTransaction(any());
    }
}
