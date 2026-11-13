package io.softa.starter.flow.runtime.engine;

import java.util.*;
import org.springframework.stereotype.Component;

import io.softa.framework.orm.compute.ComputeUtils;
import io.softa.framework.orm.domain.Filters;
import io.softa.starter.flow.dto.FlowApprovalPreview;
import io.softa.starter.flow.dto.FlowApprovalPreview.AutoApproval;
import io.softa.starter.flow.dto.FlowApprovalPreview.AutoApprovalReason;
import io.softa.starter.flow.dto.FlowApprovalPreview.Step;
import io.softa.starter.flow.dto.FlowApprovalPreview.StepOutcome;
import io.softa.starter.flow.dto.FlowApprovalPreview.Stop;
import io.softa.starter.flow.dto.FlowRecordApprovalsView;
import io.softa.starter.flow.enums.ApproverDedupStrategy;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.api.FlowStartRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.bundle.CompiledFlowTransition;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.PendingApproval;

/**
 * Works out the approval path of a record's submission without running its flow, for the approval
 * path preview ({@code FlowLaunchService.previewRecord}) and the editor's test run of a draft
 * ({@code FlowLaunchService.previewDraft}). It walks the flow from its start node the
 * way {@link FlowExecutionOrchestrator} does, every approval step assumed approved: routes are decided
 * by {@link FlowGatewayRouter}, each step's approvers by {@link PendingApprovalFactory} and its
 * automatic approvals by {@link ApproverDedupService}, on the state the submission would start from —
 * which is never saved. No node runs; no instance, task, ledger row or notification is created, and
 * no delegation's use is counted.
 *
 * <p>Nothing is guessed. An approval flow holds people and routes only, so the variables a route reads are the
 * submission's — but the instance id, unknown before the start, and anything a node on a revision published before
 * that rule may write: a route whose conditions read one is undecided and not followed, and a step whose approvers
 * are found by a rule after such a node is undetermined. With approver dedup on, so is a step whose
 * approver may or may not have approved an earlier step, one of several there who could pass it.
 */
@Component
public class ApprovalPathPreviewer {

    /** Start variables whose values exist only once the instance has started. */
    private static final Set<String> SET_ON_START = Set.of("_instanceId");

    private final FlowGatewayRouter gatewayRouter;
    private final PendingApprovalFactory pendingApprovalFactory;
    private final ApproverResolutionService approverResolutionService;
    private final ApproverDedupService approverDedupService;
    private final FlowAuditService auditService;

    public ApprovalPathPreviewer(FlowGatewayRouter gatewayRouter,
                                 PendingApprovalFactory pendingApprovalFactory,
                                 ApproverResolutionService approverResolutionService,
                                 ApproverDedupService approverDedupService,
                                 FlowAuditService auditService) {
        this.gatewayRouter = gatewayRouter;
        this.pendingApprovalFactory = pendingApprovalFactory;
        this.approverResolutionService = approverResolutionService;
        this.approverDedupService = approverDedupService;
        this.auditService = auditService;
    }

    /**
     * The approval path {@code request} — the start request of a record's submission — would take on
     * {@code definition}. Users come as ids; the caller names them.
     */
    public FlowApprovalPreview preview(CompiledFlowDefinition definition, FlowStartRequest request) {
        Walk walk = new Walk(definition, FlowRuntimeFacade.initialState(definition, request, true, null), SET_ON_START);
        walk.run(definition.getEntryNodeIds());
        return FlowApprovalPreview.builder()
                .status(FlowApprovalPreview.Status.READY)
                .flow(new FlowApprovalPreview.Flow(definition.getFlowId(), definition.getFlowName()))
                .steps(walk.steps)
                .undecided(walk.undecided)
                .failure(walk.failure)
                .nodeIds(List.copyOf(walk.passed))
                .build();
    }

