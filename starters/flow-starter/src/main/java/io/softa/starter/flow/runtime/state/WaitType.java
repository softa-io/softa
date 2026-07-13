package io.softa.starter.flow.runtime.state;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Why a {@link FlowWaitToken} is parked on a suspended branch.
 * <p>
 * Approval waits are modelled separately by {@link FlowExecutionState#getPendingApprovals()};
 * only the non-approval scalar waits (timer / async callback) are represented as wait tokens.
 */
@Getter
@AllArgsConstructor
public enum WaitType {
    /** Suspended at a Timer node; resumed when the scheduled time is reached. */
    TIMER("Timer"),
    /** Suspended at an AsyncTask node; resumed by an external callback. */
    ASYNC("Async"),
    ;

    @JsonValue
    private final String type;
}
