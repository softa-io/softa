package io.softa.starter.flow.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Strategy applied when an approval task times out.
 * Approval nodes: {@code config.timeout.timeoutStrategy} on {@code ApprovalNodeConfig}.
 * HumanTask nodes: {@code config.timeoutStrategy} on {@link io.softa.starter.flow.runtime.nodeconfig.HumanTaskNodeConfig}.
 */
@Getter
@AllArgsConstructor
public enum ApprovalTimeoutStrategy {
    /** Just send reminders, no auto-action (default). */
    REMIND("Remind"),
    /** Auto-approve the task on timeout. */
    AUTO_APPROVE("AutoApprove"),
    /** Auto-reject the task on timeout. */
    AUTO_REJECT("AutoReject"),
    /** Escalate to a designated user on timeout. */
    ESCALATE("Escalate"),
    ;

    @JsonValue
    private final String type;

    @JsonCreator
    public static ApprovalTimeoutStrategy fromValue(String value) {
        if (value == null) {
            return REMIND;
        }
        for (ApprovalTimeoutStrategy s : values()) {
            if (s.type.equalsIgnoreCase(value) || s.name().equalsIgnoreCase(value)) {
                return s;
            }
        }
        return REMIND;
    }
}

