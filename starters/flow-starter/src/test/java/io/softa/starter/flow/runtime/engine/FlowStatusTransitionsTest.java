package io.softa.starter.flow.runtime.engine;

import org.junit.jupiter.api.Test;

import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;

import static io.softa.starter.flow.runtime.state.FlowExecutionStatus.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the legal {@link FlowExecutionStatus} lifecycle graph enforced by
 * {@link FlowStatusTransitions} (R9). Each allowed edge corresponds to a real
 * {@code transitionTo} site in the orchestrator / action handlers.
 */
class FlowStatusTransitionsTest {

    @Test
    void selfTransitionAlwaysAllowed() {
        for (FlowExecutionStatus s : FlowExecutionStatus.values()) {
            assertTrue(FlowStatusTransitions.isAllowed(s, s), s + " -> " + s + " should be a no-op");
        }
    }

    @Test
    void anyStateMayFail() {
        for (FlowExecutionStatus s : FlowExecutionStatus.values()) {
            assertTrue(FlowStatusTransitions.isAllowed(s, FAILED), s + " -> FAILED should be allowed");
        }
    }

    @Test
    void runningSuspendsCompletesOrCancels() {
        assertTrue(FlowStatusTransitions.isAllowed(RUNNING, WAITING));
        assertTrue(FlowStatusTransitions.isAllowed(RUNNING, COMPLETED));
        assertTrue(FlowStatusTransitions.isAllowed(RUNNING, CANCELLED));
    }

    @Test
    void waitingDecisions() {
        // A single WAITING status covers approval / timer / async waits (possibly at once).
        assertTrue(FlowStatusTransitions.isAllowed(WAITING, RUNNING));   // approve / resume
        assertTrue(FlowStatusTransitions.isAllowed(WAITING, REJECTED));
        assertTrue(FlowStatusTransitions.isAllowed(WAITING, RETURNED));
        assertTrue(FlowStatusTransitions.isAllowed(WAITING, WITHDRAWN));
    }

    @Test
    void resumeAndResubmitPaths() {
        assertTrue(FlowStatusTransitions.isAllowed(WAITING, RUNNING));  // timer / async / approve resume
        assertTrue(FlowStatusTransitions.isAllowed(RETURNED, WAITING)); // resubmit
    }

    @Test
    void terminalStatesHaveNoBusinessTransitions() {
        assertFalse(FlowStatusTransitions.isAllowed(COMPLETED, RUNNING));
        assertFalse(FlowStatusTransitions.isAllowed(REJECTED, WAITING));
        assertFalse(FlowStatusTransitions.isAllowed(WITHDRAWN, RUNNING));
        assertFalse(FlowStatusTransitions.isAllowed(FAILED, RUNNING));
    }

    @Test
    void resumeCannotSkipDirectlyToCompleted() {
        // a resumed wait must go WAITING → RUNNING → COMPLETED, never directly
        assertFalse(FlowStatusTransitions.isAllowed(WAITING, COMPLETED));
    }

    @Test
    void validateThrowsOnIllegalTransition() {
        assertThrows(FlowRuntimeException.class, () -> FlowStatusTransitions.validate(COMPLETED, RUNNING));
        assertDoesNotThrow(() -> FlowStatusTransitions.validate(RUNNING, COMPLETED));
    }
}
