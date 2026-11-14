package io.softa.starter.flow.runtime.spi;

import io.softa.starter.flow.enums.ApprovalOutcomeType;

/**
 * The result of a record's approval, handed to {@link ModelApprovalHandler#afterOutcome}: how its approval
 * ended, or that its applicant cancelled the draft. Who decided it and what they said are in the approval's
 * history.
 *
 * @param type       how the approval ended
 * @param instanceId the approval instance; null for a draft cancelled before any approval of it ran
 */
public record ApprovalOutcome(ApprovalOutcomeType type, String instanceId) {
}
