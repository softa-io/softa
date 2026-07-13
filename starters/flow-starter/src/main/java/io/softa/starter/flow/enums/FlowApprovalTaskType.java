package io.softa.starter.flow.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import io.softa.framework.base.annotation.OptionItem;
import io.softa.framework.base.annotation.OptionSet;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Persistent approval task type for flow runtime projections.
 */
@Getter
@AllArgsConstructor
@OptionSet
public enum FlowApprovalTaskType {
    @OptionItem(label = "Approval Task")
    APPROVAL("Approval"),
    @OptionItem(label = "Cc Task")
    CC("Cc"),
    @OptionItem(label = "Read Task")
    READ("Read"),
    ;

    @JsonValue
    private final String type;
}
