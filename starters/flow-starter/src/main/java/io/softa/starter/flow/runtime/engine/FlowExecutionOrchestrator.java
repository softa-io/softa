package io.softa.starter.flow.runtime.engine;

import java.time.LocalDateTime;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import io.softa.starter.flow.design.trigger.SubflowTrigger;
import io.softa.starter.flow.enums.CcTiming;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.context.FlowVariableContext;
import io.softa.starter.flow.runtime.context.FlowVariableContextUtils;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.runtime.handler.DefaultNodeHandlerRegistry;
import io.softa.starter.flow.runtime.handler.NodeExecutionHandler;
import io.softa.starter.flow.runtime.handler.NodeOutcome;
import io.softa.starter.flow.runtime.spi.ApprovalNotificationService;
import io.softa.starter.flow.runtime.spi.FlowNotificationEvent;
import io.softa.starter.flow.runtime.state.*;
import io.softa.starter.flow.runtime.store.FlowExecutionStateMapper;

/**
 * Encapsulates compiled-flow execution traversal, parallel-join bookkeeping, and synchronous subflow orchestration.
 *
 * <p>Gateway condition routing is delegated to {@link FlowGatewayRouter}. A node that throws fails the run: a call out
 * is retried by its HTTP client, never by the engine (a database statement that failed has aborted the transaction,
 * and a request that may have gone out must not be sent again). A single
 * {@link FlowVariableContext} is maintained for the entire lifetime of each
 * {@link #executeDefinition} call and passed to every node handler. Its {@code vars} tier is the instance's own
 * variables map ({@code FlowExecutionState#getVariables()}): node outputs are written into it after each node
 * completes, and what is saved is what the nodes wrote.</p>
 */
@Slf4j
@Component
public class FlowExecutionOrchestrator {

    private final FlowBundleRegistry bundleRegistry;
    private final DefaultNodeHandlerRegistry nodeHandlerRegistry;
    private final FlowActionContextService contextService;
    private final FlowAuditService auditService;
    private final PendingApprovalFactory pendingApprovalFactory;
    private final ApprovalNotificationService notificationService;
    private final FlowGatewayRouter gatewayRouter;
    private final ApproverDedupService approverDedupService;
    private final AutoCcService autoCcService;

    public FlowExecutionOrchestrator(FlowBundleRegistry bundleRegistry,
                                     DefaultNodeHandlerRegistry nodeHandlerRegistry,
                                     FlowActionContextService contextService,
                                     FlowAuditService auditService,
                                     PendingApprovalFactory pendingApprovalFactory,
                                     ApprovalNotificationService notificationService,
                                     FlowGatewayRouter gatewayRouter,
                                     ApproverDedupService approverDedupService,
                                     AutoCcService autoCcService) {
        this.bundleRegistry = bundleRegistry;
        this.nodeHandlerRegistry = nodeHandlerRegistry;
        this.contextService = contextService;
        this.auditService = auditService;
        this.pendingApprovalFactory = pendingApprovalFactory;
        this.notificationService = notificationService;
        this.gatewayRouter = gatewayRouter;
        this.approverDedupService = approverDedupService;
        this.autoCcService = autoCcService;
    }

    // ── Public entry points ───────────────────────────────────────────────────

    public void executeRoot(CompiledFlowDefinition definition, FlowExecutionState state) {
        FlowVariableContext ctx = buildContextFromState(state);
        executeDefinition(definition, state, definition.getEntryNodeIds(),
                new LinkedHashSet<>(List.of(definition.getFlowId())), ctx);
    }

    /**
     * Resume execution after an approval node has been approved.
     */
    public FlowExecutionState resumeApprovedNode(FlowExecutionState state,
                                                 PendingApproval pendingApproval,
                                                 CompiledFlowDefinition definition,
                                                 CompiledFlowNode node) {
        state.getPendingApprovals().removeIf(item -> pendingApproval.getNodeId().equals(item.getNodeId()));
        addCompletedNode(state, node.getNodeId());
        auditService.addTrace(state, definition.getFlowId(), node, FlowTraceEventType.APPROVAL_RESUMED,
                "Approval granted for node " + node.getNodeId());
        FlowStatusTransitions.apply(state, FlowExecutionStatus.RUNNING);
        return runAfterApproval(state, definition, node);
    }

