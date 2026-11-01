package io.softa.starter.flow.service.support;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.base.exception.PermissionException;
import io.softa.framework.base.utils.LambdaUtils;
import io.softa.framework.orm.constant.ModelConstant;
import io.softa.framework.orm.domain.Filters;
import io.softa.framework.orm.domain.FlexQuery;
import io.softa.framework.orm.meta.ModelManager;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.starter.flow.dto.FlowRecordApprovalsView;
import io.softa.starter.flow.entity.FlowApprovalTask;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.runtime.exception.FlowAuthorizationException;
import io.softa.starter.flow.runtime.engine.ApprovalAuditReader;
import io.softa.starter.flow.runtime.engine.FlowParticipants;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.service.FlowInstanceService;

/**
 * Who may see a flow instance, a record's approval history, and the record an instance approves.
 *
 * <p>Two grants need no configuration: the initiator sees what they submitted, and the people an
 * instance gives or gave a task to — its approvers, pending or done, CC recipients, the targets of a
 * transfer, a delegation or an added signature, and the pending approvers a termination cleared — see
 * the instances behind their tasks. Together they are the instance's participants ({@link #isParticipant}).
 * Its state and approval history name most of them ({@link FlowParticipants}). An approver whose step
 * closed without them — a termination cleared it, the others' votes carried it — is named only by their
 * task, which stays, closed: the instance's {@code FlowApprovalTask} rows, CC tasks included, are asked
 * whenever the state and history name nobody, in one query however many instances are asked about.
 * Administrative actions make nobody a participant: urging or terminating an instance, or reassigning one
 * of its tasks (the history records the administrator as the transfer's operator), gives the administrator
 * no task.
 *
 * <p>Everyone else goes through the standard role and data-scope configuration: they see an instance
 * when their own permissions let them read its {@code FlowInstance} row (the monitoring console's
 * administrators hold that through their roles) or, for a record's approval, read the record.
 *
 * <p>Reading the approved record through its instance ({@link #requireRecordAccess(FlowExecutionState, String)})
 * is narrower. Its participants read it past their own row permissions, field by field as the approval's
 * steps say. Anyone else reads it only when their own permissions read the record, and then
 * through the model's ordinary permission-checked read — exactly what the model's own API would return
 * them, so an approval page shows them the record without the model's menu being theirs. Reading the
 * {@code FlowInstance} row is not enough. A page keyed by the record reads it the same way through the
 * newest of its approvals the caller took part in, whichever is current
 * ({@link #requireRecordAccess(String, String, String)}).
 *
 * <p>Every refusal is a {@link FlowAuthorizationException} (HTTP 403).
 */
@Component
public class FlowInstanceAccessGuard {

    private static final String FLOW_INSTANCE_MODEL = FlowInstance.class.getSimpleName();

    private static final String INSTANCE_ID_FIELD = LambdaUtils.getAttributeName(FlowInstance::getInstanceId);

    private static final String TASK_MODEL = FlowApprovalTask.class.getSimpleName();

    private static final String TASK_INSTANCE_ID_FIELD = LambdaUtils.getAttributeName(FlowApprovalTask::getInstanceId);

    private final FlowInstanceService instanceService;

    private final ApprovalAuditReader auditReader;

    private final ModelService<Serializable> modelService;

    @SuppressWarnings("unchecked")
    public FlowInstanceAccessGuard(FlowInstanceService instanceService, ApprovalAuditReader auditReader,
                                   ModelService<?> modelService) {
        this.instanceService = instanceService;
        this.auditReader = auditReader;
        this.modelService = (ModelService<Serializable>) modelService;
    }

