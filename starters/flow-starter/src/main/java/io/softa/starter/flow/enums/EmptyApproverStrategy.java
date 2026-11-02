package io.softa.starter.flow.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Strategy applied when an approval node resolves to zero eligible approvers.
 * Configurable per-node via {@code config.emptyApproverStrategy}.
 */
@Getter
@AllArgsConstructor
public enum EmptyApproverStrategy {
    /** Throw an error and halt the flow (default). */
    ERROR("Error"),
    /** Skip the approval node and continue execution. */
    SKIP("Skip"),
    /** Auto-approve the node and continue execution. */
    AUTO_APPROVE("AutoApprove"),
    /**
     * Hand the step to the holders of the node's {@code emptyApproverRoleIds} / {@code emptyApproverRoleCodes} who can
     * act, the submitter and the employee the record is about left out: any one of them approves or rejects it. With
     * nobody left, as {@link #ERROR}.
     */
    ASSIGN_TO_ROLE("AssignToRole"),
    ;

    @JsonValue
    private final String type;
}