    /**
     * Runs the steps after an approval step — those its approval resumes, or, on a retry, the same steps again —
     * without letting anything that stops them undo the approval.
     *
     * <p>The approver has done their part, and what stops the approval going on after it is not theirs to put right:
     * no route out of a step, nobody to approve the next one, branches meeting a step wrongly — or, on a revision
     * published before approval flows held people and routes only, an automated step failing. Whichever it is, the steps' writes are
     * undone apart from the approval, with what they arranged for the commit (messages, change logs and the flows
     * those would start); the error is logged; and the instance, as the approval left it, fails at the step that
     * stopped it, remembering where to run again from — its initiator and the tenant's administrators are told. The
     * approval stands: the caller gets the failed instance, to save, not the error. Its submitter can take the record
     * back and submit it again, on the flow as it is by then; an administrator can run the steps again once the cause
     * is gone ({@code RetryActionHandler}) — a system back up, an approver set up — or terminate the instance.
     *
     * <p>What the approver can put right is refused before this runs, the approval with it: a step already handled,
     * a required field left empty, an edit the record's rules refuse. So is a veto of the record's handler on the
     * approval's result, which runs as the instance is saved.
     *
     * @return the state to go on with: {@code state} once the steps ran, else the instance as the approval left it,
     *         failed
     */
    public FlowExecutionState runAfterApproval(FlowExecutionState state, CompiledFlowDefinition definition,
                                               CompiledFlowNode approved) {
        FlowExecutionState approvedState = FlowExecutionStateMapper.copyOf(state);
        UndoableSteps steps = UndoableSteps.begin();
        try {
            FlowVariableContext ctx = buildContextFromState(state);
            executeDefinition(definition, state, gatewayRouter.resolveNextNodeIds(definition, approved, ctx),
                    new LinkedHashSet<>(List.of(definition.getFlowId())), ctx);
            steps.keep();
            state.setRetryAfterNodeId(null);
            return state;
        } catch (RuntimeException e) {
            // The step that was running, while the trace still says — else the approval, whose routes led nowhere.
            String failedNodeId = Objects.requireNonNullElse(deriveFailedNodeId(state), approved.getNodeId());
            CompiledFlowNode failedNode = definition.getNodeIndex() == null ? approved
                    : definition.getNodeIndex().getOrDefault(failedNodeId, approved);
            steps.undo();
            log.error("Step '{}' after approval step '{}' of flow instance {} failed; the approval stands and the "
                    + "instance has failed until it is run again", failedNode.getNodeId(), approved.getNodeId(),
                    state.getInstanceId(), e);
            approvedState.setRetryAfterNodeId(approved.getNodeId());
            failState(approvedState, definition.getFlowId(), failedNode,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            return approvedState;
        }
    }

    /**
     * Resume execution after an async-task callback.
     *
     * @param state           the suspended instance state (loaded from store)
     * @param definition      the compiled flow definition
     * @param suspendedNodeId the node that originally caused the suspension
     * @param callbackOutputs outputs returned by the async task (may be empty)
     */
    public void resumeFromSuspendedNode(FlowExecutionState state,
                                        CompiledFlowDefinition definition,
                                        String suspendedNodeId,
                                        Map<String, Object> callbackOutputs) {
        // Idempotent: a redelivered callback finds no
        // live wait token for the node — already consumed, or the instance is terminal — and no-ops.
        if (state.getStatus() != null && state.getStatus().isTerminal()) {
            return;
        }
        if (state.findWaitToken(suspendedNodeId) == null) {
            return;
        }
        if (callbackOutputs != null && !callbackOutputs.isEmpty()) {
            contextService.mergeVariables(state, callbackOutputs);
        }
        state.removeWaitToken(suspendedNodeId);
        addCompletedNode(state, suspendedNodeId);
        FlowStatusTransitions.apply(state, FlowExecutionStatus.RUNNING);

        FlowVariableContext ctx = buildContextFromState(state);
        CompiledFlowNode suspendedNode = contextService.requiredNode(definition, suspendedNodeId);
        executeDefinition(definition, state, gatewayRouter.resolveNextNodeIds(definition, suspendedNode, ctx),
                new LinkedHashSet<>(List.of(definition.getFlowId())), ctx);
    }

    public void failState(FlowExecutionState state,
                          String flowId,
                          CompiledFlowNode node,
                          String message) {
        FlowStatusTransitions.apply(state, FlowExecutionStatus.FAILED);
        state.setErrorMessage(message);
        String failedNodeId = node != null ? node.getNodeId() : deriveFailedNodeId(state);
        FlowNodeType failedNodeType = node != null ? node.getType() : null;
        state.setFailedNodeId(failedNodeId);
        state.getTrace().add(FlowExecutionTraceEntry.builder()
                .flowId(flowId)
                .nodeId(failedNodeId)
                .flowNodeType(failedNodeType)
                .eventType(FlowTraceEventType.FLOW_FAILED)
                .eventTime(LocalDateTime.now())
                .message(message)
                .build());
        notificationService.notify(new FlowNotificationEvent.FlowFailed(state, failedNodeId, message));
    }

    /**
     * The failing node is the most recent ENTER_NODE without a matching
     * COMPLETE_NODE — execution is serial, so exactly one node can be open
     * when an exception unwinds to the facade. The in-memory trace holds only
     * the current attempt's entries, which always include the open ENTER_NODE.
     */
    private static String deriveFailedNodeId(FlowExecutionState state) {
        List<FlowExecutionTraceEntry> trace = state.getTrace();
        Set<String> completed = new HashSet<>();
        for (int i = trace.size() - 1; i >= 0; i--) {
            FlowExecutionTraceEntry entry = trace.get(i);
            if (entry.getNodeId() == null) {
                continue;
            }
            if (entry.getEventType() == FlowTraceEventType.COMPLETE_NODE) {
                completed.add(entry.getNodeId());
            } else if (entry.getEventType() == FlowTraceEventType.ENTER_NODE
                    && !completed.contains(entry.getNodeId())) {
                return entry.getNodeId();
            }
        }
        return null;
    }

    // ── Core execution loop ───────────────────────────────────────────────────

    private void executeDefinition(CompiledFlowDefinition definition,
                                   FlowExecutionState state,
                                   Collection<String> startingNodeIds,
                                   Set<String> flowStack,
                                   FlowVariableContext ctx) {
        ArrayDeque<String> queue = new ArrayDeque<>(startingNodeIds);
        execution:
        while (!queue.isEmpty()) {
            String nodeId = queue.removeFirst();
            CompiledFlowNode node = contextService.requiredNode(definition, nodeId);

            if (shouldWaitForParallelJoin(definition, state, node)) {
                continue;
            }
            requireFirstEntry(definition, state, node);

            auditService.addTrace(state, definition.getFlowId(), node, FlowTraceEventType.ENTER_NODE,
                    "Entering node " + node.getNodeId());

            NodeExecutionHandler handler = nodeHandlerRegistry.getHandler(node.getType());

            NodeOutcome outcome = handler.execute(node, ctx);

            // Outputs go into the run's variables — the instance's own map — regardless of outcome kind.
            outcome.outputs().forEach(ctx::writeVar);

            // Exhaustive dispatch: wait outcomes park the instance and skip the completion
            // tail; Completed / RunSubflow fall through to it; Ended terminates the loop.
            switch (outcome) {
                case NodeOutcome.Ended ended -> {
                    state.setReturnData(ended.outputs());
                    addCompletedNode(state, node.getNodeId());
                    auditService.addTrace(state, definition.getFlowId(), node, FlowTraceEventType.COMPLETE_NODE,
                            "Node " + node.getNodeId() + " ended flow gracefully");
                    break execution;
                }
                case NodeOutcome.WaitApproval _ -> {
                    PendingApproval pendingApproval = pendingApprovalFactory.buildPendingApproval(definition, node, state);
                    if (pendingApproval == null) {
                        // Nobody to approve: the node's empty-approver strategy approves it on record.
                        auditService.appendEmptyStepAutoApproval(state, definition, node);
                        addCompletedNode(state, node.getNodeId());
                        auditService.addTrace(state, definition.getFlowId(), node, FlowTraceEventType.COMPLETE_NODE,
                                "Approval node " + node.getNodeId() + " auto-approved (no approver)");
                        enqueueNextNodes(definition, node, queue, state, ctx);
                        continue;
                    }
                    // Automatic approvals — the submitter in their own step, approver consolidation (审批人去重);
                    // if the node is thereby complete, advance instead of waiting.
                    if (approverDedupService.applyAndCheckComplete(definition, node, state, pendingApproval)) {
                        addCompletedNode(state, node.getNodeId());
                        auditService.addTrace(state, definition.getFlowId(), node, FlowTraceEventType.COMPLETE_NODE,
                                "Approval node " + node.getNodeId() + " auto-approved");
                        enqueueNextNodes(definition, node, queue, state, ctx);
                        continue;
                    }
                    state.getPendingApprovals().add(pendingApproval);
                    auditService.addTrace(state, definition.getFlowId(), node, FlowTraceEventType.WAIT_APPROVAL,
                            "Waiting for approval at node " + node.getNodeId());
                    FlowStatusTransitions.apply(state, FlowExecutionStatus.WAITING);
                    notificationService.notify(new FlowNotificationEvent.TaskAssigned(state, pendingApproval));
                    continue;
                }
                case NodeOutcome.WaitAsync _ -> {
                    state.addWaitToken(FlowWaitToken.builder().nodeId(node.getNodeId()).build());
                    auditService.addTrace(state, definition.getFlowId(), node, FlowTraceEventType.COMPLETE_NODE,
                            "Async task node " + node.getNodeId() + " dispatched, waiting for callback");
                    FlowStatusTransitions.apply(state, FlowExecutionStatus.WAITING);
                    continue;
                }
                case NodeOutcome.RunSubflow subflowCall -> executeSubflow(state, node, subflowCall, flowStack, ctx);
                case NodeOutcome.Completed _ -> {
                    // nothing outcome-specific; completion tail below
                }
            }

            addCompletedNode(state, node.getNodeId());
            auditService.addTrace(state, definition.getFlowId(), node, FlowTraceEventType.COMPLETE_NODE,
                    "Completed node " + node.getNodeId());
            enqueueNextNodes(definition, node, queue, state, ctx);
        }

        requireJoinsComplete(definition, state);
        finalizeRootState(definition, state);
    }

    /**
     * A node runs once in an instance — an end aside, which each branch may reach: the graph has no cycles, and a
     * return or a resubmission starts a new instance. A parallel join counts as entered when its last branch
     * arrives. Entered again, branches have met at the node without a join (or one branch reached a join twice): it
     * would ask its approvers again and run everything after it twice. The run fails instead, naming the node — a
     * record's submission is refused and rolls back; after an approval, the instance fails with the approval kept
     * ({@link #runAfterApproval}). Publishing refuses such graphs
     * ({@code CONCURRENT_BRANCHES_MEET}, {@code BRANCH_ENTERS_JOIN_TWICE}); this is the engine's own check, should a
     * graph get past them.
     */
    private static void requireFirstEntry(CompiledFlowDefinition definition, FlowExecutionState state,
                                          CompiledFlowNode node) {
        if (isEnd(node)) {
            return;
        }
        String nodeId = node.getNodeId();
        boolean entered = state.getCompletedNodeIds().contains(nodeId)
                || state.getPendingApprovals().stream().anyMatch(pending -> nodeId.equals(pending.getNodeId()))
                || state.findWaitToken(nodeId) != null;
        if (entered) {
            throw new FlowRuntimeException("Flow '" + definition.getFlowName() + "' reached node '" + node.displayName()
                    + "' a second time: branches that run at the same time meet there without a parallel join, so "
                    + "it would run once for each. Join the branches with a parallel join first, or let each end on "
                    + "its own, and publish the flow again.");
        }
    }

    /**
     * With nothing left to wait for, every parallel join a branch reached has had all of its branches: a join still
     * short of one never goes on, and the instance would complete there, skipping the steps after it — an approval
     * written back as approved. The run fails instead, naming the join. Publishing refuses the graphs that leave a
     * join short ({@code BRANCH_SKIPS_JOIN}, {@code BRANCH_ENTERS_JOIN_TWICE}, {@code INCLUSIVE_JOIN_UNPAIRED}); this
     * is the engine's own check, should a graph get past them. A flow a Return Value ended skips the check: it ends
     * with its branches.
     */
    private static void requireJoinsComplete(CompiledFlowDefinition definition, FlowExecutionState state) {
        if (state.hasWaitTokens() || !state.getPendingApprovals().isEmpty()) {
            return;
        }
        Map<String, CompiledFlowNode> nodes = definition.getNodeIndex();
        boolean returned = state.getCompletedNodeIds().stream()
                .map(nodes::get)
                .anyMatch(node -> node != null && FlowNodeType.RETURN_VALUE.equals(node.getType()));
        if (returned) {
            return;
        }
        String prefix = definition.getFlowId() + ":";
        for (Map.Entry<String, Integer> arrivals : state.getJoinArrivalCounts().entrySet()) {
            if (!arrivals.getKey().startsWith(prefix)) {
                continue;
            }
            CompiledFlowNode join = nodes.get(arrivals.getKey().substring(prefix.length()));
            if (join == null || !isParallelJoin(join)) {
                continue;
            }
            int branches = join.getIncomingEdgeIds().size();
            if (arrivals.getValue() < branches) {
                throw new FlowRuntimeException("Flow '" + definition.getFlowName() + "' has nothing left to run, but "
                        + "parallel join '" + join.displayName() + "' has had " + arrivals.getValue() + " of its "
                        + branches + " branches: the others took a way that skips it, so the steps after it would "
                        + "never run. Let every branch that goes on to the join reach it by one edge, and publish the "
                        + "flow again.");
            }
        }
    }

    /** Where a path ends: an End, or a Return Value, which ends the flow. */
    private static boolean isEnd(CompiledFlowNode node) {
        return FlowNodeType.END.equals(node.getType()) || FlowNodeType.RETURN_VALUE.equals(node.getType());
    }

    /**
     * The completion notice tells the initiator their approval went through, so only an Approval flow
     * sends one — an automation run is not something its triggering user asked to hear about.
     */
    private static boolean announcesCompletion(CompiledFlowDefinition definition) {
        return FlowScenario.APPROVAL.equals(definition.getScenario());
    }

    // ── Subflow ───────────────────────────────────────────────────────────────

    private void executeSubflow(FlowExecutionState parentState,
                                CompiledFlowNode parentNode,
                                NodeOutcome.RunSubflow subflowCall,
                                Set<String> flowStack,
                                FlowVariableContext parentCtx) {
        // A subflow is a step like any other: what stops it throws, and whoever runs the steps handles it as it handles
        // any failing step — an automation run failed.
        String subflowId = subflowCall.flowId();
        CompiledFlowDefinition subflow = bundleRegistry.getActiveByFlowId(subflowId)
                .orElseThrow(() -> new FlowRuntimeException("Subflow " + subflowId
                        + " cannot run: it was never published, or it is disabled"));
        String subflowName = subflow.getFlowName();
        if (flowStack.contains(subflowId)) {
            throw new FlowRuntimeException("Subflow node '" + parentNode.displayName() + "' calls flow '" + subflowName
                    + "', which is already running above it: a flow cannot call itself, directly or through another");
        }
        // A flow's trigger is its start contract: only a flow that declares a Subflow trigger (an
        // Automation flow) runs from a parent.
        if (!FlowScenario.AUTOMATION.equals(subflow.getScenario()) || !(subflow.getTrigger() instanceof SubflowTrigger)) {
            throw new FlowRuntimeException("Flow '" + subflowName + "' cannot run as a subflow: only an Automation flow "
                    + "with a Subflow trigger can. Pick one on Subflow node '" + parentNode.displayName() + "'");
        }
        auditService.addTrace(parentState, subflowId, parentNode, FlowTraceEventType.SUBFLOW_START,
                "Starting subflow " + subflowName + " (flowId=" + subflowId + ") from node " + parentNode.getNodeId());

        // Fork child context from parent using the outcome's input mapping, over the child run's own variables
        Map<String, Object> childVariables = new LinkedHashMap<>();
        FlowVariableContext childCtx = FlowVariableContextUtils.forkForSubflow(
                parentCtx, subflowCall.inputMapping(), childVariables);

        FlowExecutionState childState = FlowExecutionState.builder()
                .instanceId(parentState.getInstanceId() + ":" + subflowId)
                .bundleId(subflow.getBundleId())
                .flowId(subflow.getFlowId())
                .flowName(subflowName)
                .flowRevision(subflow.getRevision())
                .status(FlowExecutionStatus.RUNNING)
                .variables(childVariables)
                .completedNodeIds(new ArrayList<>())
                .pendingApprovals(new ArrayList<>())
                .approvalAuditDelta(new ArrayList<>())
                .joinArrivalCounts(new LinkedHashMap<>())
                .trace(new ArrayList<>())
                .build();

        Set<String> childFlowStack = new LinkedHashSet<>(flowStack);
        childFlowStack.add(subflowId);
        executeDefinition(subflow, childState, subflow.getEntryNodeIds(), childFlowStack, childCtx);

        // Merge child results back into the parent's variables using output mapping
        FlowVariableContextUtils.mergeSubflowResult(
                parentCtx, childCtx,
                subflowCall.outputVariable(),
                subflowCall.outputMapping());

        parentState.getTrace().addAll(childState.getTrace());
        auditService.addTrace(parentState, subflowId, parentNode, FlowTraceEventType.SUBFLOW_END,
                "Completed subflow " + subflowName + " from node " + parentNode.getNodeId());
    }

    // ── Parallel join ─────────────────────────────────────────────────────────

    private boolean shouldWaitForParallelJoin(CompiledFlowDefinition definition,
                                              FlowExecutionState state,
                                              CompiledFlowNode node) {
        if (!isParallelJoin(node)) {
            return false;
        }
        if (joinStillWaits(definition, state, node)) {
            return true;
        }
        auditService.addTrace(state, definition.getFlowId(), node, FlowTraceEventType.JOIN_PARALLEL,
                "All branches joined at " + node.getNodeId());
        return false;
    }

    /** A parallel join: several branches come in, at most one goes on. */
    static boolean isParallelJoin(CompiledFlowNode node) {
        return FlowNodeType.PARALLEL_JOIN.equals(node.getType())
                && node.getIncomingEdgeIds().size() > 1 && node.getOutgoingEdgeIds().size() <= 1;
    }

    /**
     * Counts a branch's arrival at a parallel join in {@code state}: whether the join still waits for
     * other branches. The approval path preview counts its arrivals the same way.
     */
    static boolean joinStillWaits(CompiledFlowDefinition definition, FlowExecutionState state, CompiledFlowNode node) {
        String counterKey = definition.getFlowId() + ":" + node.getNodeId();
        int arrivals = state.getJoinArrivalCounts().merge(counterKey, 1, Integer::sum);
        return arrivals < node.getIncomingEdgeIds().size();
    }

    /**
     * Counts, at the join an inclusive gateway is paired with, the branches it did not take as arrived: each of its
     * edges leads to one edge into the join, so the join waits for the {@code taken} it did take, and goes on once,
     * after the last of them. A gateway whose branches each end on their own, or compiled before pairing, names no
     * join. The approval path preview counts the same way.
     */
    static void creditUntakenBranches(CompiledFlowDefinition definition, FlowExecutionState state,
                                      CompiledFlowNode gateway, int taken) {
        String join = gateway.getPairedJoinId();
        int untaken = gateway.getOutgoingEdgeIds().size() - taken;
        if (join != null && untaken > 0) {
            state.getJoinArrivalCounts().merge(definition.getFlowId() + ":" + join, untaken, Integer::sum);
        }
    }

    // ── Transition resolution ─────────────────────────────────────────────────

    private void enqueueNextNodes(CompiledFlowDefinition definition,
                                  CompiledFlowNode node,
                                  ArrayDeque<String> queue,
                                  FlowExecutionState state,
                                  FlowVariableContext ctx) {
        List<String> nextNodeIds = gatewayRouter.resolveNextNodeIds(definition, node, ctx);
        if (nextNodeIds.isEmpty()) {
            return;
        }
        boolean isParallelFork = FlowNodeType.PARALLEL_FORK.equals(node.getType())
                && node.getOutgoingEdgeIds().size() > 1;
        if (isParallelFork) {
            auditService.addTrace(state, definition.getFlowId(), node, FlowTraceEventType.FORK_PARALLEL,
                    "Forked " + nextNodeIds.size() + " branches from " + node.getNodeId());
        }
        boolean isGateway = FlowNodeType.PARALLEL_FORK.equals(node.getType())
                || FlowNodeType.INCLUSIVE_GATEWAY.equals(node.getType());
        if (!isGateway && nextNodeIds.size() > 1) {
            throw new FlowRuntimeException("Node '" + node.displayName()
                    + "' produced multiple outgoing branches without a gateway");
        }
        if (FlowNodeType.INCLUSIVE_GATEWAY.equals(node.getType())) {
            creditUntakenBranches(definition, state, node, nextNodeIds.size());
        }
        nextNodeIds.forEach(queue::addLast);
    }

    // ── State finalization ────────────────────────────────────────────────────

    /**
     * A run that got here did not fail: nothing in a run fails an instance but a step that throws, and the caller of the
     * run handles that ({@link #runAfterApproval}, or the facade around a start or a resume).
     *
     * <p>What an approval completing approved brings goes here, so that every run that completes one brings it — an
     * approval, a retry, an async task resuming the instance, an administrator's reassignment: the notice
     * that it went through, and the flow's approval CCs, recorded on the step whose action ended it.
     */
    private void finalizeRootState(CompiledFlowDefinition definition, FlowExecutionState state) {
        // Still parked on any wait — an async task or a pending approval.
        if (state.hasWaitTokens() || !state.getPendingApprovals().isEmpty()) {
            FlowStatusTransitions.apply(state, FlowExecutionStatus.WAITING);
            return;
        }
        FlowStatusTransitions.apply(state, FlowExecutionStatus.COMPLETED);
        // Unconditional: a state finalizes as COMPLETED exactly once (terminal guards block
        // re-entry), and a merged subflow's FLOW_COMPLETED entry must not suppress the
        // parent's own marker.
        state.getTrace().add(FlowExecutionTraceEntry.builder()
                .flowId(state.getFlowId())
                .eventType(FlowTraceEventType.FLOW_COMPLETED)
                .eventTime(LocalDateTime.now())
                .message("Flow execution completed")
                .build());
        if (announcesCompletion(definition)) {
            notificationService.notify(new FlowNotificationEvent.FlowCompleted(state));
            autoCcService.processCc(state, approvalEndStep(definition, state), CcTiming.ON_APPROVED);
        }
    }

    /**
     * The step whose action ended the approval: the approval step completed last — one that passed on its own as it
     * opened counts too — else the start node, when no approval step was on the way.
     */
    private static CompiledFlowNode approvalEndStep(CompiledFlowDefinition definition, FlowExecutionState state) {
        Map<String, CompiledFlowNode> nodes = definition.getNodeIndex() == null ? Map.of() : definition.getNodeIndex();
        List<String> completed = state.getCompletedNodeIds();
        for (int i = completed.size() - 1; i >= 0; i--) {
            CompiledFlowNode node = nodes.get(completed.get(i));
            if (node != null && FlowNodeType.APPROVAL.equals(node.getType())) {
                return node;
            }
        }
        List<String> entries = definition.getEntryNodeIds();
        return entries == null || entries.isEmpty() ? null : nodes.get(entries.getFirst());
    }

    private void addCompletedNode(FlowExecutionState state, String nodeId) {
        if (!state.getCompletedNodeIds().contains(nodeId)) {
            state.getCompletedNodeIds().add(nodeId);
        }
    }

    // ── Context construction ──────────────────────────────────────────────────

    /**
     * A run's context, over its instance's variables. The input tier is a subflow's own — its parent's inputMapping,
     * handed to it in memory (it runs in one go and is never resumed) — so a run loaded from its state has none.
     */
    private FlowVariableContext buildContextFromState(FlowExecutionState state) {
        if (state.getVariables() == null) {
            state.setVariables(new LinkedHashMap<>());
        }
        return new FlowVariableContext(Map.of(), state.getVariables());
    }
}
