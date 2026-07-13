package io.softa.starter.flow.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Per-node run state in an execution overlay — what the editor paints on a node.
 */
@Getter
@AllArgsConstructor
public enum FlowNodeRunStatus {
    COMPLETED("Completed"),
    WAITING_APPROVAL("WaitingApproval"),
    WAITING_TIMER("WaitingTimer"),
    WAITING_ASYNC("WaitingAsync"),
    FAILED("Failed");

    @JsonValue
    private final String type;
}
