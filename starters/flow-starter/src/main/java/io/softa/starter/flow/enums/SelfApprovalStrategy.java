package io.softa.starter.flow.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * What an approval step does when the record's submitter is among its approvers ("提交人是审批人时"). A flow
 * setting, like {@link ApproverDedupStrategy}. Only the submitter: the employee the record is about, when
 * someone else submitted it, is always asked like any other approver — approving for them would decide their
 * own matter in their name.
 *
 * <p>Either way it is an approval business rule, decided per flow: nobody is quietly dropped from a step,
 * and an automatic approval writes a real {@code AUTO_APPROVE} record, so the approval history shows who
 * passed the step and why.
 */
@Getter
@AllArgsConstructor
public enum SelfApprovalStrategy {

    /**
     * The submitter counts as having approved: submitting the record was agreeing to it, so they are not asked
     * again. Whether the step then passes is the step's own approval mode — an any-one step passes, an
     * all-approve step still waits for the others. This is the default when unconfigured.
     */
    AUTO_APPROVE("AutoApprove"),

    /** No exception: the submitter is asked like any other approver, and approves or rejects the record. */
    REQUIRE_APPROVAL("RequireApproval");

    @JsonValue
    private final String type;
}
