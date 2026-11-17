package io.softa.starter.flow.service.support;

import java.time.LocalDateTime;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;

import io.softa.framework.base.utils.JsonUtils;
import io.softa.starter.flow.dto.FlowApprovalPreview;
import io.softa.starter.flow.dto.FlowRecordApprovalsView;
import io.softa.starter.flow.dto.FlowRecordApprovalsView.Actor;
import io.softa.starter.flow.dto.FlowRecordApprovalsView.Approver;
import io.softa.starter.flow.dto.FlowRecordApprovalsView.ApproverResult;
import io.softa.starter.flow.dto.FlowRecordApprovalsView.Ending;
import io.softa.starter.flow.dto.FlowRecordApprovalsView.Event;
import io.softa.starter.flow.dto.FlowRecordApprovalsView.Step;
import io.softa.starter.flow.dto.FlowRecordApprovalsView.StepState;
import io.softa.starter.flow.entity.FlowApprovalTask;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.enums.FlowApprovalTaskStatus;
import io.softa.starter.flow.enums.FlowApprovalTaskType;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.VoteThresholdMode;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.engine.ApprovalPathPreviewer;
import io.softa.starter.flow.runtime.engine.ApproverResolutionService;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.state.PendingApproval;
import io.softa.starter.flow.runtime.store.FlowExecutionStateMapper;

/**
 * One submission of a record as the record's approval shows it ({@link FlowRecordApprovalsView.Instance}): its
 * approval steps in the order the approval reached them, each with its approvers and what they did, and how it
 * ended.
 *
 * <ul>
 *   <li>A step the approval passed or waits on comes from its tasks — one per approver asked, with their result,
 *   time and comment — and its history: the automatic approvals, whom a step was handed to and by whom, and the
 *   copies, urges and comments on it. A step it waits on also lists the approvers the waiting step names.</li>
 *   <li>The steps still ahead of a submission in progress are worked out as its route is previewed
 *   ({@link ApprovalPathPreviewer#previewRemaining}), from its own variables and approvals, never from the record:
 *   their approvers are expected, not asked.</li>
 * </ul>
 * A flow has no loops, so a step occurs once in a submission. Users come as ids; the caller names them.
 */
@Slf4j
@Component
public class FlowApprovalStepsComposer {

    /** What else happens on a step besides its approvers' decisions: listed under it. */
    private static final Set<ApprovalActionType> STEP_EVENTS = EnumSet.of(ApprovalActionType.CC,
            ApprovalActionType.URGE, ApprovalActionType.COMMENT, ApprovalActionType.ADD_SIGN);

    /** An approver's decision, as their row shows it when no task of theirs does (history recorded before tasks). */
    private static final Map<ApprovalActionType, ApproverResult> DECISIONS = Map.of(
            ApprovalActionType.APPROVE, ApproverResult.APPROVED,
            ApprovalActionType.REJECT, ApproverResult.REJECTED,
            ApprovalActionType.RETURN, ApproverResult.RETURNED,
            ApprovalActionType.TRANSFER, ApproverResult.TRANSFERRED,
            ApprovalActionType.DELEGATE, ApproverResult.DELEGATED);

    /** The action that ends an approval with each status: the entry its ending is read from. */
    private static final Map<FlowExecutionStatus, ApprovalActionType> ENDING_ACTIONS = Map.of(
            FlowExecutionStatus.REJECTED, ApprovalActionType.REJECT,
            FlowExecutionStatus.RETURNED, ApprovalActionType.RETURN,
            FlowExecutionStatus.WITHDRAWN, ApprovalActionType.WITHDRAW,
            FlowExecutionStatus.CANCELLED, ApprovalActionType.TERMINATE);

    private final FlowBundleRegistry bundleRegistry;
    private final ApprovalPathPreviewer pathPreviewer;
    private final ApproverResolutionService approverResolution;

    public FlowApprovalStepsComposer(FlowBundleRegistry bundleRegistry, ApprovalPathPreviewer pathPreviewer,
                                     ApproverResolutionService approverResolution) {
        this.bundleRegistry = bundleRegistry;
        this.pathPreviewer = pathPreviewer;
        this.approverResolution = approverResolution;
    }

