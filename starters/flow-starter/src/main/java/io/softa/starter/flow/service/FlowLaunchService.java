package io.softa.starter.flow.service;

import java.util.Map;

import io.softa.starter.flow.dto.FlowApprovalPreview;
import io.softa.starter.flow.runtime.engine.FlowLaunchResponse;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.api.PublishAndStartRequest;
import io.softa.starter.flow.runtime.state.FlowExecutionState;

/**
 * Launch facade: the editor's publish-and-start and debug runs, and the submission (and
 * withdrawal) of business records for approval, with its preview.
 */
public interface FlowLaunchService {

    FlowLaunchResponse publishAndStart(PublishAndStartRequest request);

    /**
     * Editor test-run of the CURRENT draft canvas: compiles the saved draft
     * (compile errors surface as diagnostics), registers a debug bundle
     * (never active, hidden from revision lists, purged after retention),
     * and executes it through the normal engine. NOT a sandbox — node side
     * effects (record writes, notifications) are real.
     */
    FlowLaunchResponse debugRunDraft(String flowId, FlowStartRequest request);

    /**
     * Submits a business record for approval, in the caller's transaction: the submitter must reach the
     * record within the range their roles may edit it in — submitting writes it, so being able to view it is
     * not enough — and it must be a draft; its model's
     * {@code ModelApprovalHandler} checks it and supplies the title and routing variables, the flow
     * is chosen by the flows' submit triggers (exactly one active flow of the tenant must match the
     * model and the record), the record becomes {@code Pending} and the record's approval starts.
     * Every submission starts a new approval from the beginning — a record returned to its
     * submitter, a draft again, included — and a record has at most one approval in progress.
     *
     * @param modelName   the record's model
     * @param rowId       the record
     * @param initiatorId the submitting user
     * @return the new approval instance's state
     */
    FlowExecutionState submitRecord(String modelName, String rowId, String initiatorId);

    /**
     * What submitting the record would do now, worked out without submitting it: the same checks and
     * the same choice of flow as {@link #submitRecord}, then the approval path that flow would take —
     * its steps in order, each with the approvers who would be asked or why it passes on its own. For a
     * record whose approval is in progress, the rest of that approval's path ({@code InProgress}): the
     * step it waits on now, then those after it, from the instance's own variables.
     * The handler's {@code validate} runs with the requester's own permissions; {@code afterSubmitted}
     * is never called, and nothing is started or written. A refusal, no applicable flow or several are
     * reported, not thrown.
     *
     * @param modelName   the record's model
     * @param rowId       the record
     * @param requesterId the user who would submit it
     * @return the preview, users named
     * @throws io.softa.starter.flow.runtime.exception.FlowAuthorizationException when the requester
     *         cannot read the record with their own permissions
     */
    FlowApprovalPreview previewRecord(String modelName, String rowId, String requesterId);

    /**
     * What submitting a record not saved yet would do now, as {@link #previewRecord} answers for a saved one: its
     * field values, in the form they are stored (ids, codes), stand for the row — the subject among them. For the
     * application's own pages, which decide who may ask: no endpoint serves it.
     */
    FlowApprovalPreview previewValues(String modelName, Map<String, Object> values, String requesterId);

    /**
     * Withdraws a record's approval while it waits for approvers, in the caller's transaction. The
     * engine checks that the actor is the submitter and that the flow allows withdrawal; the record
     * is then written back as a draft like any other withdrawal and handed to its handler. When the
     * record's approval failed, its submitter takes the record back the same way — the handler is told
     * it was withdrawn — while the failed instance stays as it is for flow monitoring.
     *
     * @param modelName the record's model
     * @param rowId     the record
     * @param actorId   the withdrawing user
     * @param comment   optional reason
     * @return the withdrawn (or failed) approval instance's state
     * @throws io.softa.framework.base.exception.BusinessException when the record has no approval in
     *         progress
     */
    FlowExecutionState withdrawRecord(String modelName, String rowId, String actorId, String comment);

    /**
     * Cancels a draft for good, in the caller's transaction: the actor must reach the record within the
     * range their roles may edit it in, it must be a draft, and its handler's {@code validateCancel} must pass. The record
     * becomes {@code Cancelled} and its handler is handed a {@code CANCELLED} result with no instance.
     *
     * @param modelName the record's model
     * @param rowId     the record
     * @param actorId   the cancelling applicant
     * @throws io.softa.framework.base.exception.BusinessException when the record is not a draft, or its
     *         handler refuses
     */
    void cancelRecord(String modelName, String rowId, String actorId);
}
