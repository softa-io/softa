package io.softa.starter.flow.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Approver consolidation policy ("审批人去重") for a flow: when the same actor is an approver on
 * more than one node of the same instance, may a later node auto-approve on that actor's behalf?
 *
 * <p>This is an approval business rule (it produces a real {@code AUTO_APPROVE} audit record),
 * distinct from action idempotency (which dedups retried / redelivered physical requests).
 */
@Getter
@AllArgsConstructor
public enum ApproverDedupStrategy {

    /** No consolidation — the actor must approve at every node they appear on. */
    NONE("None"),

    /** Auto-approve only when the actor approved the immediately preceding approval node. */
    CONTIGUOUS("Contiguous"),

    /**
     * Auto-approve when the actor approved any earlier node of the same instance, restricted to the
     * current resubmission cycle (approvals recorded before the last return/resubmit are discounted,
     * because the document may have changed since). This is the default when unconfigured.
     */
    GLOBAL("Global");

    @JsonValue
    private final String type;

    @JsonCreator
    public static ApproverDedupStrategy fromValue(String value) {
        for (ApproverDedupStrategy s : values()) {
            if (s.type.equalsIgnoreCase(value) || s.name().equalsIgnoreCase(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unsupported approver dedup strategy: " + value);
    }
}