    /**
     * The submission an instance is, as its record's approval shows it.
     *
     * @param instance the instance, its whole row
     * @param history  its whole approval history
     * @param tasks    its tasks: approval tasks and copies
     * @return the submission, its users as ids
     */
    public FlowRecordApprovalsView.Instance compose(FlowInstance instance, List<ApprovalActionAuditEntry> history,
                                                    List<FlowApprovalTask> tasks) {
        CompiledFlowDefinition definition = instance.getBundleId() == null ? null
                : bundleRegistry.getByBundleId(instance.getBundleId()).orElse(null);
        List<ApprovalActionAuditEntry> entries = history.stream()
                .filter(entry -> entry.getAction() != null && !ApprovalActionType.READ.equals(entry.getAction()))
                .sorted(Comparator.comparing(ApprovalActionAuditEntry::getSequence,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        boolean inProgress = instance.getStatus() != null && !instance.getStatus().isTerminal();

        Map<String, StepDraft> drafts = new LinkedHashMap<>();
        tasks.stream()
                .filter(FlowApprovalStepsComposer::isApprovalTask)
                .sorted(Comparator.comparing(FlowApprovalTask::getStartTime, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(FlowApprovalTask::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(task -> draft(drafts, task.getNodeId(), task.getNodeLabel()).asked(task, entries));
        for (ApprovalActionAuditEntry entry : entries) {
            if (!StringUtils.hasText(entry.getNodeId())) {
                continue;
            }
            if (ApprovalActionType.AUTO_APPROVE.equals(entry.getAction())) {
                draft(drafts, entry.getNodeId(), entry.getNodeLabel()).autoApproved(entry, instance.getInitiatorId());
            } else if (DECISIONS.containsKey(entry.getAction())) {
                draft(drafts, entry.getNodeId(), entry.getNodeLabel()).decided(entry);
            }
        }
        if (inProgress) {
            for (PendingApproval pending : pendingApprovalsOf(instance)) {
                draft(drafts, pending.getNodeId(), pending.getNodeLabel()).waiting(pending);
            }
        }

        Map<String, FlowApprovalTask> copies = copiesOf(tasks);
        List<Event> events = new ArrayList<>();
        for (ApprovalActionAuditEntry entry : entries) {
            boolean onStep = STEP_EVENTS.contains(entry.getAction());
            if (!onStep && !ApprovalActionType.RETRY.equals(entry.getAction())) {
                continue;
            }
            StepDraft step = onStep && entry.getNodeId() != null ? drafts.get(entry.getNodeId()) : null;
            (step == null ? events : step.events).add(eventOf(entry, copies));
        }

        List<StepDraft> reached = new ArrayList<>(drafts.values());
        // Stable: steps entered together keep the order they were found in.
        reached.sort(Comparator.comparing((StepDraft step) -> step.enteredAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        List<Step> steps = new ArrayList<>();
        reached.forEach(step -> steps.add(step.toStep(definition, approverResolution)));

        FlowRecordApprovalsView.Instance view = FlowRecordApprovalsView.Instance.builder()
                .instanceId(instance.getInstanceId())
                .flowId(instance.getFlowId())
                .flowName(definition == null ? instance.getFlowName() : definition.getFlowName())
                .status(instance.getStatus())
                .superseded(Boolean.TRUE.equals(instance.getSuperseded()))
                .initiator(actor(instance.getInitiatorId()))
                .startedAt(instance.getCreatedTime())
                .steps(steps)
                .undecided(new ArrayList<>())
                .failure(failureOf(instance, definition))
                .ended(endingOf(instance, entries))
                .events(events)
                .build();
        if (inProgress && definition != null) {
            addRouteAhead(view, instance, definition, drafts.keySet());
        }
        return view;
    }

    /**
     * The steps still ahead of a submission in progress, its route as previewed now: after the steps it waits on,
     * each step it is expected to reach, with whom it is expected to ask — and where the route stops being known, or
     * would stop with an error. A route that cannot be worked out shows no step ahead rather than no approval.
     */
    private void addRouteAhead(FlowRecordApprovalsView.Instance view, FlowInstance instance,
                               CompiledFlowDefinition definition, Set<String> reached) {
        FlowApprovalPreview route;
        try {
            route = pathPreviewer.previewRemaining(definition, FlowExecutionStateMapper.toState(instance));
        } catch (RuntimeException e) {
            log.warn("The route ahead of flow instance {} could not be worked out", instance.getInstanceId(), e);
            return;
        }
        for (FlowApprovalPreview.Step expected : route.getSteps() == null ? List.<FlowApprovalPreview.Step>of()
                : route.getSteps()) {
            if (Boolean.TRUE.equals(expected.getCurrent()) || reached.contains(expected.getNodeId())) {
                continue;
            }
            List<Approver> approvers = new ArrayList<>();
            for (FlowApprovalPreview.AutoApproval approval : expected.getAutoApprovals() == null
                    ? List.<FlowApprovalPreview.AutoApproval>of() : expected.getAutoApprovals()) {
                approvers.add(Approver.builder()
                        .actor(approval.getActor())
                        .result(ApproverResult.AUTO_APPROVED)
                        .autoApprovalReason(approval.getReason())
                        .build());
            }
            for (Actor approver : expected.getApprovers() == null ? List.<Actor>of() : expected.getApprovers()) {
                approvers.add(Approver.builder().actor(approver).result(ApproverResult.EXPECTED).build());
            }
            view.getSteps().add(Step.builder()
                    .nodeId(expected.getNodeId())
                    .nodeLabel(expected.getNodeLabel())
                    .state(StepState.UPCOMING)
                    .approvalMode(modeOf(definition, expected.getNodeId(), approverResolution))
                    .requiredApprovals(expected.getRequiredApprovals())
                    .approvers(approvers)
                    .outcome(expected.getOutcome())
                    .assignedRoles(expected.getAssignedRoles())
                    .events(new ArrayList<>())
                    .build());
        }
        if (route.getUndecided() != null) {
            view.getUndecided().addAll(route.getUndecided());
        }
        view.setExpectedFailure(route.getFailure());
    }

    /** An approval task, as against a copy sent to someone (a CC task, read or not). */
    private static boolean isApprovalTask(FlowApprovalTask task) {
        if (!StringUtils.hasText(task.getNodeId())) {
            return false;
        }
        if (task.getTaskType() != null) {
            return FlowApprovalTaskType.APPROVAL.equals(task.getTaskType());
        }
        return task.getStatus() != FlowApprovalTaskStatus.CC && task.getStatus() != FlowApprovalTaskStatus.READ;
    }

    /** The copies sent, by step and recipient: whether each was read. */
    private static Map<String, FlowApprovalTask> copiesOf(List<FlowApprovalTask> tasks) {
        Map<String, FlowApprovalTask> copies = new HashMap<>();
        for (FlowApprovalTask task : tasks) {
            boolean copy = FlowApprovalTaskType.CC.equals(task.getTaskType())
                    || task.getStatus() == FlowApprovalTaskStatus.CC || task.getStatus() == FlowApprovalTaskStatus.READ;
            if (copy && task.getActorId() != null) {
                copies.put(copyKey(task.getNodeId(), task.getActorId()), task);
            }
        }
        return copies;
    }

    private static String copyKey(String nodeId, String recipientId) {
        return Objects.toString(nodeId, "") + '\u0000' + recipientId;
    }

    private static Event eventOf(ApprovalActionAuditEntry entry, Map<String, FlowApprovalTask> copies) {
        Boolean read = null;
        if (ApprovalActionType.CC.equals(entry.getAction()) && entry.getTargetActorId() != null) {
            FlowApprovalTask copy = copies.get(copyKey(entry.getNodeId(), entry.getTargetActorId()));
            read = copy == null ? null : copy.getStatus() == FlowApprovalTaskStatus.READ;
        }
        return Event.builder()
                .action(entry.getAction())
                .actor(actor(entry.getActorId()))
                .target(actor(entry.getTargetActorId()))
                .operator(actor(entry.getOperatorId()))
                .comment(entry.getComment())
                .time(entry.getEventTime())
                .read(read)
                .build();
    }

    /**
     * How a submission ended: the entry of the action that ended it, or when it completed — the last thing its
     * history holds, or its start for an approval with no approval step. Null while it is in progress, and after it
     * failed: its failure says where and why.
     */
    private static Ending endingOf(FlowInstance instance, List<ApprovalActionAuditEntry> entries) {
        FlowExecutionStatus status = instance.getStatus();
        if (status == null || !status.isTerminal() || FlowExecutionStatus.FAILED.equals(status)) {
            return null;
        }
        if (FlowExecutionStatus.COMPLETED.equals(status)) {
            return Ending.builder()
                    .time(entries.isEmpty() ? instance.getCreatedTime() : entries.getLast().getEventTime())
                    .build();
        }
        ApprovalActionType action = ENDING_ACTIONS.get(status);
        for (ApprovalActionAuditEntry entry : entries.reversed()) {
            if (Objects.equals(action, entry.getAction())) {
                return Ending.builder()
                        .action(action)
                        .actor(actor(entry.getActorId()))
                        .operator(actor(entry.getOperatorId()))
                        .comment(entry.getComment())
                        .time(entry.getEventTime())
                        .build();
            }
        }
        return Ending.builder().action(action).build();
    }

    /**
     * Where and why a failed approval stopped, or null for one that did not fail. The record stays in approval with
     * nobody to act on it, so its submitter takes it back — unless it failed after an approval, which an
     * administrator runs again.
     */
    private static FlowRecordApprovalsView.Failure failureOf(FlowInstance instance, CompiledFlowDefinition definition) {
        if (!FlowExecutionStatus.FAILED.equals(instance.getStatus())) {
            return null;
        }
        String nodeId = instance.getFailedNodeId();
        CompiledFlowNode node = nodeId == null || definition == null || definition.getNodeIndex() == null
                ? null : definition.getNodeIndex().get(nodeId);
        return new FlowRecordApprovalsView.Failure(nodeId, node == null ? null : node.getLabel(),
                instance.getErrorMessage(), StringUtils.hasText(instance.getRetryAfterNodeId()));
    }

    private static List<PendingApproval> pendingApprovalsOf(FlowInstance instance) {
        return JsonUtils.stringToObject(instance.getPendingApprovals(),
                new TypeReference<List<PendingApproval>>() {}, new ArrayList<>());
    }

    private static StepDraft draft(Map<String, StepDraft> drafts, String nodeId, String nodeLabel) {
        StepDraft step = drafts.computeIfAbsent(nodeId, StepDraft::new);
        if (!StringUtils.hasText(step.nodeLabel) && StringUtils.hasText(nodeLabel)) {
            step.nodeLabel = nodeLabel;
        }
        return step;
    }

    /** A step's approval mode as its definition sets it, or null when its revision is not at hand. */
    private static VoteThresholdMode modeOf(CompiledFlowDefinition definition, String nodeId,
                                           ApproverResolutionService approverResolution) {
        CompiledFlowNode node = definition == null || definition.getNodeIndex() == null || nodeId == null
                ? null : definition.getNodeIndex().get(nodeId);
        return node == null || !FlowNodeType.APPROVAL.equals(node.getType()) ? null
                : approverResolution.resolveApprovalMode(node);
    }

    private static Actor actor(String id) {
        return id == null ? null : new Actor(id, null);
    }

    private static LocalDateTime earlier(LocalDateTime a, LocalDateTime b) {
        return a == null ? b : b == null || !b.isBefore(a) ? a : b;
    }

    private static LocalDateTime later(LocalDateTime a, LocalDateTime b) {
        return a == null ? b : b == null || !b.isAfter(a) ? a : b;
    }

    /** A step as it is put together from the submission's tasks, history and waiting steps. */
    private static final class StepDraft {

        private final String nodeId;
        private String nodeLabel;
        private final List<Approver> approvers = new ArrayList<>();
        /** Who has a row: an approver's decision recorded without a task of theirs adds one only when they have none. */
        private final Set<String> listed = new HashSet<>();
        private final List<Event> events = new ArrayList<>();
        private LocalDateTime enteredAt;
        private LocalDateTime finishedAt;
        private Integer requiredApprovals;
        /** What the waiting step says, when the approval waits on it now. */
        private PendingApproval waiting;
        /** Nobody resolved to the step, and it passed on its own. */
        private boolean noApprover;

        private StepDraft(String nodeId) {
            this.nodeId = nodeId;
        }

        /** A task the step gave an approver: their row, as the task stands. */
        void asked(FlowApprovalTask task, List<ApprovalActionAuditEntry> entries) {
            ApproverResult result = resultOf(task.getStatus());
            ApprovalActionAuditEntry decision = result == ApproverResult.PENDING ? null : lastDecisionOf(task, entries);
            approvers.add(Approver.builder()
                    .actor(actor(task.getActorId()))
                    .result(result)
                    .target(decision == null ? null : actor(decision.getTargetActorId()))
                    .operator(decision == null ? null : actor(decision.getOperatorId()))
                    .time(result == ApproverResult.PENDING ? null : task.getEndTime())
                    .comment(task.getComment())
                    .build());
            listed.add(task.getActorId());
            enteredAt = earlier(enteredAt, task.getStartTime());
            finishedAt = later(finishedAt, task.getEndTime());
            if (task.getRequiredApprovalCount() != null) {
                requiredApprovals = requiredApprovals == null ? task.getRequiredApprovalCount()
                        : Math.max(requiredApprovals, task.getRequiredApprovalCount());
            }
        }

        /** An approval the flow counted on its own: someone counted as having approved, or nobody to ask. */
        void autoApproved(ApprovalActionAuditEntry entry, String submitterId) {
            enteredAt = earlier(enteredAt, entry.getEventTime());
            finishedAt = later(finishedAt, entry.getEventTime());
            if (entry.getActorId() == null) {
                noApprover = true;
                return;
            }
            if (listed.add(entry.getActorId())) {
                approvers.add(Approver.builder()
                        .actor(actor(entry.getActorId()))
                        .result(ApproverResult.AUTO_APPROVED)
                        .time(entry.getEventTime())
                        .autoApprovalReason(entry.getActorId().equals(submitterId)
                                ? FlowApprovalPreview.AutoApprovalReason.SUBMITTER
                                : FlowApprovalPreview.AutoApprovalReason.EARLIER_APPROVAL)
                        .build());
            }
        }

        /** An approver's decision: their row when no task holds it. */
        void decided(ApprovalActionAuditEntry entry) {
            enteredAt = earlier(enteredAt, entry.getEventTime());
            finishedAt = later(finishedAt, entry.getEventTime());
            if (requiredApprovals == null) {
                requiredApprovals = entry.getRequiredApprovalCount();
            }
            if (entry.getActorId() != null && listed.add(entry.getActorId())) {
                approvers.add(Approver.builder()
                        .actor(actor(entry.getActorId()))
                        .result(DECISIONS.get(entry.getAction()))
                        .target(actor(entry.getTargetActorId()))
                        .operator(actor(entry.getOperatorId()))
                        .time(entry.getEventTime())
                        .comment(entry.getComment())
                        .build());
            }
        }

        /** The approval waits on the step: its approvers as it lists them, those with no task yet included. */
        void waiting(PendingApproval pending) {
            waiting = pending;
            List<String> approved = pending.getApprovedActors() == null ? List.of() : pending.getApprovedActors();
            for (String approver : pending.getApprovers() == null ? List.<String>of() : pending.getApprovers()) {
                if (listed.add(approver)) {
                    approvers.add(Approver.builder()
                            .actor(actor(approver))
                            .result(approved.contains(approver) ? ApproverResult.APPROVED : ApproverResult.PENDING)
                            .build());
                }
            }
        }

        Step toStep(CompiledFlowDefinition definition, ApproverResolutionService approverResolution) {
            boolean current = waiting != null;
            return Step.builder()
                    .nodeId(nodeId)
                    .nodeLabel(StringUtils.hasText(nodeLabel) ? nodeLabel : labelOf(definition))
                    .state(current ? StepState.CURRENT : StepState.DONE)
                    .approvalMode(current && waiting.getApprovalMode() != null ? waiting.getApprovalMode()
                            : modeOf(definition, nodeId, approverResolution))
                    .requiredApprovals(current && waiting.getRequiredApprovalCount() != null
                            ? waiting.getRequiredApprovalCount() : requiredApprovals)
                    .enteredAt(enteredAt)
                    .finishedAt(current ? null : finishedAt)
                    .approvers(approvers)
                    .outcome(noApprover && approvers.isEmpty() ? FlowApprovalPreview.StepOutcome.NO_APPROVER_APPROVED
                            : null)
                    .assignedRoles(current && waiting.assignedToRoles() ? waiting.getAssignedRoles() : null)
                    .events(events)
                    .build();
        }

        private String labelOf(CompiledFlowDefinition definition) {
            CompiledFlowNode node = definition == null || definition.getNodeIndex() == null ? null
                    : definition.getNodeIndex().get(nodeId);
            return node == null ? null : node.getLabel();
        }

        /** The last decision the task's approver recorded on the step: whom they handed it to, and who acted for them. */
        private ApprovalActionAuditEntry lastDecisionOf(FlowApprovalTask task, List<ApprovalActionAuditEntry> entries) {
            ApprovalActionAuditEntry last = null;
            for (ApprovalActionAuditEntry entry : entries) {
                if (DECISIONS.containsKey(entry.getAction()) && Objects.equals(nodeId, entry.getNodeId())
                        && Objects.equals(task.getActorId(), entry.getActorId())) {
                    last = entry;
                }
            }
            return last;
        }

        private static ApproverResult resultOf(FlowApprovalTaskStatus status) {
            return switch (status) {
                case APPROVED -> ApproverResult.APPROVED;
                case REJECTED -> ApproverResult.REJECTED;
                case RETURNED -> ApproverResult.RETURNED;
                case TRANSFERRED -> ApproverResult.TRANSFERRED;
                case DELEGATED -> ApproverResult.DELEGATED;
                case CANCELED, WITHDRAWN -> ApproverResult.NOT_NEEDED;
                case null, default -> ApproverResult.PENDING;
            };
        }
    }
}
