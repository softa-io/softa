package io.softa.starter.flow.service;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import io.softa.framework.orm.domain.Page;
import io.softa.starter.flow.dto.FlowInstanceRecordView;
import io.softa.starter.flow.dto.FlowInstanceSearchRequest;
import io.softa.starter.flow.dto.FlowRecordApprovalsView;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;

/**
 * Service interface for persistent flow instance operations.
 * <p>
 * Every method is abstract on purpose: core capabilities (start-dedup,
 * monitoring) depend on these finders, so a replacement implementation that misses one
 * must fail to compile instead of silently returning empty results.
 */
public interface FlowInstanceService {

    /**
     * Save a flow instance: insert it when it has no id yet, else update its row by id and version — the fields the
     * engine's state holds ({@code FlowExecutionStateMapper.STATE_FIELDS}), nulls included, and no other.
     *
     * @param instance the instance entity to persist
     * @return the saved entity
     */
    FlowInstance saveInstance(FlowInstance instance);

    /**
     * Find a flow instance by its runtime instance id.
     *
     * @param instanceId the runtime instance UUID
     * @return the instance if found
     */
    Optional<FlowInstance> findByInstanceId(String instanceId);

    /**
     * Batch finder by runtime instance ids — the one-query backbone of task
     * projection enrichment. Missing ids are simply absent from the result.
     * The summary columns only: no state JSON (see {@link #findStatesByInstanceIds}).
     */
    List<FlowInstance> findByInstanceIds(Collection<String> instanceIds);

    /**
     * Batch finder by runtime instance ids, each row whole — its state JSON (pending approvals, variables)
     * included: what an execution state is built from. Missing ids are simply absent from the result.
     */
    List<FlowInstance> findStatesByInstanceIds(Collection<String> instanceIds);

    /**
     * Find all instances by execution status.
     *
     * @param status the execution status
     * @return list of matching instances
     */
    List<FlowInstance> findByStatus(FlowExecutionStatus status);

    /**
     * Find all instances by related model name and row id.
     *
     * @param modelName the model name
     * @param rowId     the row data id
     * @return list of matching instances
     */
    List<FlowInstance> findByModelNameAndRowId(String modelName, String rowId);

    /**
     * A record's latest approval, whole — the automation runs on the record left out — or none: one row read, newest
     * first.
     */
    Optional<FlowInstance> findLatestRecordApproval(String modelName, String rowId);

    /**
     * Find the run a trigger event started for a flow — an event starts a flow at most once.
     *
     * @param flowId  the flow id
     * @param eventId the trigger event's identity
     * @return the instance, if the event already started the flow
     */
    Optional<FlowInstance> findByTriggerEvent(String flowId, String eventId);

    /**
     * How many of the flow's instances have not ended — pending, running or waiting, a record's approval in progress
     * among them — whoever may see them.
     */
    long countUnfinished(String flowId);

    /** The revisions (bundle ids) the current tenant's unfinished instances run on, whoever may see them. */
    Set<Long> unfinishedBundleIds();

    /**
     * Paged summary search shared by the caller's "my instances" read
     * ({@code POST /flow/instances/search}) and the cross-initiator monitor endpoint. Builds the summary projection (heavy JSON
     * state columns excluded) and the filter set from the request, newest first; the
     * request's {@code filters} are ANDed onto that set and its {@code orders} replace the
     * default order. Both may name only fields {@code FlowInstance} stores, else 400.
     *
     * @param request           search filters and paging
     * @param forcedInitiatorId when non-null, pins the search to that initiator's record approvals — the
     *                          caller-facing "my instances" contract, which the request's filters only narrow;
     *                          when {@code null} every instance is searched (monitor views)
     * @return one page of instance summary rows
     */
    Page<FlowInstance> searchSummaries(FlowInstanceSearchRequest request, String forcedInitiatorId);

    /**
     * Marks every approval of a record superseded, read and written past the caller's data
     * permissions — engine bookkeeping: a new submission of the record replaces them, or the draft is
     * cancelled for good, so a returned one no longer waits for the record to be edited.
     */
    void markSuperseded(String modelName, String rowId);

    /**
     * A business record's approval, for every page that shows it: each submission of the record — the approval
     * instance it started — newest first, with its approval steps: those it passed, those it waits on now and, for
     * the one in progress, those still ahead as expected now ({@code FlowApprovalStepsComposer}); every user named.
     * Read past the caller's permissions; {@code authorize} decides who may see it, before any step is built and
     * any user named.
     *
     * @param modelName the record's model
     * @param rowId     the record
     * @param authorize checks the submissions, each with its whole history — so whoever decides who takes part in
     *                  them reads neither again — and throws to refuse them
     * @return the record's approval; no submission when the record was never submitted
     */
    FlowRecordApprovalsView recordApprovals(String modelName, String rowId, Consumer<List<RecordApproval>> authorize);

    /** One of a record's approvals, as its approval history was built from it: its state and its whole history. */
    record RecordApproval(FlowExecutionState state, List<ApprovalActionAuditEntry> history) {
    }

    /**
     * A business record's approvals — the instances its submissions started; automation runs on it
     * are not — newest first, each as the engine loads an instance (trace not hydrated): the instances
     * {@link #recordApprovals} lists, for deciding who takes part in which.
     *
     * @param modelName the record's model
     * @param rowId     the record
     * @return the approvals; none when the record was never submitted
     */
    List<FlowExecutionState> recordApprovalStates(String modelName, String rowId);

    /**
     * The business record an instance runs on, as {@code viewerId} is shown it: read past the viewer's own
     * row and field checks — the caller checks who may — every field, dynamic ones included, but the
     * sensitive ones the viewer may see on no row. The waiting steps the viewer is an approver of lay their
     * field permissions over that: what they list as Readonly, Editable or Required is shown, sensitive or
     * not, and what they mark Hidden is not. The initiator sees every field but the unseen sensitive ones;
     * anyone else also misses whatever an approval step of the flow hides.
     *
     * <p>A one-to-many's rows lose the sensitive fields the viewer may see on no row of their own model,
     * unless a waiting step of the viewer lists the one-to-many itself as Readonly, Editable or Required:
     * then they come whole. The view carries the definitions of every field it shows that the viewer's own
     * metadata leaves out, by model.
     *
     * @param state    the instance
     * @param viewerId the user the record is shown to
     * @return the record, or empty when the instance names none or it no longer exists
     */
    Optional<FlowInstanceRecordView> recordOf(FlowExecutionState state, String viewerId);

    /**
     * A business record read with the caller's own permissions — the same fields as {@link #recordOf}, with
     * their row scope, field blocks and masking, as the model's read API would return it. Nothing in it is left
     * out of the caller's metadata, so the view carries no field definitions. It needs no instance: a record
     * never submitted is read the same way.
     *
     * @param modelName the record's model
     * @param rowId     the record
     * @return the record, or empty when its model does not exist or the read finds no such row
     */
    Optional<FlowInstanceRecordView> recordThroughOwnPermissions(String modelName, String rowId);
}