    /**
     * Authorizes a caller to view a business record's approval history: they take part in one of
     * its approvals — submitted it, wait to approve it, acted on it other than as an administrator, were
     * its target, or hold a task of it, an approver it closed without them included — or they can read
     * the record with their own permissions, as anyone who sees a record also sees its history. The same
     * people {@link #requireRecordAccess(String, String, String)} lends the record to, or lets read it.
     *
     * @param modelName   the record's model
     * @param rowId       the record
     * @param history     the record's approval history, already loaded
     * @param requesterId the authenticated caller's user id
     * @throws FlowAuthorizationException if neither holds
     */
    public void requireRecordViewer(String modelName, String rowId, FlowRecordApprovalsView history,
                                    String requesterId) {
        requireAuthenticated(requesterId, "view the approvals of " + modelName + " " + rowId);
        if (participatesIn(history, requesterId)
                || holdsTaskIn(instanceIdsOf(history), requesterId)
                || canReadRecord(modelName, rowId)) {
            return;
        }
        throw new FlowAuthorizationException("User may not view the approvals of " + modelName + " " + rowId);
    }

    /**
     * Authorizes a caller to preview the approval a business record's submission would start: they
     * can read the record with their own permissions. Taking part in the record's earlier approvals
     * is not enough — the preview is about its next one.
     *
     * @param modelName   the record's model
     * @param rowId       the record
     * @param requesterId the authenticated caller's user id
     * @throws FlowAuthorizationException if they cannot read the record
     */
    public void requireRecordReader(String modelName, String rowId, String requesterId) {
        requireAuthenticated(requesterId, "preview the approval of " + modelName + " " + rowId);
        if (canReadRecord(modelName, rowId)) {
            return;
        }
        throw new FlowAuthorizationException("User may not preview the approval of " + modelName + " " + rowId);
    }

    /**
     * Authorizes a caller to view an instance's cross-actor approval data — its task list or its
     * approval records. The rows name some participants already; any other caller is admitted as the
     * initiator, as the holder of a task of the instance — an approver who has not acted yet, or one a
     * termination cleared — or by their roles.
     *
     * @param instanceId          the runtime instance id being queried
     * @param requesterId         the authenticated caller's user id
     * @param participantInResult whether the loaded rows already name the caller as a participant: a task
     *                            of theirs, an approval action they made other than as an administrator (an
     *                            urge, a termination), or one made toward them
     * @throws FlowAuthorizationException if the caller is neither a participant, nor the initiator,
     *                                    nor permitted by their roles
     */
    public void requireInstanceViewer(String instanceId, String requesterId, boolean participantInResult) {
        requireAuthenticated(requesterId, "view instance " + instanceId);
        if (participantInResult) {
            return;
        }
        Optional<FlowInstance> instance = ContextUtils.withoutPermissionChecks(
                () -> instanceService.findByInstanceId(instanceId));
        if (instance.isPresent() && (requesterId.equals(instance.get().getInitiatorId())
                || holdsTaskIn(Collections.singletonList(instanceId), requesterId)
                || permittedByRoles(instanceId, instance.get().getRecordApproval(),
                        instance.get().getModelName(), instance.get().getRowId()))) {
            return;
        }
        throw new FlowAuthorizationException("User may not view flow instance " + instanceId);
    }

    /**
     * Authorizes a caller against the full persisted runtime state before detail endpoints expose it: a
     * participant ({@link #isParticipant}), or whoever their roles permit.
     */
    public void requireInstanceViewer(FlowExecutionState state, String requesterId) {
        String instanceId = state == null ? null : state.getInstanceId();
        requireAuthenticated(requesterId, "view instance " + instanceId);
        if (state != null && (isParticipant(state, requesterId)
                || permittedByRoles(instanceId, state.getRecordApproval(), state.getModelName(), state.getRowId()))) {
            return;
        }
        throw new FlowAuthorizationException("User may not view flow instance " + instanceId);
    }

    /**
     * How a caller reads the record an instance approves: see {@link #requireRecordAccess(FlowExecutionState, String)}.
     */
    public enum RecordAccess {

        /**
         * A participant — the initiator or a task holder: past their own row permissions, as the approval's steps
         * show it.
         */
        GRANTED,

        /** Anyone else whose own permissions read the record: the model's ordinary read, nothing more. */
        OWN_PERMISSIONS
    }

