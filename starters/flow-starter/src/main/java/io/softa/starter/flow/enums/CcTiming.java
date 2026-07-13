package io.softa.starter.flow.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import io.softa.framework.base.annotation.OptionSet;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Timing for automatic CC notifications.
 */
@Getter
@AllArgsConstructor
@OptionSet
public enum CcTiming {
    ON_SUBMIT("OnSubmit"),
    ON_APPROVE("OnApprove"),
    ON_REJECT("OnReject"),
    ON_COMPLETE("OnComplete"),
    ;

    @JsonValue
    private final String type;
}
