package io.softa.starter.flow.runtime.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.state.FlowWaitToken;
import io.softa.starter.flow.runtime.state.WaitType;

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
                .completedNodeIds(new ArrayList<>(List.of("timer")))
                .waitTokens(new ArrayList<>())
                .variables(Map.of())
                .build();

        // A redelivered timer/async message for an already-terminal instance is ignored.
        assertDoesNotThrow(() -> orchestrator.resumeFromSuspendedNode(state, null, "timer", Map.of()));
    }

    @Test
    void resumeForNodeWithoutLiveTokenIsNoop() {
        FlowExecutionState state = FlowExecutionState.builder()
                .instanceId("inst-1")
                .status(FlowExecutionStatus.WAITING)
                .waitTokens(new ArrayList<>(List.of(
                        FlowWaitToken.builder().nodeId("timer-2").type(WaitType.TIMER).build())))
                .completedNodeIds(new ArrayList<>())
                .variables(Map.of())
                .build();

        // Resuming a node whose token was already consumed (or never existed) is an idempotent
        // no-op — it must not throw, and must leave the sibling branch's live wait untouched.
        assertDoesNotThrow(() -> orchestrator.resumeFromSuspendedNode(state, null, "timer-1", Map.of()));
        assertNotNull(state.findWaitToken("timer-2"));
    }
}