    /**
     * The rest of a running approval's path, for its record's approval to show the steps ahead
     * ({@code FlowApprovalStepsComposer}): the steps it waits on now, as they stand — who is still asked — then
     * the steps after them, worked out as a submission's path is, from the instance's own variables and
     * approvals. Every step still waiting is assumed approved. The instance is read, never changed: the walk
     * runs on a copy of it. Users come as ids; the caller names them.
     */
    public FlowApprovalPreview previewRemaining(CompiledFlowDefinition definition, FlowExecutionState live) {
        Walk walk = new Walk(definition, copyOf(live), Set.of());
        List<String> after = new ArrayList<>();
        for (PendingApproval pending : live.getPendingApprovals() == null ? List.<PendingApproval>of() : live.getPendingApprovals()) {
            CompiledFlowNode node = definition.getNodeIndex().get(pending.getNodeId());
            if (node != null) {
                walk.waiting(node, pending);
                after.addAll(gatewayRouter.resolveNextNodeIds(definition, node, walk::decide));
            }
        }
        walk.run(after);
        return FlowApprovalPreview.builder()
                .status(FlowApprovalPreview.Status.IN_PROGRESS)
                .flow(new FlowApprovalPreview.Flow(definition.getFlowId(), definition.getFlowName()))
                .steps(walk.steps)
                .undecided(walk.undecided)
                .failure(walk.failure)
                .nodeIds(List.copyOf(walk.passed))
                .build();
    }

    /** A copy of a running instance to walk on: what the walk records lands on the copy alone. */
    private static FlowExecutionState copyOf(FlowExecutionState live) {
        return FlowExecutionState.builder()
                .instanceId(live.getInstanceId())
                .bundleId(live.getBundleId())
                .flowId(live.getFlowId())
                .flowName(live.getFlowName())
                .flowRevision(live.getFlowRevision())
                .title(live.getTitle())
                .modelName(live.getModelName())
                .rowId(live.getRowId())
                .initiatorId(live.getInitiatorId())
                .recordApproval(live.getRecordApproval())
                .status(live.getStatus())
                .variables(new LinkedHashMap<>(live.getVariables() == null ? Map.of() : live.getVariables()))
                .completedNodeIds(new ArrayList<>(live.getCompletedNodeIds() == null ? List.of() : live.getCompletedNodeIds()))
                .pendingApprovals(new ArrayList<>())
                .approvalAuditDelta(new ArrayList<>(live.getApprovalAuditDelta() == null ? List.of() : live.getApprovalAuditDelta()))
                .persistedAuditCount(live.getPersistedAuditCount())
                .auditSequenceBase(live.getAuditSequenceBase())
                .committedAudit(live.getCommittedAudit() == null ? null : new ArrayList<>(live.getCommittedAudit()))
                .joinArrivalCounts(new HashMap<>(live.getJoinArrivalCounts() == null ? Map.of() : live.getJoinArrivalCounts()))
                .trace(new ArrayList<>())
                .build();
    }

    /** Thrown by a route condition that reads data only known once the approval runs. */
    private static final class UndecidedRoute extends RuntimeException {

        UndecidedRoute() {
            super(null, null, false, false);
        }
    }

    /** One walk of a flow: the state it walks on and what it finds. */
    private final class Walk {

        private final CompiledFlowDefinition definition;
        /**
         * The state the walk starts from — the one a submission would start from, or a copy of a running
         * instance. The approvals the walk assumes are recorded on it, so that later steps' dedup sees
         * them, as on a running instance.
         */
        private final FlowExecutionState state;
        /** Variables whose values are not known before the approval runs. */
        private final Set<String> unknownVariables;
        /** By step: approvers who may or may not approve it — one of several who could pass it. */
        private final Map<String, Set<String>> possibleApprovers = new HashMap<>();

        private final List<Step> steps = new ArrayList<>();
        private final List<Stop> undecided = new ArrayList<>();
        private Stop failure;
        /** The nodes the walk reached, in order: a join once its branches are in. */
        private final Set<String> passed = new LinkedHashSet<>();

        Walk(CompiledFlowDefinition definition, FlowExecutionState state, Set<String> unknownAtStart) {
            this.definition = definition;
            this.state = state;
            this.unknownVariables = new HashSet<>(unknownAtStart);
        }

