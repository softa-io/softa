package io.softa.starter.flow.runtime.engine;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import org.springframework.stereotype.Component;

import io.softa.starter.flow.design.trigger.SubflowTrigger;
import io.softa.starter.flow.enums.EmptyApproverStrategy;
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

/**
 * Encapsulates compiled-flow execution traversal, parallel-join bookkeeping, and synchronous subflow orchestration.
 *
 * <p>Gateway condition routing is delegated to {@link FlowGatewayRouter}; per-node error
 * strategy (FAIL/RETRY) is delegated to {@link NodeRetryExecutor}. A single
 * {@link FlowVariableContext} is maintained for the entire lifetime of each
 * {@link #executeDefinition} call and passed to every node handler. Node outputs are
 * merged into the context's mutable {@code vars} tier after each node completes, which
 * is synced back to {@code FlowExecutionState#getVariables()} for persistence-layer
 * compatibility.</p>
 */
@Component
public class FlowExecutionOrchestrator {

    private final FlowBundleRegistry bundleRegistry;
    private final DefaultNodeHandlerRegistry nodeHandlerRegistry;
    private final FlowActionContextService contextService;
    private final FlowAuditService auditService;
    private final PendingApprovalFactory pendingApprovalFactory;
    private final ApprovalNotificationService notificationService;
    private final FlowGatewayRouter gatewayRouter;
    private final NodeRetryExecutor retryExecutor;
    private final ApproverDedupService approverDedupService;

    public FlowExecutionOrchestrator(FlowBundleRegistry bundleRegistry,
                                     DefaultNodeHandlerRegistry nodeHandlerRegistry,
                                     FlowActionContextService contextService,
                                     FlowAuditService auditService,
                                     PendingApprovalFactory pendingApprovalFactory,
                                     ApprovalNotificationService notificationService,
                                     FlowGatewayRouter gatewayRouter,
                                     NodeRetryExecutor retryExecutor,
                                     ApproverDedupService approverDedupService) {
        this.bundleRegistry = bundleRegistry;
        this.nodeHandlerRegistry = nodeHandlerRegistry;
        this.contextService = contextService;
        this.auditService = auditService;
        this.pendingApprovalFactory = pendingApprovalFactory;
        this.notificationService = notificationService;
        this.gatewayRouter = gatewayRouter;
        this.retryExecutor = retryExecutor;
        this.approverDedupService = approverDedupService;
    }

    // ── Public entry points ───────────────────────────────────────────────────

    public void executeRoot(CompiledFlowDefinition definition, FlowExecutionState state) {
        FlowVariableContext ctx = buildContextFromState(state);
        executeDefinition(definition, state, definition.getEntryNodeIds(), false,
                new LinkedHashSet<>(List.of(definition.getFlowId())), ctx, true);
    }

    /**
     * Transient variant of {@link #executeRoot} for stateless Validation / Compute evaluation:
     * identical traversal, but completion notifications are suppressed — the run leaves no
     * persisted instance, so listeners must not observe it.
     */
    public void evaluateRoot(CompiledFlowDefinition definition, FlowExecutionState state) {
        FlowVariableContext ctx = buildContextFromState(state);
        executeDefinition(definition, state, definition.getEntryNodeIds(), false,
                new LinkedHashSet<>(List.of(definition.getFlowId())), ctx, false);
    }

    /**
     * Resume execution after an approval node has been approved.
     */
    public void resumeApprovedNode(FlowExecutionState state,
                                   PendingApproval pendingApproval,
                                   CompiledFlowDefinition definition,
                                   CompiledFlowNode node) {
        state.getPendingApprovals().removeIf(item -> pendingApproval.getNodeId().equals(item.getNodeId()));
        addCompletedNode(state, node.getNodeId());
        auditService.addTrace(state, definition.getFlowId(), node, FlowTraceEventType.APPROVAL_RESUMED,
                "Approval granted for node " + node.getNodeId());
        FlowStatusTransitions.apply(state, FlowExecutionStatus.RUNNING);

        FlowVariableContext ctx = buildContextFromState(state);
        executeDefinition(definition, state, gatewayRouter.resolveNextNodeIds(definition, node, ctx), false,
                new LinkedHashSet<>(List.of(definition.getFlowId())), ctx, true);
    }

