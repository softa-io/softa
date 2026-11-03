package io.softa.starter.flow.runtime.action;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.orm.service.ModelService;
import io.softa.framework.orm.utils.IdUtils;
import io.softa.starter.flow.entity.ApprovalModel;
import io.softa.starter.flow.entity.FlowInstance;
import io.softa.starter.flow.enums.ApprovalStatus;
import io.softa.starter.flow.runtime.api.FlowRetryRequest;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.engine.FlowActionContextService;
import io.softa.starter.flow.runtime.engine.FlowAuditService;
import io.softa.starter.flow.runtime.engine.FlowExecutionOrchestrator;
import io.softa.starter.flow.runtime.engine.FlowStatusTransitions;
import io.softa.starter.flow.runtime.exception.FlowActionValidationException;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.ApprovalActionType;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionStatus;
import io.softa.starter.flow.runtime.state.FlowExecutionTraceEntry;
import io.softa.starter.flow.runtime.state.FlowTraceEventType;
import io.softa.starter.flow.service.FlowInstanceService;

/**
 * Runs the steps after an approval again, as an administrator, for an instance that failed in them
 * (zingkey-hcm#2385): the approval goes on from the step it failed after, as it would have had nothing failed. The
 * approval history records who ran it again; should the steps fail again, the instance fails again and its initiator
 * and the tenant's administrators are told again.
 *
 * <p>Only an instance that failed after an approval remembers where to run again from; one that failed otherwise —
 * an automation run, a timer or a callback — is terminated instead. A record's approval runs again only while the
 * record still waits on it: taken back by its submitter, the record is a draft; submitted again, it waits on its new
 * approval. The steps run with the caller's permission checks set aside, as they would after the approver's approval:
 * the flow authorized them.
 */
@Component
public class RetryActionHandler implements FlowActionHandler<FlowRetryRequest> {

    private final FlowActionContextService contextService;
    private final FlowAuditService auditService;
    private final FlowExecutionOrchestrator orchestrator;
    private final FlowInstanceService instanceService;
    private final ModelService<Serializable> modelService;

    @SuppressWarnings("unchecked")
    public RetryActionHandler(FlowActionContextService contextService,
                              FlowAuditService auditService,
                              FlowExecutionOrchestrator orchestrator,
                              FlowInstanceService instanceService,
                              ModelService<?> modelService) {
        this.contextService = contextService;
        this.auditService = auditService;
        this.orchestrator = orchestrator;
        this.instanceService = instanceService;
        this.modelService = (ModelService<Serializable>) modelService;
    }

    @Override
    public FlowExecutionState handle(FlowRetryRequest request) {
        if (request == null || !StringUtils.hasText(request.getInstanceId()) || !StringUtils.hasText(request.getActorId())) {
            throw new FlowActionValidationException("instanceId and actorId are required to run an approval again");
        }
        FlowExecutionState state = contextService.requireState(request.getInstanceId());
        if (!FlowExecutionStatus.FAILED.equals(state.getStatus()) || !StringUtils.hasText(state.getRetryAfterNodeId())) {
            throw new FlowActionValidationException("Only an approval whose steps after an approval failed can be run "
                    + "again; terminate any other failed instance instead.");
        }
        if (state.isApprovalOfRecord()) {
            requireRecordStillWaitsOn(state);
        }
        CompiledFlowDefinition definition = contextService.resolveDefinition(state.getBundleId());
        CompiledFlowNode approved = contextService.requiredNode(definition, state.getRetryAfterNodeId());
        String failedNodeId = state.getFailedNodeId();
        String error = state.getErrorMessage();

        state.setErrorMessage(null);
        state.setFailedNodeId(null);
        FlowStatusTransitions.apply(state, FlowExecutionStatus.RUNNING);
        state.getTrace().add(FlowExecutionTraceEntry.builder()
                .flowId(definition.getFlowId())
                .nodeId(approved.getNodeId())
                .flowNodeType(approved.getType())
                .eventType(FlowTraceEventType.FLOW_RETRIED)
                .eventTime(LocalDateTime.now())
                .message("Run again by " + request.getActorId() + " after failing at " + failedNodeId + ": " + error)
                .build());
        auditService.appendApprovalAudit(state, ApprovalActionAuditEntry.builder()
                .action(ApprovalActionType.RETRY)
                .flowId(definition.getFlowId())
                .flowRevision(definition.getRevision())
                .nodeId(approved.getNodeId())
                .nodeLabel(approved.getLabel())
                .actorId(request.getActorId())
                .comment(request.getComment())
                .statusBefore(FlowExecutionStatus.FAILED)
                .statusAfter(FlowExecutionStatus.RUNNING)
                .build());
        FlowExecutionState resumed = ContextUtils.withoutPermissionChecks(
                () -> orchestrator.runAfterApproval(state, definition, approved));
        contextService.persistState(resumed);
        return resumed;
    }

    /** The record waits on this approval still: in approval, and this its latest. */
    private void requireRecordStillWaitsOn(FlowExecutionState state) {
        List<FlowInstance> approvals = ContextUtils.withoutPermissionChecks(
                () -> instanceService.findByModelNameAndRowId(state.getModelName(), state.getRowId()));
        FlowInstance latest = approvals.stream()
                .filter(instance -> Boolean.TRUE.equals(instance.getRecordApproval()))
                .max(Comparator.comparing(FlowInstance::getCreatedTime, Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
        if (latest != null && !Objects.equals(latest.getInstanceId(), state.getInstanceId())) {
            throw new FlowActionValidationException("The record was submitted again after this approval failed: "
                    + "it waits on its new approval, and this one cannot run again.");
        }
        Serializable id = IdUtils.formatId(state.getModelName(), state.getRowId());
        Map<String, Object> row = ContextUtils.withoutPermissionChecks(
                () -> modelService.getById(state.getModelName(), id, List.of(ApprovalModel.APPROVAL_STATUS))).orElse(null);
        if (row == null || ApprovalStatus.from(row.get(ApprovalModel.APPROVAL_STATUS)) != ApprovalStatus.PENDING) {
            throw new FlowActionValidationException("The record is no longer in this approval — its submitter took it "
                    + "back, or it was removed — so the approval cannot run again.");
        }
    }
}
