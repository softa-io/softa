package io.softa.starter.flow.runtime.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.state.FlowWaitToken;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class FlowExecutionOrchestratorResumeTest {

    private final FlowExecutionOrchestrator orchestrator =
            new FlowExecutionOrchestrator(null, null, null, null, null, null, null, null, null);

    @Test
    void resumeOfTerminalInstanceIsNoop() {
        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("inst-1")
                .status(FlowExecutionStatus.COMPLETED)
                .completedNodeIds(new ArrayList<>(List.of("async-1")))
                .waitTokens(new ArrayList<>())
                .variables(Map.of())
                .build();

        // A redelivered callback for an already-terminal instance is ignored.
        assertDoesNotThrow(() -> orchestrator.resumeFromSuspendedNode(state, null, "async-1", Map.of()));
    }

    @Test
    void resumeForNodeWithoutLiveTokenIsNoop() {
        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("inst-1")
                .status(FlowExecutionStatus.WAITING)
                .waitTokens(new ArrayList<>(List.of(
                        FlowWaitToken.builder().nodeId("async-2").build())))
                .completedNodeIds(new ArrayList<>())
                .variables(Map.of())
                .build();

        // Resuming a node whose token was already consumed (or never existed) is an idempotent
        // no-op — it must not throw, and must leave the sibling branch's live wait untouched.
        assertDoesNotThrow(() -> orchestrator.resumeFromSuspendedNode(state, null, "async-1", Map.of()));
        assertNotNull(state.findWaitToken("async-2"));
    }
}