    /**
     * Resume execution after an async-task callback or timer fires.
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
        // Idempotent: a redelivered timer/async message (or a sweep racing Pulsar) finds no
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
        executeDefinition(definition, state, gatewayRouter.resolveNextNodeIds(definition, suspendedNode, ctx), false,
                new LinkedHashSet<>(List.of(definition.getFlowId())), ctx, true);
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
                                   boolean subflow,
                                   Set<String> flowStack,
                                   FlowVariableContext ctx,
                                   boolean notifyOnComplete) {
        ArrayDeque<String> queue = new ArrayDeque<>(startingNodeIds);
        execution:
        while (!queue.isEmpty()) {
            String nodeId = queue.removeFirst();
            CompiledFlowNode node = contextService.requiredNode(definition, nodeId);

            if (shouldWaitForParallelJoin(definition, state, node)) {
                continue;
            }

            auditService.addTrace(state, definition.getFlowId(), node, FlowTraceEventType.ENTER_NODE,
                    "Entering node " + node.getNodeId());

            NodeExecutionHandler handler = nodeHandlerRegistry.getHandler(node.getType());

            NodeOutcome outcome = retryExecutor.execute(handler, node, ctx, state, definition);
            if (FlowExecutionStatus.FAILED.equals(state.getStatus())) {
                return;
            }

            // Merge outputs into context vars and sync back to state — regardless of outcome kind
            if (!outcome.outputs().isEmpty()) {
                outcome.outputs().forEach(ctx::writeVar);
                contextService.mergeVariables(state, outcome.outputs());
            }

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
                        // Nobody to approve: the node's empty-approver strategy skips it, or approves it on record.
                        boolean autoApproved = EmptyApproverStrategy.AUTO_APPROVE.equals(
                                pendingApprovalFactory.resolveEmptyApproverStrategy(node));
                        if (autoApproved) {
                            auditService.appendEmptyStepAutoApproval(state, definition, node);
                        }
                        addCompletedNode(state, node.getNodeId());
                        auditService.addTrace(state, definition.getFlowId(), node, FlowTraceEventType.COMPLETE_NODE,
                                "Approval node " + node.getNodeId() + (autoApproved ? " auto-approved" : " skipped")
                                        + " (no approver)");
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
                case NodeOutcome.WaitTimer timer -> {
                    LocalDateTime dueAt = timer.dueAt() == null ? null
                            : LocalDateTime.ofInstant(timer.dueAt(), ZoneId.systemDefault());
                    state.addWaitToken(FlowWaitToken.builder()
                            .nodeId(node.getNodeId()).type(WaitType.TIMER).dueAt(dueAt).build());
                    auditService.addTrace(state, definition.getFlowId(), node, FlowTraceEventType.COMPLETE_NODE,
                            "Timer node " + node.getNodeId() + " suspending instance");
                    FlowStatusTransitions.apply(state, FlowExecutionStatus.WAITING);
                    continue;
                }
                case NodeOutcome.WaitAsync _ -> {
                    state.addWaitToken(FlowWaitToken.builder()
                            .nodeId(node.getNodeId()).type(WaitType.ASYNC).build());
                    auditService.addTrace(state, definition.getFlowId(), node, FlowTraceEventType.COMPLETE_NODE,
                            "Async task node " + node.getNodeId() + " dispatched, waiting for callback");
                    FlowStatusTransitions.apply(state, FlowExecutionStatus.WAITING);
                    continue;
                }
                case NodeOutcome.RunSubflow subflowCall -> {
                    executeSubflow(state, node, subflowCall, flowStack, ctx, notifyOnComplete);
                    if (FlowExecutionStatus.FAILED.equals(state.getStatus())) {
                        return;
                    }
                }
                case NodeOutcome.Completed _ -> {
                    // nothing outcome-specific; completion tail below
                }
            }

            addCompletedNode(state, node.getNodeId());
            auditService.addTrace(state, definition.getFlowId(), node, FlowTraceEventType.COMPLETE_NODE,
                    "Completed node " + node.getNodeId());
            enqueueNextNodes(definition, node, queue, state, ctx);
        }

        if (!subflow) {
            finalizeRootState(state, announcesCompletion(definition, notifyOnComplete));
        }
    }

    /**
     * The completion notice tells the initiator their approval went through, so only an Approval flow
     * sends one — an automation run is not something its triggering user asked to hear about.
     */
    private static boolean announcesCompletion(CompiledFlowDefinition definition, boolean notifyOnComplete) {
        return notifyOnComplete && FlowScenario.APPROVAL.equals(definition.getScenario());
    }

    // ── Subflow ───────────────────────────────────────────────────────────────