        /**
         * A step the running approval waits on now, as it stands: the approvers still asked. Who already
         * approved it is the approval history's to show. Its approval is assumed, as every later step's is.
         */
        void waiting(CompiledFlowNode node, PendingApproval pending) {
            List<String> remaining = pending.getApprovers().stream()
                    .filter(approver -> !pending.getApprovedActors().contains(approver))
                    .toList();
            int required = Math.max(0, pending.getRequiredApprovalCount() - pending.getApprovedActors().size());
            Step step = step(node, StepOutcome.WAITING, remaining, required, List.of());
            step.setCurrent(true);
            step.setAssignedRoles(pending.assignedToRoles() ? pending.getAssignedRoles() : null);
            steps.add(step);
            passed.add(node.getNodeId());
            assumeApproved(node, remaining, required);
        }

        void run(Collection<String> from) {
            Deque<String> queue = new ArrayDeque<>(from);
            while (!queue.isEmpty() && failure == null) {
                String nodeId = queue.removeFirst();
                CompiledFlowNode node = definition.getNodeIndex().get(nodeId);
                if (node == null) {
                    failure = new Stop(nodeId, null, "Node not found in compiled definition: " + nodeId);
                    return;
                }
                if (FlowExecutionOrchestrator.isParallelJoin(node)
                        && FlowExecutionOrchestrator.joinStillWaits(definition, state, node)) {
                    continue;
                }
                passed.add(nodeId);
                if (FlowNodeType.RETURN_VALUE.equals(node.getType())) {
                    // Ends the flow, branches still queued included.
                    return;
                }
                try {
                    if (FlowNodeType.APPROVAL.equals(node.getType())) {
                        previewStep(node);
                    }
                    if (failure == null) {
                        List<String> next = gatewayRouter.resolveNextNodeIds(definition, node, this::decide);
                        if (FlowNodeType.INCLUSIVE_GATEWAY.equals(node.getType())) {
                            FlowExecutionOrchestrator.creditUntakenBranches(definition, state, node, next.size());
                        }
                        queue.addAll(next);
                    }
                } catch (UndecidedRoute e) {
                    undecided.add(new Stop(node.getNodeId(), node.getLabel(), null));
                } catch (RuntimeException e) {
                    // What fails the instance here ends the walk here.
                    failure = new Stop(node.getNodeId(), node.getLabel(), e.getMessage());
                }
            }
        }

        /** A route condition, decided over the start variables unless it reads one that is not known. */
        private boolean decide(CompiledFlowTransition transition) {
            Collection<String> reads = Filters.isEmpty(transition.getCondition())
                    ? ComputeUtils.getVariables(transition.getConditionExpression())
                    : transition.getCondition().extractFields();
            if (reads.stream().anyMatch(unknownVariables::contains)) {
                throw new UndecidedRoute();
            }
            return FlowGatewayRouter.holds(definition, transition, state.getVariables());
        }

        private void previewStep(CompiledFlowNode node) {
            PendingApproval pending = pendingApprovalFactory.previewPendingApproval(definition, node, state);
            if (pending == null) {
                previewEmptyStep(node);
                return;
            }
            List<ApprovalActionAuditEntry> recorded = state.getApprovalAuditDelta();
            int before = recorded.size();
            boolean passes = approverDedupService.applyAndCheckComplete(definition, node, state, pending);
            List<AutoApproval> autoApprovals = autoApprovals(List.copyOf(recorded.subList(before, recorded.size())));
            if (passes) {
                steps.add(step(node, StepOutcome.AUTO_APPROVED, List.of(), null, autoApprovals));
                return;
            }
            List<String> remaining = pending.getApprovers().stream()
                    .filter(approver -> !pending.getApprovedActors().contains(approver))
                    .toList();
            int required = pending.getRequiredApprovalCount() - pending.getApprovedActors().size();
            StepOutcome outcome = mayHaveApprovedEarlier(node, remaining) ? StepOutcome.UNDETERMINED : StepOutcome.WAITING;
            Step step = step(node, outcome, remaining, required, autoApprovals);
            step.setAssignedRoles(pending.assignedToRoles() ? pending.getAssignedRoles() : null);
            steps.add(step);
            assumeApproved(node, remaining, required);
        }

