package io.softa.starter.flow.runtime.state;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Runtime status of a flow execution instance.
 */
@Getter
@AllArgsConstructor
public enum FlowExecutionStatus {
    /** Instance created but not yet started (async-launch scenario). */
    PENDING("Pending"),
    RUNNING("Running"),
    /**
     * Parked on one or more waits — a pending approval and/or timer / async
     * callback. The specific reasons live in {@link FlowExecutionState#getPendingApprovals()}
     * and {@link FlowExecutionState#getWaitTokens()}, so a single status covers
     * parallel branches suspended for different reasons at once.
     */
    WAITING("Waiting"),
    REJECTED("Rejected"),
    RETURNED("Returned"),
    WITHDRAWN("Withdrawn"),
    /** Externally force-cancelled (distinct from business-level Withdrawn). */
    CANCELLED("Cancelled"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    ;

    @JsonValue
    private final String type;

    /** Terminal states have no outgoing business transition (the flow has ended). */
    public boolean isTerminal() {
        return switch (this) {
            case REJECTED, WITHDRAWN, CANCELLED, COMPLETED, FAILED -> true;
            default -> false;
        };
    }
}