    /**
     * Authorizes a caller to read the record an instance approves, and says how. The initiator and the
     * people the instance gives or gave a task to — its participants ({@link #isParticipant}) — read it past
     * their own row permissions ({@link RecordAccess#GRANTED}).
     * Anyone else who can read the record with their own permissions reads it through them
     * ({@link RecordAccess#OWN_PERMISSIONS}) — the instance lends them nothing.
     *
     * <p>Only a record's approval lends its record: its record was submitted, checked with the submitter's
     * own permissions; any other start names its record freely, and must not become a way to read past row
     * permissions — nor a way to learn which record a run they may not see works on.
     *
     * @param state       the instance
     * @param requesterId the authenticated caller's user id
     * @return how the caller reads the record
     * @throws FlowAuthorizationException if the instance is not a record's approval, or the caller is none of these
     */
    public RecordAccess requireRecordAccess(FlowExecutionState state, String requesterId) {
        String instanceId = state == null ? null : state.getInstanceId();
        requireAuthenticated(requesterId, "read the record of instance " + instanceId);
        if (state != null && !state.isApprovalOfRecord()) {
            throw new FlowAuthorizationException("Flow instance " + instanceId
                    + " is not a record's approval; its record is read through the record's own permissions");
        }
        if (state != null && isParticipant(state, requesterId)) {
            return RecordAccess.GRANTED;
        }
        if (state != null && canReadRecord(state.getModelName(), state.getRowId())) {
            return RecordAccess.OWN_PERMISSIONS;
        }
        throw new FlowAuthorizationException("Only the initiator, the people flow instance " + instanceId
                + " gives a task to and those who can read its record may read its record through it");
    }

    /**
     * How a caller reads a business record through its approvals
     * ({@link #requireRecordAccess(String, String, String)}): {@link RecordAccess#GRANTED} through {@code approval},
     * the newest of them that lends it to them, or {@link RecordAccess#OWN_PERMISSIONS} through none
     * ({@code approval} null).
     */
    public record RecordAdmission(RecordAccess access, FlowExecutionState approval) {
    }

    /**
     * Authorizes a caller to read a business record through its approvals, whichever of them is current, and says
     * how. The record's approvals are walked newest first; the first in which the caller holds the record grant —
     * its initiator or a person it gives or gave a task to, as {@link #requireRecordAccess(FlowExecutionState, String)}
     * rules — lends it to them past their own row permissions, as that instance would
     * ({@link RecordAccess#GRANTED}): an approver it waits on reads the record as their step shows it, someone who took
     * part only in an earlier submission as that one shows it to a participant with no waiting step. Anyone else who
     * can read the record with their own permissions reads it through them ({@link RecordAccess#OWN_PERMISSIONS}) —
     * the only way a record never submitted is read here.
     *
     * <p>The approvals are read past the caller's permissions, as every lookup here is: who may read what is decided
     * by the rule above, not by the caller's grants on {@code FlowInstance}. Their states and histories are asked
     * first; then, in one query, the tasks of the approvals newer than the first of them that names the caller — of
     * all of them when none does — since a terminated approval's cleared approver, say, is named by nothing else.
     * Whoever this lends the record to, or lets read it, sees its approval history ({@link #requireRecordViewer}).
     *
     * @param modelName   the record's model
     * @param rowId       the record
     * @param requesterId the authenticated caller's user id
     * @return how the caller reads the record, and through which approval
     * @throws FlowAuthorizationException if the caller took part in none of its approvals and cannot read it
     */
    public RecordAdmission requireRecordAccess(String modelName, String rowId, String requesterId) {
        requireAuthenticated(requesterId, "read " + modelName + " " + rowId + " through its approvals");
        List<FlowExecutionState> approvals = ContextUtils.withoutPermissionChecks(
                () -> instanceService.recordApprovalStates(modelName, rowId)).stream()
                .filter(FlowExecutionState::isApprovalOfRecord)
                .toList();
        FlowExecutionState lending = newestTakenPartIn(approvals, requesterId);
        if (lending != null) {
            return new RecordAdmission(RecordAccess.GRANTED, lending);
        }
        if (canReadRecord(modelName, rowId)) {
            return new RecordAdmission(RecordAccess.OWN_PERMISSIONS, null);
        }
        throw new FlowAuthorizationException("Only those who took part in an approval of " + modelName + " " + rowId
                + " and those who can read it may read it through its approvals");
    }

