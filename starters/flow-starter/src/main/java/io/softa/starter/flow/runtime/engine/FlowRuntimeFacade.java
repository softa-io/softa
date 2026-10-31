package io.softa.starter.flow.runtime.engine;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.softa.starter.flow.design.trigger.ApiTrigger;
import io.softa.starter.flow.enums.CcTiming;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.action.*;
import io.softa.starter.flow.runtime.api.*;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.store.FlowInstanceStore;

/**
 * Facade implementing {@link FlowRuntimeEngine} that delegates to focused action handlers
 * and domain services.
 */
@Service
@Transactional(rollbackFor = Exception.class)
public class FlowRuntimeFacade implements FlowRuntimeEngine {

    private final FlowActionContextService contextService;
    private final FlowExecutionOrchestrator orchestrator;
    private final FlowInstanceStore instanceStore;
    private final AutoCcService autoCcService;
    private final ApproveActionHandler approveHandler;
    private final RejectActionHandler rejectHandler;
    private final TransferActionHandler transferHandler;
    private final DelegateActionHandler delegateHandler;
    private final AddSignBeforeActionHandler addSignBeforeHandler;
    private final AddSignAfterActionHandler addSignAfterHandler;
    private final CcActionHandler ccHandler;
    private final ReadCcActionHandler readCcHandler;
    private final ReturnApprovalActionHandler returnApprovalHandler;
    private final WithdrawActionHandler withdrawHandler;
    private final UrgeActionHandler urgeHandler;
    private final CommentActionHandler commentHandler;
    private final TerminateActionHandler terminateHandler;
    private final ReassignActionHandler reassignHandler;

    public FlowRuntimeFacade(FlowActionContextService contextService,
                             FlowExecutionOrchestrator orchestrator,
                             FlowInstanceStore instanceStore,
                             AutoCcService autoCcService,
                             ApproveActionHandler approveHandler,
                             RejectActionHandler rejectHandler,
                             TransferActionHandler transferHandler,
                             DelegateActionHandler delegateHandler,
                             AddSignBeforeActionHandler addSignBeforeHandler,
                             AddSignAfterActionHandler addSignAfterHandler,
                             CcActionHandler ccHandler,
                             ReadCcActionHandler readCcHandler,
                             ReturnApprovalActionHandler returnApprovalHandler,
                             WithdrawActionHandler withdrawHandler,
                             UrgeActionHandler urgeHandler,
                             CommentActionHandler commentHandler,
                             TerminateActionHandler terminateHandler,
                             ReassignActionHandler reassignHandler) {
        this.contextService = contextService;
        this.orchestrator = orchestrator;
        this.instanceStore = instanceStore;
        this.autoCcService = autoCcService;
        this.approveHandler = approveHandler;
        this.rejectHandler = rejectHandler;
        this.transferHandler = transferHandler;
        this.delegateHandler = delegateHandler;
        this.addSignBeforeHandler = addSignBeforeHandler;
        this.addSignAfterHandler = addSignAfterHandler;
        this.ccHandler = ccHandler;
        this.readCcHandler = readCcHandler;
        this.returnApprovalHandler = returnApprovalHandler;
        this.withdrawHandler = withdrawHandler;
        this.urgeHandler = urgeHandler;
        this.commentHandler = commentHandler;
        this.terminateHandler = terminateHandler;
        this.reassignHandler = reassignHandler;
    }

    @Override
    public FlowExecutionState start(FlowStartRequest request) {
        return startInstance(resolveRequestedDefinition(request), request, false);
    }

    @Override
    public FlowExecutionState startAutomation(FlowStartRequest request) {
        CompiledFlowDefinition definition = resolveRequestedDefinition(request);
        if (!FlowScenario.AUTOMATION.equals(definition.getScenario())
                || !(definition.getTrigger() instanceof ApiTrigger)) {
            throw new FlowActionValidationException("Flow '" + definition.getFlowName()
                    + "' cannot be started explicitly: only an Automation flow with an Api trigger can be");
        }
        if (request.getBundleId() != null) {
            // A pinned revision starts only while its flow is on, as a start by flowId does.
            contextService.resolveDefinitionByFlowId(definition.getFlowId());
        }
        return startInstance(definition, request, false);
    }

    @Override
    public FlowExecutionState startRecordApproval(FlowStartRequest request) {
        return startInstance(resolveRequestedDefinition(request), request, true);
    }

