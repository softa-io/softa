package io.softa.starter.flow.runtime.engine;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.softa.starter.flow.enums.CcTiming;
import io.softa.starter.flow.enums.FlowScenario;
import io.softa.starter.flow.runtime.action.*;
import io.softa.starter.flow.runtime.api.*;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
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
    private final BatchCcActionHandler batchCcHandler;
    private final ReadCcActionHandler readCcHandler;
    private final ReturnApprovalActionHandler returnApprovalHandler;
    private final ResubmitActionHandler resubmitHandler;
    private final WithdrawActionHandler withdrawHandler;
    private final UrgeActionHandler urgeHandler;
    private final CommentActionHandler commentHandler;

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
                             BatchCcActionHandler batchCcHandler,
                             ReadCcActionHandler readCcHandler,
                             ReturnApprovalActionHandler returnApprovalHandler,
                             ResubmitActionHandler resubmitHandler,
                             WithdrawActionHandler withdrawHandler,
                             UrgeActionHandler urgeHandler,
                             CommentActionHandler commentHandler) {
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
        this.batchCcHandler = batchCcHandler;
        this.readCcHandler = readCcHandler;
        this.returnApprovalHandler = returnApprovalHandler;
        this.resubmitHandler = resubmitHandler;
        this.withdrawHandler = withdrawHandler;
        this.urgeHandler = urgeHandler;
        this.commentHandler = commentHandler;
    }

    @Override
    public FlowExecutionState start(FlowStartRequest request) {
        CompiledFlowDefinition definition = resolveRequestedDefinition(request);
        FlowExecutionState state = buildInitialState(definition, request);

        try {
            orchestrator.executeRoot(definition, state);
        } catch (RuntimeException e) {
            orchestrator.failState(state, definition.getFlowCode(), null, e.getMessage());
            // Record the FAILED instance in its own transaction so it survives this method's
            // rollback — the failed node's partial writes still roll back with the outer transaction,
            // but operators keep a record of the failed start.
            contextService.persistStateInNewTransaction(state);
            throw e;
        }
        contextService.persistState(state);
        autoCcService.processCc(state, null, CcTiming.ON_SUBMIT);
        return state;
    }

    @Override
    public FlowExecutionState evaluate(FlowStartRequest request) {
        CompiledFlowDefinition definition = resolveRequestedDefinition(request);
        if (definition.getScenario() == null || FlowScenario.PROCESS.equals(definition.getScenario())) {
            throw new FlowActionValidationException("evaluate() only runs stateless Validation/Compute flows; "
                    + "use start() for Process flows: " + definition.getFlowCode());
        }
        FlowExecutionState state = buildInitialState(definition, request);
        // Zero flow footprint: nothing is persisted on success or failure. A failure propagates
        // so the caller's transaction rolls back any business writes made by task nodes.
        orchestrator.evaluateRoot(definition, state);
        return state;
    }

    private CompiledFlowDefinition resolveRequestedDefinition(FlowStartRequest request) {
        if (request == null) {
            throw new FlowActionValidationException("FlowStartRequest must not be null");
        }
        // Resolution priority: bundleId > designId
        if (request.getBundleId() != null) {
            return contextService.resolveDefinition(request.getBundleId());
        }
        if (request.getDesignId() != null) {
            return contextService.resolveDefinitionByDesignId(request.getDesignId());
        }
        throw new FlowActionValidationException(
                "bundleId or designId is required to start execution");
    }

    private FlowExecutionState buildInitialState(CompiledFlowDefinition definition, FlowStartRequest request) {
        String instanceId = UUID.randomUUID().toString();
        Map<String, Object> vars = new LinkedHashMap<>(
                request.getVariables() == null ? Map.of() : request.getVariables());
        vars.put("_instanceId", instanceId);
        if (definition.getBundleId() != null) vars.put("_bundleId", definition.getBundleId());
        if (definition.getDesignId() != null) vars.put("_designId", definition.getDesignId());
        vars.put("_flowCode", definition.getFlowCode());
        vars.put("_flowRevision", definition.getRevision());

        return FlowExecutionState.builder()
                .instanceId(instanceId)
                .bundleId(definition.getBundleId())
                .designId(definition.getDesignId())
                .flowCode(definition.getFlowCode())
                .flowRevision(definition.getRevision())
                .title(request.getTitle())
                .modelName(request.getModelName())
                .rowId(request.getRowId())
                .initiatorId(request.getInitiatorId())
                .status(FlowExecutionStatus.RUNNING)
                .inputPayload(Map.of())
                .variables(vars)
                .completedNodeIds(new ArrayList<>())
                .pendingApprovals(new ArrayList<>())
                .returnedApproval(null)
                .approvalAuditDelta(new ArrayList<>())
                .resubmissionCount(0)
                .joinArrivalCounts(new LinkedHashMap<>())
                .trace(new ArrayList<>())
                .build();
    }

    @Override
    public FlowExecutionState approve(FlowApproveRequest request) {
        FlowExecutionState state = approveHandler.handle(request);
        autoCcService.processCc(state, null, CcTiming.ON_APPROVE);
        return state;
    }

    @Override
    public FlowExecutionState reject(FlowRejectRequest request) {
        FlowExecutionState state = rejectHandler.handle(request);
        autoCcService.processCc(state, null, CcTiming.ON_REJECT);
        return state;
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
    public FlowExecutionState batchCc(FlowBatchCcRequest request) {
        return batchCcHandler.handle(request);
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
    public FlowExecutionState resubmit(FlowResubmitRequest request) {
        return resubmitHandler.handle(request);
    }

    @Override
    public FlowExecutionState withdraw(FlowWithdrawRequest request) {
        return withdrawHandler.handle(request);
    }

    @Override
    public FlowExecutionState urge(FlowUrgeRequest request) {
        return urgeHandler.handle(request);
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
            orchestrator.failState(state, state.getFlowCode(), null, e.getMessage());
            contextService.persistStateInNewTransaction(state);
            throw e;
        }
        contextService.persistState(state);
        return state;
    }
}