    private static void requireAuthenticated(String requesterId, String action) {
        if (!StringUtils.hasText(requesterId)) {
            throw new FlowAuthorizationException("Authentication is required to " + action);
        }
    }

    /**
     * Authorizes an administrator's action on an instance — what the monitoring console does to it: the
     * caller's own permissions let them read its {@code FlowInstance} row, with the data scope of their
     * roles. Taking part in the instance is not enough; the console's actions are not a participant's.
     *
     * @throws FlowAuthorizationException if their permissions do not reach the instance
     */
    public void requireInstanceAdministrator(String instanceId, String requesterId) {
        requireAuthenticated(requesterId, "administer instance " + instanceId);
        if (canReadInstance(instanceId)) {
            return;
        }
        throw new FlowAuthorizationException("User may not administer flow instance " + instanceId);
    }

    /**
     * Whether the user takes part in the instance — the default grants: its initiator, or a person it gives or gave a
     * task to. Its state and approval history are asked first ({@link FlowParticipants}); its tasks, CC tasks and
     * closed ones included, only when they name nobody, since an approver whose step closed without them is named by
     * nothing else. Read past the caller's permissions, as every lookup here is. Who may comment on an instance
     * follows the same rule.
     *
     * @param state  the instance
     * @param userId the user
     */
    public boolean isParticipant(FlowExecutionState state, String userId) {
        return namedByInstance(state, userId)
                || holdsTaskIn(Collections.singletonList(state == null ? null : state.getInstanceId()), userId);
    }

    /** Whether the instance's state and approval history name the user a participant: no query of its tasks. */
    private boolean namedByInstance(FlowExecutionState state, String userId) {
        return FlowParticipants.isParticipant(state, auditReader.fullHistory(state), userId);
    }

    /**
     * Of a record's approvals, newest first, the newest the user takes part in, or null. Their states and histories
     * are walked newest first until one names the user; the approvals newer than that one may still have given the
     * user a task since closed, and their tasks are asked in one query — none when the newest names the user.
     */
    private FlowExecutionState newestTakenPartIn(List<FlowExecutionState> approvals, String userId) {
        int named = 0;
        while (named < approvals.size() && !namedByInstance(approvals.get(named), userId)) {
            named++;
        }
        List<FlowExecutionState> newer = approvals.subList(0, named);
        Set<String> withTask = instancesGivingTaskTo(
                newer.stream().map(FlowExecutionState::getInstanceId).toList(), userId);
        for (FlowExecutionState approval : newer) {
            if (withTask.contains(approval.getInstanceId())) {
                return approval;
            }
        }
        return named < approvals.size() ? approvals.get(named) : null;
    }

    /**
     * Whether any of these instances gives or gave the user a task: an approval task, open or closed, or a CC. One
     * query however many instances; none for no instance.
     */
    private boolean holdsTaskIn(Collection<String> instanceIds, String userId) {
        List<String> ids = presentIds(instanceIds);
        if (ids.isEmpty() || !StringUtils.hasText(userId)) {
            return false;
        }
        return ContextUtils.withoutPermissionChecks(() -> modelService.count(TASK_MODEL, taskFilters(ids, userId))) > 0;
    }