    private FlowExecutionState startInstance(CompiledFlowDefinition definition, FlowStartRequest request,
                                             boolean recordApproval) {
        FlowExecutionState state = buildInitialState(definition, request, recordApproval);

        try {
            orchestrator.executeRoot(definition, state);
        } catch (RuntimeException e) {
            orchestrator.failState(state, definition.getFlowId(), null, e.getMessage());
            // Record the FAILED instance in its own transaction so it survives this method's
            // rollback — the failed node's partial writes still roll back with the outer transaction,
            // but operators keep a record of the failed start.
            contextService.persistStateInNewTransaction(state);
            throw e;
        }
        contextService.persistState(state);
        // Submitting happens at the flow's start node; an approval every step of which passed on its own
        // completes right there.
        List<String> entryNodeIds = definition.getEntryNodeIds();
        if (entryNodeIds != null && !entryNodeIds.isEmpty()) {
            CompiledFlowNode startNode = contextService.requiredNode(definition, entryNodeIds.getFirst());
            autoCcService.processCc(state, startNode, CcTiming.ON_SUBMIT);
            if (FlowExecutionStatus.COMPLETED.equals(state.getStatus())) {
                autoCcService.processCc(state, startNode, CcTiming.ON_APPROVED);
            }
        }
        return state;
    }

    @Override
    public FlowExecutionState evaluate(FlowStartRequest request) {
        CompiledFlowDefinition definition = resolveRequestedDefinition(request);
        if (definition.getScenario() == null || !definition.getScenario().isTransient()) {
            throw new FlowActionValidationException("evaluate() only runs stateless Validation/Compute flows; "
                    + "Approval and Automation flows start instances: " + definition.getFlowName());
        }
        FlowExecutionState state = buildInitialState(definition, request, false);
        // Zero flow footprint: nothing is persisted on success or failure. A failure propagates
        // so the caller's transaction rolls back any business writes made by task nodes.
        orchestrator.evaluateRoot(definition, state);
        return state;
    }

    private CompiledFlowDefinition resolveRequestedDefinition(FlowStartRequest request) {
        if (request == null) {
            throw new FlowActionValidationException("FlowStartRequest must not be null");
        }
        // Resolution priority: bundleId > flowId
        if (request.getBundleId() != null) {
            return contextService.resolveDefinition(request.getBundleId());
        }
        if (request.getFlowId() != null) {
            return contextService.resolveDefinitionByFlowId(request.getFlowId());
        }
        throw new FlowActionValidationException(
                "bundleId or flowId is required to start execution");
    }

    private FlowExecutionState buildInitialState(CompiledFlowDefinition definition, FlowStartRequest request,
                                                 boolean recordApproval) {
        return initialState(definition, request, recordApproval, UUID.randomUUID().toString());
    }

    /**
     * The state an instance of {@code definition} starts from. The approval path preview walks from it
     * with no instance id: nothing is started, and the state is never saved.
     */
    static FlowExecutionState initialState(CompiledFlowDefinition definition, FlowStartRequest request,
                                           boolean recordApproval, String instanceId) {
        Map<String, Object> vars = new LinkedHashMap<>(
                request.getVariables() == null ? Map.of() : request.getVariables());
        vars.put("_instanceId", instanceId);
        if (definition.getBundleId() != null) vars.put("_bundleId", definition.getBundleId());
        if (definition.getFlowId() != null) vars.put("_flowId", definition.getFlowId());
        vars.put("_flowRevision", definition.getRevision());

        return FlowExecutionState.builder()
                .instanceId(instanceId)
                .bundleId(definition.getBundleId())
                .flowId(definition.getFlowId())
                .flowName(definition.getFlowName())
                .flowRevision(definition.getRevision())
                .title(request.getTitle())
                .modelName(request.getModelName())
                .rowId(request.getRowId())
                .initiatorId(request.getInitiatorId())
                .recordApproval(recordApproval)
                .subjectId(request.getSubjectId())
                .departmentId(request.getDepartmentId())
                .effectiveDate(request.getEffectiveDate())
                .recordCode(request.getRecordCode())
                .triggerEventId(request.getTriggerEventId())
                .status(FlowExecutionStatus.RUNNING)
                .inputPayload(Map.of())
                .variables(vars)
                .completedNodeIds(new ArrayList<>())
                .pendingApprovals(new ArrayList<>())
                .approvalAuditDelta(new ArrayList<>())
                .joinArrivalCounts(new LinkedHashMap<>())
                .trace(new ArrayList<>())
                .build();
    }

