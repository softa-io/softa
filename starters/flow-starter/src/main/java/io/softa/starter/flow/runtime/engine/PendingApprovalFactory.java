package io.softa.starter.flow.runtime.engine;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.starter.flow.enums.EmptyApproverStrategy;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.exception.FlowNoApproverException;
import io.softa.starter.flow.runtime.nodeconfig.ApprovalNodeConfig;
import io.softa.starter.flow.runtime.spi.ApprovalTimeoutConfig;
import io.softa.starter.flow.runtime.state.ApprovalActionAuditEntry;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.PendingApproval;

/**
 * Builds and updates pending approval runtime state from compiled approval nodes.
 */
@Component
public class PendingApprovalFactory {

    private final ApproverResolutionService approverResolutionService;
    private final ApprovalAuditReader auditReader;

    @Autowired(required = false)
    private AutoDelegationService autoDelegationService;

    public PendingApprovalFactory(ApproverResolutionService approverResolutionService,
                                  ApprovalAuditReader auditReader) {
        this.approverResolutionService = approverResolutionService;
        this.auditReader = auditReader;
    }

    /**
     * Build a pending approval for the given node. A submitted record's submitter stays among the
     * step's approvers: {@link ApproverDedupService} counts them as having approved.
     *
     * @return the pending approval, or {@code null} when the node resolves to nobody and its
     *         empty-approver strategy skips or auto-approves it — the caller tells which with
     *         {@link #resolveEmptyApproverStrategy}
     * @throws FlowNoApproverException when the node resolves to nobody and its strategy is ERROR: the action that
     *         reached it is refused
     */
    public PendingApproval buildPendingApproval(CompiledFlowDefinition definition,
                                                CompiledFlowNode node,
                                                FlowExecutionState state) {
        PendingApproval pendingApproval = resolvePendingApproval(definition, node, state, true);
        if (pendingApproval == null && EmptyApproverStrategy.ERROR.equals(resolveEmptyApproverStrategy(node))) {
            throw new FlowNoApproverException(node.getNodeId(), node.getLabel());
        }
        return pendingApproval;
    }

    /**
     * The pending approval {@link #buildPendingApproval} would build, for the approval path preview:
     * the same approvers, delegated the same way, with nothing recorded — a delegation's use is
     * counted only when a step really goes to its delegate.
     *
     * @return the pending approval, or {@code null} when the node resolves to nobody, whatever its
     *         empty-approver strategy
     */
    PendingApproval previewPendingApproval(CompiledFlowDefinition definition,
                                           CompiledFlowNode node,
                                           FlowExecutionState state) {
        return resolvePendingApproval(definition, node, state, false);
    }

    /** Why a node that resolves to nobody is refused under its {@code ERROR} empty-approver strategy. */
    static String emptyApproverMessage(CompiledFlowNode node) {
        return FlowNoApproverException.messageOf(node.getNodeId(), node.getLabel());
    }

    private PendingApproval resolvePendingApproval(CompiledFlowDefinition definition,
                                                   CompiledFlowNode node,
                                                   FlowExecutionState state,
                                                   boolean recordDelegationUsage) {
        List<String> approvers = approverResolutionService.resolveApprovers(node,
                ApproverResolutionService.resolutionVariables(state == null ? null : state.getVariables(),
                        state == null ? null : state.getInitiatorId()));

        if (approvers.isEmpty()) {
            return null;
        }
        PendingApproval pendingApproval = PendingApproval.builder()
                .flowId(definition.getFlowId())
                .flowRevision(definition.getRevision())
                .nodeId(node.getNodeId())
                .nodeLabel(node.getLabel())
                .cycleNumber(nextCycleNumberForNode(state, node.getNodeId()))
                .approvers(approvers)
                .dynamicApprovers(approverResolutionService.hasDynamicApproverSource(node))
                .approvalMode(approverResolutionService.resolveApprovalMode(node))
                .requiredApprovalCount(approverResolutionService.resolveRequiredApprovalCount(node, approvers))
                .totalApproverCount(approvers.size())
                .approvedActors(new ArrayList<>())
                .rejectMode(approverResolutionService.resolveRejectMode(node))
                .requiredRejectCount(approverResolutionService.resolveRequiredRejectCount(node, approvers))
                .rejectedActors(new ArrayList<>())
                .dueTime(computeDueTime(node))
                .remindCount(0)
                .build();

        // Apply auto-delegation rules if available
        if (autoDelegationService != null) {
            autoDelegationService.applyAutoDelegation(pendingApproval, state, node, recordDelegationUsage);
        }

        return pendingApproval;
    }

    public void recomputePendingApprovalThresholds(PendingApproval pendingApproval, CompiledFlowNode node) {
        pendingApproval.setTotalApproverCount(pendingApproval.getApprovers().size());
        pendingApproval.setRequiredApprovalCount(
                approverResolutionService.resolveRequiredApprovalCount(node, pendingApproval.getApprovers()));
        pendingApproval.setRequiredRejectCount(
                approverResolutionService.resolveRequiredRejectCount(node, pendingApproval.getApprovers()));
    }

    private Integer nextCycleNumberForNode(FlowExecutionState state, String nodeId) {
        int maxCycle = 0;
        if (state != null) {
            if (state.getPendingApprovals() != null) {
                maxCycle = Math.max(maxCycle, state.getPendingApprovals().stream()
                        .filter(item -> Objects.equals(nodeId, item.getNodeId()))
                        .map(PendingApproval::getCycleNumber)
                        .filter(Objects::nonNull)
                        .max(Integer::compareTo)
                        .orElse(0));
            }
            maxCycle = Math.max(maxCycle, auditReader.fullHistory(state).stream()
                    .filter(entry -> Objects.equals(nodeId, entry.getNodeId()))
                    .map(ApprovalActionAuditEntry::getCycleNumber)
                    .filter(Objects::nonNull)
                    .max(Integer::compareTo)
                    .orElse(0));
        }
        return maxCycle + 1;
    }

    /**
     * Read empty-approver strategy from node config, defaulting to {@link EmptyApproverStrategy#ERROR}.
     */
    EmptyApproverStrategy resolveEmptyApproverStrategy(CompiledFlowNode node) {
        if (node.getParsedConfig() instanceof ApprovalNodeConfig cfg && cfg.getEmptyApproverStrategy() != null) {
            return cfg.getEmptyApproverStrategy();
        }
        return EmptyApproverStrategy.ERROR;
    }

    /**
     * Compute due time from the node's timeout configuration.
     * Returns {@code null} if no timeout is configured.
     */
    private LocalDateTime computeDueTime(CompiledFlowNode node) {
        ApprovalTimeoutConfig config = resolveTimeoutConfig(node);
        if (config == null || config.getTimeoutHours() == null || config.getTimeoutHours() <= 0) {
            return null;
        }
        return LocalDateTime.now().plusHours(config.getTimeoutHours());
    }

    /**
     * Extract {@link ApprovalTimeoutConfig} from node config.
     */
    ApprovalTimeoutConfig resolveTimeoutConfig(CompiledFlowNode node) {
        if (node.getParsedConfig() instanceof ApprovalNodeConfig cfg) {
            return cfg.getTimeout();
        }
        return null;
    }
}
