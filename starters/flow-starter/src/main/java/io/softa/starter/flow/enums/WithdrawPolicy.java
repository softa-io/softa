package io.softa.starter.flow.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Until when the initiator may withdraw an approval in progress — taking the submission back, the record a
 * draft again. A flow setting, like {@link SelfApprovalStrategy}; only an approval has one.
 *
 * <p>Whatever the policy, only the initiator withdraws, and only while the approval waits on its approvers.
 */
@Getter
@AllArgsConstructor
public enum WithdrawPolicy {

    /** The initiator may not withdraw. This is the default when unconfigured. */
    NEVER("Never"),

    /**
     * Until an approver first acts on it: approves, rejects, returns, transfers or delegates at any of
     * its steps. An automatic approval — the submitter's own, a repeated approver's, a step
     * nobody resolved to — is not an approver acting.
     */
    BEFORE_FIRST_ACTION("BeforeFirstAction"),

    /** Until the approval ends, however far it got. */
    UNTIL_COMPLETED("UntilCompleted");

    @JsonValue
    private final String type;
}