    @Override
    public FlowExecutionState approve(FlowApproveRequest request) {
        FlowExecutionState state = approveHandler.handle(request);
        // The approval's CC: once the approval ends approved, not at each step.
        if (FlowExecutionStatus.COMPLETED.equals(state.getStatus())) {
            autoCcService.processCc(state, actedStep(state, request.getNodeId()), CcTiming.ON_APPROVED);
        }
        return state;
    }

    @Override
    public FlowExecutionState reject(FlowRejectRequest request) {
        FlowExecutionState state = rejectHandler.handle(request);
        // A rejection that the step's reject threshold does not yet carry leaves the approval waiting.
        if (FlowExecutionStatus.REJECTED.equals(state.getStatus())) {
            autoCcService.processCc(state, actedStep(state, request.getNodeId()), CcTiming.ON_REJECTED);
        }
        return state;
    }

    /** The step whose action ended the approval: the flow's CCs at that moment are recorded on it. */
    private CompiledFlowNode actedStep(FlowExecutionState state, String nodeId) {
        return contextService.requiredNode(contextService.resolveDefinition(state.getBundleId()), nodeId);
    }

    @Override
    public FlowExecutionState transfer(FlowTransferRequest request) {
        return transferHandler.handle(request);
    }

    @Override
    public FlowExecutionState delegate(FlowDelegateRequest request) {
        return delegateHandler.handle(request);
    }

    @Override
    public FlowExecutionState addSignBefore(FlowAddSignBeforeRequest request) {
        return addSignBeforeHandler.handle(request);
    }

    @Override
    public FlowExecutionState addSignAfter(FlowAddSignAfterRequest request) {
        return addSignAfterHandler.handle(request);
    }

    @Override
    public FlowExecutionState cc(FlowCcRequest request) {
        return ccHandler.handle(request);
    }

    @Override
    public FlowExecutionState readCc(FlowCcReadRequest request) {
        return readCcHandler.handle(request);
    }

    @Override
    public FlowExecutionState returnApproval(FlowReturnRequest request) {
        return returnApprovalHandler.handle(request);
    }

    @Override
    public FlowExecutionState withdraw(FlowWithdrawRequest request) {
        return withdrawHandler.handle(request);
    }

    @Override
    public FlowExecutionState terminate(FlowTerminateRequest request) {
        return terminateHandler.handle(request);
    }

    @Override
    public FlowExecutionState reassign(FlowReassignRequest request) {
        return reassignHandler.handle(request);
    }

    @Override
    public FlowExecutionState urge(FlowUrgeRequest request) {
        return urgeHandler.handle(request);
    }

    @Override
    public FlowExecutionState urgeAsAdministrator(FlowUrgeRequest request) {
        return urgeHandler.handle(request, true);
    }

    @Override
    public FlowExecutionState addComment(FlowCommentRequest request) {
        return commentHandler.handle(request);
    }

    @Override
    public Optional<FlowExecutionState> getInstance(String instanceId) {
        return instanceStore.get(instanceId);
    }

    @Override
    public Optional<FlowExecutionState> getInstanceWithTrace(String instanceId) {
        return instanceStore.getWithTrace(instanceId);
    }

    @Override
    public FlowExecutionState resumeAsyncTask(String instanceId, String nodeId,
                                              Map<String, Object> callbackOutputs) {
        return resumeSuspended(instanceId, nodeId, callbackOutputs == null ? Map.of() : callbackOutputs);
    }

    @Override
    public FlowExecutionState resumeTimer(String instanceId, String nodeId) {
        return resumeSuspended(instanceId, nodeId, Map.of());
    }

    /**
     * Shared timer/async resume template with the same failure semantics as {@link #start}:
     * record the FAILED instance in its own transaction (the consumed wait token is already
     * removed in memory, so redeliveries no-op against the terminal state), then rethrow so
     * the failed attempt's partial business writes roll back with this transaction instead
     * of committing alongside the failure.
     */
    private FlowExecutionState resumeSuspended(String instanceId, String nodeId,
                                               Map<String, Object> callbackOutputs) {
        FlowExecutionState state = instanceStore.get(instanceId)
                .orElseThrow(() -> new FlowRuntimeException("Flow instance not found: " + instanceId));
        CompiledFlowDefinition definition = contextService.resolveDefinition(state.getBundleId());
        try {
            orchestrator.resumeFromSuspendedNode(state, definition, nodeId, callbackOutputs);
        } catch (RuntimeException e) {
            orchestrator.failState(state, state.getFlowId(), null, e.getMessage());
            contextService.persistStateInNewTransaction(state);
            throw e;
        }
        contextService.persistState(state);
        return state;
    }
}
