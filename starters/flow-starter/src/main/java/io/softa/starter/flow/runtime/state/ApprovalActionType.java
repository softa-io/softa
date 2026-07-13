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
    ADD_SIGN("AddSign"),
    CC("Cc"),
    READ("Read"),
    RETURN("Return"),
    RESUBMIT("Resubmit"),
    WITHDRAW("Withdraw"),
    URGE("Urge"),
    COMMENT("Comment"),
    ;

    @JsonValue
    private final String type;
}