        /** Records the step's assumed approval, as later steps' dedup will see it. */
        private void assumeApproved(CompiledFlowNode node, List<String> remaining, int required) {
            if (required >= remaining.size()) {
                // The step passes only once each of them has approved it.
                remaining.forEach(approver -> recordApproval(node, approver));
            } else {
                // Some of them will; which ones is only known once they act.
                recordApproval(node, null);
                possibleApprovers.put(node.getNodeId(), Set.copyOf(remaining));
            }
        }

        /** A step that resolves to nobody, as its empty-approver strategy has the engine treat it. */
        private void previewEmptyStep(CompiledFlowNode node) {
            switch (pendingApprovalFactory.resolveEmptyApproverStrategy(node)) {
                case AUTO_APPROVE -> {
                    auditService.appendEmptyStepAutoApproval(state, definition, node);
                    steps.add(step(node, StepOutcome.NO_APPROVER_APPROVED, List.of(), null, List.of()));
                }
                case ERROR, ASSIGN_TO_ROLE -> {
                    // AssignToRole reaches here only when its roles leave nobody either.
                    steps.add(step(node, StepOutcome.NO_APPROVER_FAILS, List.of(), null, List.of()));
                    failure = new Stop(node.getNodeId(), node.getLabel(), pendingApprovalFactory.emptyApproverMessage(node, state));
                }
            }
        }

        /**
         * Whether one of {@code approvers} may or may not have approved an earlier step that the flow's
         * approver dedup counts here — which would pass them here automatically.
         */
        private boolean mayHaveApprovedEarlier(CompiledFlowNode node, List<String> approvers) {
            ApproverDedupStrategy strategy = ApproverDedupService.strategyOf(definition);
            Set<String> earlierSteps = new HashSet<>();
            approverDedupService.countedApprovals(state, node.getNodeId(), strategy)
                    .forEach(approval -> earlierSteps.add(approval.getNodeId()));
            for (String step : earlierSteps) {
                Set<String> possible = possibleApprovers.getOrDefault(step, Set.of());
                if (approvers.stream().anyMatch(possible::contains)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Records on the walk's state that {@code approver} approves the step — or someone does, when
         * null — as the engine records an approval, so later steps' dedup sees it.
         */
        private void recordApproval(CompiledFlowNode node, String approver) {
            auditService.appendApprovalAudit(state, ApprovalActionAuditEntry.builder()
                    .flowId(definition.getFlowId())
                    .flowRevision(definition.getRevision())
                    .nodeId(node.getNodeId())
                    .nodeLabel(node.getLabel())
                    .action(ApprovalActionType.APPROVE)
                    .actorId(approver)
                    .build());
        }

        /** The automatic approvals {@link ApproverDedupService} recorded on a step, and why each. */
        private List<AutoApproval> autoApprovals(List<ApprovalActionAuditEntry> entries) {
            String submitter = approverDedupService.selfApprover(definition, state);
            return entries.stream()
                    .filter(entry -> ApprovalActionType.AUTO_APPROVE.equals(entry.getAction()) && entry.getActorId() != null)
                    .map(entry -> new AutoApproval(actor(entry.getActorId()), entry.getActorId().equals(submitter)
                            ? AutoApprovalReason.SUBMITTER : AutoApprovalReason.EARLIER_APPROVAL))
                    .toList();
        }

        private Step step(CompiledFlowNode node, StepOutcome outcome, List<String> approvers, Integer requiredApprovals,
                          List<AutoApproval> autoApprovals) {
            return Step.builder()
                    .nodeId(node.getNodeId())
                    .nodeLabel(node.getLabel())
                    .outcome(outcome)
                    .approvers(approvers.stream().map(Walk::actor).toList())
                    .requiredApprovals(requiredApprovals)
                    .autoApprovals(autoApprovals)
                    .build();
        }

        private static FlowRecordApprovalsView.Actor actor(String userId) {
            return new FlowRecordApprovalsView.Actor(userId, null);
        }
    }
}
