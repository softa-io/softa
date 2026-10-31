package io.softa.starter.flow.runtime.spi;

import java.util.Collection;
import java.util.Map;

import io.softa.framework.base.utils.SFunction;
import io.softa.starter.flow.entity.ApprovalModel;
import io.softa.starter.flow.enums.RecordEffectStatus;

/**
 * The business side of one model's record approvals: implement one per model (a Spring bean) and submit
 * records through {@code FlowClient.submitRecord}. Which flow approves a record is not decided here —
 * each approval flow's submit trigger names the model and, optionally, which of its records the flow takes.
 *
 * <p>The framework keeps the approval state, the same for every model: the record's
 * {@link ApprovalModel#getApprovalStatus() approvalStatus}, its submitter and submission time, the one
 * state machine every record follows — only a draft is edited, submitted or cancelled, a return or a
 * withdrawal makes it a draft again, everything else freezes it — and the write of each result, once. A
 * handler holds what only the business knows: the checks a submission or a cancellation must pass, what
 * its flow routes on, and what follows a submission or a result.
 *
 * <p>Every record has a subject: the employee it is about, whom approvers are found relative to —
 * their supervisor, their department's leader — whoever submits it. It is the model's
 * {@code employeeId} unless {@link #subject()} names another field; either way a many-to-one field onto
 * the employee model, which the boot checks.
 *
 * <p>The approval's progress lives on the flow instance; the record keeps only where it stands.
 *
 * @param <T> the model's entity
 */
public interface ModelApprovalHandler<T extends ApprovalModel> {

    /** The model whose records this handler serves, e.g. {@code LeaveRequest.class}: one handler per model. */
    Class<T> modelClass();

    /**
     * The record's subject field, when it is not {@code employeeId}: a many-to-one field onto the
     * employee model, such as {@code Transfer::getTransfereeId}. Null — the default — means
     * {@code employeeId}. A record whose subject is empty cannot be submitted.
     */
    default SFunction<T, Long> subject() {
        return null;
    }

    /**
     * The business checks a submission must pass: who may submit the record, what it must hold. Throw a
     * {@code BusinessException} to refuse; the submission fails with its message, and the record's approval
     * preview reports it, so a Submit button can say why before anyone presses it.
     * <p>
     * Runs as the submitter, with their own permissions, on the whole record. The framework has already
     * made the checks every model shares: the submitter can read the record, it is a draft, and no approval
     * of it is in progress. Called on every submission and every preview: change nothing here.
     *
     * @param record the record to submit
     */
    default void validate(T record) {}

    /**
     * Who may cancel the draft, beyond seeing it: throw a {@code BusinessException} to refuse, such as an
     * expense claim cancelled only by its claimant. Runs as the applicant, with their own permissions; the
     * framework has already checked they can read the record and that it is a draft. Change nothing here:
     * what a cancellation releases goes in {@link #afterOutcome}.
     *
     * @param record the draft to cancel
     */
    default void validateCancel(T record) {}

    /**
     * The approval instance's title, and the routing variables its flow decides on beyond the record's own
     * fields — values worked out from the record alone, such as an amount in the company's currency. The
     * framework already routes every field of the record a route can compare (its own columns of a plain
     * type, dates as ISO text, under the field's name); a variable here of the same name replaces it.
     * Called right after {@link #validate}, on every submission and every preview, and again after an
     * approver edits the record, whose variables then replace the ones the flow started with: change
     * nothing here.
     *
     * @param record the record to submit
     * @return the title and the variables
     */
    ApprovalSubmission submission(T record);

    /**
     * The record is in approval: run what a submission sets off, such as reserving a leave balance. Runs in
     * the submission's transaction with permission checks skipped, after the framework set the record
     * {@code Pending} and just before its approval starts; throwing refuses the submission.
     *
     * @param record the record, already {@code Pending}
     */
    default void afterSubmitted(T record) {}

    /**
     * The approval reached a result, already written to the record's {@code approvalStatus}: run the
     * follow-up business actions, such as deducting a leave balance or registering a new salary. A return
     * or a withdrawal arrives with the record a {@code Draft} again; a draft its applicant cancelled arrives
     * as {@code CANCELLED} with no instance. Runs inside the action that produced the result — the same
     * transaction, with permission checks skipped — so throwing vetoes the action, which rolls back and
     * reports the error to whoever acted.
     * <p>
     * Called once per result: the framework writes a result only onto a record still in approval, and
     * calls this only when it did. Keep to writes in this database that belong with the result. Anything
     * that leaves it — a payment, an external system, a message — goes after the commit; otherwise a
     * failure there holds up the approval, and a rollback does not undo it.
     *
     * @param record  the record, with its new {@code approvalStatus}
     * @param outcome how the approval ended, and who ended it
     */
    default void afterOutcome(T record, ApprovalOutcome outcome) {}

    /**
     * Where each of these approved records' change stands — applied, failed to apply, or still scheduled — for a
     * model whose approval takes effect later, read from the records themselves, never guessed from dates. The
     * monitoring console shows it beside each approved instance; nothing filters on it. Keys are the row ids as
     * given; a record with nothing to say is left out. Empty — the default — for a model whose approval takes
     * effect at once. Called past the caller's permissions, one call per model on a page.
     */
    default Map<String, RecordEffectStatus> effectStatuses(Collection<String> rowIds) {
        return Map.of();
    }
}
