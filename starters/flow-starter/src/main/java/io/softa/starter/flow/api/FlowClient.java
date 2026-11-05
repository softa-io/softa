package io.softa.starter.flow.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.softa.starter.flow.design.FlowNodeDescriptor;
import io.softa.starter.flow.dto.FlowApprovalPreview;
import io.softa.starter.flow.dto.FlowApprovalRecordView;
import io.softa.starter.flow.dto.FlowApprovalTaskView;
import io.softa.starter.flow.dto.FlowInboxView;
import io.softa.starter.flow.dto.FlowRecordApprovalsView;
import io.softa.starter.flow.dto.FlowSentCcView;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.enums.FormFieldPermission;
import io.softa.starter.flow.runtime.api.*;
import io.softa.starter.flow.runtime.state.FlowExecutionState;

/**
 * Single in-process port (host-facing facade) for a monolith that embeds the flow engine.
 * It mirrors {@code FlowRuntimeEngine}'s lifecycle/action methods plus the host-relevant
 * query and form-permission operations otherwise spread across the engine's controllers.
 * <p>
 * Every method exchanges only SDK DTOs. {@code actorId} / {@code initiatorId} / {@code tenantId}
 * are NEVER call parameters — they are resolved server-side from the context.
 * <p>
 * This is NOT a remote contract: in a microservice topology, actions go to the engine's
 * {@code FlowInstanceController} REST API ({@code /flow/instances}) and flow-data queries go
 * through the framework model RPC — a linking application neither implements nor proxies
 * {@code FlowClient}.
 */
public interface FlowClient {

    // lifecycle / launch
    /** Start an Automation flow whose trigger is {@code Api}; any other flow is refused. */
    FlowExecutionState start(FlowStartRequest request);

    /** Publish the definition, then start it as {@link #start} does — it must be an Automation flow with an Api trigger. */
    FlowExecutionState publishAndStart(PublishAndStartRequest request);

    /**
     * Submits a business record for approval as the current user: the record's
     * {@code ModelApprovalHandler} checks it, the flow is chosen by the flows' submit triggers, and
     * a new approval starts from the beginning — after a return too. Joins the caller's
     * transaction — call it from the business module's own submit endpoint.
     */
    FlowExecutionState submitRecord(String modelName, String rowId);

    /**
     * What submitting the record as the current user would do now, without submitting it — the same
     * answer as {@code GET /flow/instances/preview}, under the same check: the current user can read
     * the record with their own permissions. Nothing is started or written. A refusal by the
     * handler's {@code validate} is reported rather than thrown, so call it outside a transaction: a
     * refusal thrown through a transactional method marks the transaction it joins rollback-only.
     */
    FlowApprovalPreview previewRecord(String modelName, String rowId);

    /**
     * What submitting a record not saved yet as the current user would do now — {@link #previewRecord} on its field
     * values, in the form they are stored (ids, codes), the subject among them, instead of a saved row: a record its
     * application creates as it is submitted (a pay run's approval request) previews its route before it exists.
     * Nothing is started or written; the caller decides who may ask. Call it outside a transaction, as
     * {@link #previewRecord}.
     */
    FlowApprovalPreview previewValues(String modelName, Map<String, Object> values);

    /**
     * Withdraws the record from approval as the current user, its submitter — or, when its approval
     * failed, takes the record back. The record is a draft again. Refused when the record has no
     * approval in progress, or its flow does not let its submitter withdraw. Joins the caller's
     * transaction.
     */
    FlowExecutionState withdrawRecord(String modelName, String rowId, String comment);

    /**
     * Cancels the draft for good as the current user, its applicant: refused unless the record is a
     * draft within the range the user may edit it in and its handler's {@code validateCancel} passes. Joins
     * the caller's transaction.
     */
    void cancelRecord(String modelName, String rowId);

    /**
     * The record's approval history — its approval instances newest first, with steps, approvers
     * and actions, every user named, each saying whether the current user may open it — the same
     * answer as {@code GET /flow/instances/by-record}, under the same check: the current user can
     * read the record or took part in its approvals.
     */
    FlowRecordApprovalsView recordApprovals(String modelName, String rowId);

    /**
     * Current state of an instance, viewer-checked: the resolved current user must be the
     * initiator or a participant — the same authorization the REST detail endpoints apply.
     * Trace history is not hydrated.
     */
    Optional<FlowExecutionState> getInstance(String instanceId);

    /**
     * The current user's own instances, newest first: the first page (up to 50) of the summary
     * search behind {@code POST /flow/instances/search}, filtered by the query's flow and status.
     */
    List<FlowInstanceView> myInstances(MineQuery query);

    // approval actions (1:1 with FlowRuntimeEngine)
    FlowExecutionState approve(FlowApproveRequest request);

    FlowExecutionState reject(FlowRejectRequest request);

    FlowExecutionState transfer(FlowTransferRequest request);

    FlowExecutionState delegate(FlowDelegateRequest request);

    FlowExecutionState addSignBefore(FlowAddSignBeforeRequest request);

    FlowExecutionState addSignAfter(FlowAddSignAfterRequest request);

    FlowExecutionState cc(FlowCcRequest request);

    FlowExecutionState readCc(FlowCcReadRequest request);

    FlowExecutionState returnApproval(FlowReturnRequest request);

    FlowExecutionState withdraw(FlowWithdrawRequest request);

    FlowExecutionState urge(FlowUrgeRequest request);

    FlowExecutionState addComment(FlowCommentRequest request);

    // form / field-permission
    /**
     * Field-level form permissions of one approval node, viewer-checked like
     * {@link #getInstance}. Empty when the instance, its bundle or the node cannot be found.
     */
    Map<String, FormFieldPermission> getFormPermissions(String instanceId, String nodeId);

    // async / timer resume (engine-internal callers; exposed for custom TaskExecutors)
    FlowExecutionState resumeAsyncTask(String instanceId, String nodeId, Map<String, Object> callbackOutputs);

    FlowExecutionState resumeTimer(String instanceId, String nodeId);

    // inbox / task / record queries
    FlowInboxView inbox(InboxQuery query);

    List<FlowApprovalTaskView> pendingTasks(TaskQuery query);

    List<FlowApprovalTaskView> completedTasks(TaskQuery query);

    List<FlowApprovalTaskView> ccTasks(CcTaskQuery query);

    List<FlowApprovalTaskView> tasksByInstance(String instanceId);

    List<FlowApprovalRecordView> recordsByInstance(String instanceId);

    List<FlowApprovalRecordView> approvalHistory(TaskQuery query);

    List<FlowSentCcView> sentCcHistory(CcTaskQuery query);

    // palette (read-only descriptor list; live-bean enumeration stays engine-side)
    List<FlowNodeDescriptor> nodeDescriptors(FlowScenario scenario);
}
