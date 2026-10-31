package io.softa.starter.flow.service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.domain.Page;
import io.softa.starter.flow.dto.FlowInstanceSearchRequest;
import io.softa.starter.flow.dto.FlowRecordApprovalsView;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;

/**
 * Service interface for persistent flow instance operations.
 * <p>
 * Every method is abstract on purpose: core capabilities (timer sweeps, start-dedup,
 * monitoring) depend on these finders, so a replacement implementation that misses one
 * must fail to compile instead of silently returning empty results.
 */
public interface FlowInstanceService {

    /**
     * Save or update a flow instance entity.
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
     */
    List<FlowInstance> findByInstanceIds(Collection<String> instanceIds);

    /**
     * Find all instances of a flow.
     *
     * @param flowId the flow ID
     * @return list of matching instances
     */
    List<FlowInstance> findByFlowId(String flowId);

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
     * Find the run a trigger event started for a flow — an event starts a flow at most once.
     *
     * @param flowId  the flow id
     * @param eventId the trigger event's identity
     * @return the instance, if the event already started the flow
     */
    Optional<FlowInstance> findByTriggerEvent(String flowId, String eventId);

    /**
     * Find all instances initiated by a specific user.
     *
     * @param initiatorId the initiator id
     * @return list of matching instances
     */
    List<FlowInstance> findByInitiatorId(String initiatorId);

    /**
     * Find WAITING instances with a due timer whose {@code next_fire_at} is at or before {@code now}.
     *
     * @param now   cutoff timestamp
     * @param limit maximum rows to return
     * @return list of due timer instances
     */
    List<FlowInstance> findDueTimers(LocalDateTime now, int limit);

    /**
     * Count WAITING instances with a due timer whose {@code next_fire_at} is at or before {@code now}.
     */
    long countDueTimers(LocalDateTime now);

    /**
     * Count instances currently in the given status.
     */
    long countByStatus(FlowExecutionStatus status);

    /**
     * How many of the flow's instances have not ended — pending, running or waiting, a record's approval in progress
     * among them — whoever may see them.
     */
    long countUnfinished(String flowId);

    /** The revisions (bundle ids) the current tenant's unfinished instances run on, whoever may see them. */
    Set<Long> unfinishedBundleIds();

    /**
     * Paged instance query for monitoring views.
     */
    Page<FlowInstance> searchInstances(FlexQuery query, Page<FlowInstance> page);

    /**
     * Paged summary search shared by the caller's "my instances" reads
     * ({@code POST /flow/instances/search} and {@code FlowClient.myInstances}) and the
     * cross-initiator monitor endpoint. Builds the summary projection (heavy JSON
     * state columns excluded) and the filter set from the request, newest first.
     *
     * @param request           search filters and paging
     * @param forcedInitiatorId when non-null, overrides any requested initiator filter and keeps
     *                          the record approvals only — the caller-facing "my instances"
     *                          contract; when {@code null} the request's own {@code initiatorId}
     *                          is honored over every instance (monitor views)
     * @return one page of instance summary rows
     */
    Page<FlowInstance> searchSummaries(FlowInstanceSearchRequest request, String forcedInitiatorId);

    /**
     * Marks every approval of a record superseded, read and written past the caller's data
     * permissions — engine bookkeeping: a new submission of the record replaces them, so a returned
     * one no longer waits for the record to be edited.
     */
    void markSuperseded(String modelName, String rowId);

    /**
     * Find non-terminal instances (RUNNING / WAITING_*) whose last update time is before
     * {@code threshold} — i.e. instances that have been silent longer than expected and may
     * require operator attention.
     *
     * @param threshold updated-time cutoff
     * @param limit     maximum rows to return
     * @return list of candidate stuck instances
     */
    List<FlowInstance> findStuckInstances(LocalDateTime threshold, int limit);

    /**
     * A business record's approval history, for its approval panel: the record's approval instances
     * (one per submission), newest first, each with its status, initiator, the steps still waiting
     * and the approvers yet to act, and its approval actions (CC reads left out), every user named.
     * Read past the caller's permissions; {@code authorize} decides who may see it, and the users are
     * named only once it has passed.
     *
     * @param modelName the record's model
     * @param rowId     the record
     * @param authorize checks the history, its users still unnamed, and throws to refuse it
     * @return the history; no instances when the record was never submitted
     */
    FlowRecordApprovalsView recordApprovals(String modelName, String rowId,
                                            Consumer<FlowRecordApprovalsView> authorize);

    /**
     * The business record an instance runs on, as {@code viewerId} is shown it: read past the viewer's own
     * row and field checks — the caller checks who may — every field, dynamic ones included, but the
     * sensitive ones the viewer may see on no row. The waiting steps the viewer is an approver of lay their
     * field permissions over that: what they list as Readonly, Editable or Required is shown, sensitive or
     * not, and what they mark Hidden is not. The initiator sees every field but the unseen sensitive ones;
     * anyone else also misses whatever an approval step of the flow hides.
     *
     * @param state    the instance
     * @param viewerId the user the record is shown to
     * @return the record, or empty when the instance names none or it no longer exists
     */
    Optional<Map<String, Object>> recordOf(FlowExecutionState state, String viewerId);
}
