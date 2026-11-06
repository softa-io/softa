package io.softa.starter.flow.runtime.state;

import com.fasterxml.jackson.annotation.JsonValue;
import io.softa.framework.base.annotation.OptionSet;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Unified approval lifecycle actions recorded in runtime audit history.
 */
@Getter
@AllArgsConstructor
@OptionSet
public enum ApprovalActionType {
    APPROVE("Approve"),
    AUTO_APPROVE("AutoApprove"),
    REJECT("Reject"),
    TRANSFER("Transfer"),
    DELEGATE("Delegate"),
    /**
     * A signer added before or after an approver. The action is gone: nothing records it any more. Kept because
     * history recorded before still holds it, and a stored code the enum no longer has cannot be read.
     */
    ADD_SIGN("AddSign"),
    CC("Cc"),
    READ("Read"),
    RETURN("Return"),
    WITHDRAW("Withdraw"),
    URGE("Urge"),
    COMMENT("Comment"),
    /** An administrator ended the approval: the instance is cancelled and its record with it. */
    TERMINATE("Terminate"),
    /** An administrator ran the steps after an approval again, after they had failed: the approval goes on. */
    RETRY("Retry"),
    ;

    @JsonValue
    private final String type;
}