    /** Of these instances, the ones that give or gave the user a task. One query however many; none for no instance. */
    private Set<String> instancesGivingTaskTo(Collection<String> instanceIds, String userId) {
        List<String> ids = presentIds(instanceIds);
        if (ids.isEmpty() || !StringUtils.hasText(userId)) {
            return Set.of();
        }
        FlexQuery query = new FlexQuery(taskFilters(ids, userId));
        query.setFields(List.of(TASK_INSTANCE_ID_FIELD));
        return ContextUtils.withoutPermissionChecks(() -> modelService.searchList(TASK_MODEL, query)).stream()
                .map(row -> Objects.toString(row.get(TASK_INSTANCE_ID_FIELD), null))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /** The user's tasks on these instances, whatever their type and status. */
    private static Filters taskFilters(List<String> instanceIds, String userId) {
        return new Filters()
                .in(FlowApprovalTask::getInstanceId, instanceIds)
                .eq(FlowApprovalTask::getActorId, userId);
    }

    private static List<String> presentIds(Collection<String> instanceIds) {
        return instanceIds.stream().filter(StringUtils::hasText).distinct().toList();
    }

    /**
     * The standard permissions: the caller's roles let them read the instance's row, or — for a
     * record's approval — the record itself.
     */
    private boolean permittedByRoles(String instanceId, Boolean recordApproval, String modelName, String rowId) {
        return canReadInstance(instanceId)
                || (Boolean.TRUE.equals(recordApproval) && canReadRecord(modelName, rowId));
    }

    /**
     * Of these instances, the ones the caller may administer: those whose FlowInstance row their own permissions
     * read — the monitoring console's data scope. One query however many.
     */
    public Set<String> administrableInstanceIds(Collection<String> instanceIds) {
        if (instanceIds == null || instanceIds.isEmpty()) {
            return Set.of();
        }
        FlexQuery query = new FlexQuery(new Filters().in(FlowInstance::getInstanceId, List.copyOf(instanceIds)));
        query.setFields(List.of(INSTANCE_ID_FIELD));
        try {
            return modelService.searchList(FLOW_INSTANCE_MODEL, query).stream()
                    .map(row -> Objects.toString(row.get(INSTANCE_ID_FIELD), null))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
        } catch (PermissionException e) {
            return Set.of();
        }
    }

    private boolean canReadInstance(String instanceId) {
        if (!StringUtils.hasText(instanceId)) {
            return false;
        }
        try {
            return modelService.count(FLOW_INSTANCE_MODEL, new Filters().eq(FlowInstance::getInstanceId, instanceId)) > 0;
        } catch (PermissionException e) {
            return false;
        }
    }

    /** Whether the caller can read the record under their own row scope. */
    private boolean canReadRecord(String modelName, String rowId) {
        if (!StringUtils.hasText(modelName) || !StringUtils.hasText(rowId) || !ModelManager.existModel(modelName)) {
            return false;
        }
        try {
            return modelService.getById(modelName, IdUtils.formatId(modelName, rowId), List.of(ModelConstant.ID))
                    .isPresent();
        } catch (PermissionException e) {
            // No access to the model at all.
            return false;
        }
    }

    /**
     * Whether a record's approval history names the user a participant of one of its approvals: its initiator, an
     * approver it waits on, the actor of an action of it — an urge or a termination aside, as
     * {@link FlowParticipants} rules — or its target. No query of their tasks.
     */
    private static boolean participatesIn(FlowRecordApprovalsView history, String requesterId) {
        if (history == null || history.getInstances() == null) {
            return false;
        }
        for (FlowRecordApprovalsView.Instance instance : history.getInstances()) {
            if (isActor(instance.getInitiator(), requesterId)
                    || (instance.getPending() != null && instance.getPending().stream()
                            .anyMatch(step -> step.getApprovers() != null
                                    && step.getApprovers().stream().anyMatch(a -> isActor(a, requesterId))))
                    || (instance.getActions() != null && instance.getActions().stream()
                            .anyMatch(a -> (FlowParticipants.actorTakesPart(a.getAction())
                                    && isActor(a.getActor(), requesterId))
                                    || isActor(a.getTarget(), requesterId)))) {
                return true;
            }
        }
        return false;
    }

    /** The approvals a record's approval history lists. */
    private static List<String> instanceIdsOf(FlowRecordApprovalsView history) {
        if (history == null || history.getInstances() == null) {
            return List.of();
        }
        return history.getInstances().stream()
                .filter(Objects::nonNull)
                .map(FlowRecordApprovalsView.Instance::getInstanceId)
                .toList();
    }

    private static boolean isActor(FlowRecordApprovalsView.Actor actor, String requesterId) {
        return actor != null && requesterId.equals(actor.getId());
    }
}