    private void executeSubflow(FlowExecutionState parentState,
                                CompiledFlowNode parentNode,
                                NodeOutcome.RunSubflow subflowCall,
                                Set<String> flowStack,
                                FlowVariableContext parentCtx,
                                boolean notifyOnComplete) {
        String subflowId = subflowCall.flowId();
        if (flowStack.contains(subflowId)) {
            FlowStatusTransitions.apply(parentState, FlowExecutionStatus.FAILED);
            parentState.setErrorMessage("Recursive subflow invocation is not supported: " + flowStack + " -> " + subflowId);
            auditService.addTrace(parentState, subflowId, parentNode, FlowTraceEventType.FLOW_FAILED,
                    parentState.getErrorMessage());
            return;
        }
        CompiledFlowDefinition subflow = bundleRegistry.getActiveByFlowId(subflowId)
                .orElseThrow(() -> new FlowRuntimeException("Subflow " + subflowId
                        + " cannot run: it was never published, or it is disabled"));
        String subflowName = subflow.getFlowName();
        // A flow's trigger is its start contract: only a flow that declares a Subflow trigger (an
        // Automation flow) runs from a parent.
        if (!FlowScenario.AUTOMATION.equals(subflow.getScenario()) || !(subflow.getTrigger() instanceof SubflowTrigger)) {
            FlowStatusTransitions.apply(parentState, FlowExecutionStatus.FAILED);
            parentState.setErrorMessage("Flow '" + subflowName + "' cannot run as a subflow: only an Automation flow "
                    + "with a Subflow trigger can");
            auditService.addTrace(parentState, subflowId, parentNode, FlowTraceEventType.FLOW_FAILED,
                    parentState.getErrorMessage());
            return;
        }
        auditService.addTrace(parentState, subflowId, parentNode, FlowTraceEventType.SUBFLOW_START,
                "Starting subflow " + subflowName + " (flowId=" + subflowId + ") from node " + parentNode.getNodeId());

        // Fork child context from parent using the outcome's input mapping
        FlowVariableContext childCtx = FlowVariableContextUtils.forkForSubflow(
                parentCtx, subflowCall.inputMapping());

        FlowExecutionState childState = FlowExecutionState.builder()
                .instanceId(parentState.getInstanceId() + ":" + subflowId)
                .bundleId(subflow.getBundleId())
                .flowId(subflow.getFlowId())
                .flowName(subflowName)
                .flowRevision(subflow.getRevision())
                .status(FlowExecutionStatus.RUNNING)
                .inputPayload(new LinkedHashMap<>(childCtx.getInput()))
                .variables(new LinkedHashMap<>(childCtx.getVars()))
                .completedNodeIds(new ArrayList<>())
                .pendingApprovals(new ArrayList<>())
                .approvalAuditDelta(new ArrayList<>())
                .joinArrivalCounts(new LinkedHashMap<>())
                .trace(new ArrayList<>())
                .build();

        Set<String> childFlowStack = new LinkedHashSet<>(flowStack);
        childFlowStack.add(subflowId);
        executeDefinition(subflow, childState, subflow.getEntryNodeIds(), true, childFlowStack, childCtx, notifyOnComplete);
        finalizeRootState(childState, announcesCompletion(subflow, notifyOnComplete));

        FlowExecutionStatus childStatus = childState.getStatus();
        if (FlowExecutionStatus.WAITING.equals(childStatus)) {
            FlowStatusTransitions.apply(parentState, FlowExecutionStatus.FAILED);
            parentState.setErrorMessage("Subflow '" + subflowName
                    + "' paused (" + childStatus + "), which is not supported in synchronous subflow mode");
            auditService.addTrace(parentState, subflowId, parentNode, FlowTraceEventType.FLOW_FAILED,
                    parentState.getErrorMessage());
            return;
        }
        if (FlowExecutionStatus.FAILED.equals(childState.getStatus())) {
            FlowStatusTransitions.apply(parentState, FlowExecutionStatus.FAILED);
            parentState.setErrorMessage(childState.getErrorMessage());
            auditService.addTrace(parentState, subflowId, parentNode, FlowTraceEventType.FLOW_FAILED,
                    childState.getErrorMessage());
            return;
        }

        // Merge child results back into parent context using output mapping
        FlowVariableContextUtils.mergeSubflowResult(
                parentCtx, childCtx,
                subflowCall.outputVariable(),
                subflowCall.outputMapping());

        // Sync parent ctx vars to parent state for persistence
        parentCtx.varsView().forEach((k, v) -> parentState.getVariables().put(k, v));

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
            throw new FlowRuntimeException("Node '" + node.getNodeId()
                    + "' produced multiple outgoing branches without a gateway");
        }
        nextNodeIds.forEach(queue::addLast);
    }

    // ── State finalization ────────────────────────────────────────────────────

    private void finalizeRootState(FlowExecutionState state, boolean notifyOnComplete) {
        if (FlowExecutionStatus.FAILED.equals(state.getStatus())) {
            return;
        }
        // Still parked on any wait — a timer/async token or a pending approval.
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
        if (notifyOnComplete) {
            notificationService.notify(new FlowNotificationEvent.FlowCompleted(state, true));
        }
    }

    private void addCompletedNode(FlowExecutionState state, String nodeId) {
        if (!state.getCompletedNodeIds().contains(nodeId)) {
            state.getCompletedNodeIds().add(nodeId);
        }
    }

    // ── Context construction ──────────────────────────────────────────────────

    private FlowVariableContext buildContextFromState(FlowExecutionState state) {
        Map<String, Object> input = state.getInputPayload() != null ? state.getInputPayload() : Map.of();
        Map<String, Object> vars  = state.getVariables()    != null ? state.getVariables()    : Map.of();
        return FlowVariableContextUtils.fromTriggerPayloadAndVars(input, vars);
    }
}
