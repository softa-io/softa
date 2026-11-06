package io.softa.starter.flow.runtime.engine;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.starter.flow.enums.EmptyApproverStrategy;
import io.softa.starter.flow.enums.VoteThresholdMode;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.exception.FlowNoApproverException;
import io.softa.starter.flow.runtime.nodeconfig.ApprovalNodeConfig;
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
     * @return the pending approval — under AssignToRole, the holders of its roles when the node itself resolves to
     *         nobody — or {@code null} when the node resolves to nobody and its empty-approver strategy skips or
     *         auto-approves it — the caller tells which with {@link #resolveEmptyApproverStrategy}
     * @throws FlowNoApproverException when the node resolves to nobody and its strategy is ERROR, or AssignToRole and
     *         its roles leave nobody either: the action that reached it is refused
     */
    public PendingApproval buildPendingApproval(CompiledFlowDefinition definition,
                                                CompiledFlowNode node,
                                                FlowExecutionState state) {
        PendingApproval pendingApproval = resolvePendingApproval(definition, node, state, true);
        if (pendingApproval == null) {
            switch (resolveEmptyApproverStrategy(node)) {
                case ERROR -> throw new FlowNoApproverException(node.getNodeId(), node.getLabel(), whyNobody(node, state));
                case ASSIGN_TO_ROLE -> throw FlowNoApproverException.inRoles(node.getNodeId(), node.getLabel(),
                        approverResolutionService.emptyApproverRoleNames(node));
                case SKIP, AUTO_APPROVE -> {
                    // The caller skips or approves it.
                }
            }
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

    /**
     * Why a node that resolves to nobody is refused: under {@code ERROR}, saying why it found nobody where its source
     * can tell; or {@code AssignToRole} leaving nobody.
     */
    String emptyApproverMessage(CompiledFlowNode node, FlowExecutionState state) {
        return EmptyApproverStrategy.ASSIGN_TO_ROLE.equals(resolveEmptyApproverStrategy(node))
                ? FlowNoApproverException.messageOf(node.getNodeId(), node.getLabel(),
                        approverResolutionService.emptyApproverRoleNames(node))
                : FlowNoApproverException.messageOf(node.getNodeId(), node.getLabel(), whyNobody(node, state));
    }

    private String whyNobody(CompiledFlowNode node, FlowExecutionState state) {
        return approverResolutionService.whyNobody(node, ApproverResolutionService.resolutionVariables(
                state == null ? null : state.getVariables(), state == null ? null : state.getInitiatorId()));
    }

    private PendingApproval resolvePendingApproval(CompiledFlowDefinition definition,
                                                   CompiledFlowNode node,
                                                   FlowExecutionState state,
                                                   boolean recordDelegationUsage) {
        Map<String, Object> variables = state == null ? null : state.getVariables();
        String initiatorId = state == null ? null : state.getInitiatorId();
        List<String> approvers = approverResolutionService.resolveApprovers(node,
                ApproverResolutionService.resolutionVariables(variables, initiatorId));
        List<String> assignedRoles = null;
        if (approvers.isEmpty() && EmptyApproverStrategy.ASSIGN_TO_ROLE.equals(resolveEmptyApproverStrategy(node))) {
            // Found nobody: the holders of its roles take it, any one of whom decides.
            approvers = approverResolutionService.resolveEmptyApproverRoleHolders(node, variables, initiatorId);
            assignedRoles = approvers.isEmpty() ? null : approverResolutionService.emptyApproverRoleNames(node);
        }

        if (approvers.isEmpty()) {
            return null;
        }
        boolean toRoles = assignedRoles != null;
        PendingApproval pendingApproval = PendingApproval.builder()
                .flowId(definition.getFlowId())
                .flowRevision(definition.getRevision())
                .nodeId(node.getNodeId())
                .nodeLabel(node.getLabel())
                .cycleNumber(nextCycleNumberForNode(state, node.getNodeId()))
                .approvers(approvers)
                .dynamicApprovers(toRoles || approverResolutionService.hasDynamicApproverSource(node))
                .approvalMode(toRoles ? VoteThresholdMode.ANY_ONE : approverResolutionService.resolveApprovalMode(node))
                .requiredApprovalCount(toRoles ? 1 : approverResolutionService.resolveRequiredApprovalCount(node, approvers))
                .totalApproverCount(approvers.size())
                .approvedActors(new ArrayList<>())
                .dueTime(computeDueTime(definition))
                .remindCount(0)
                .assignedRoles(assignedRoles)
                .build();

        // Apply auto-delegation rules if available
        if (autoDelegationService != null) {
            autoDelegationService.applyAutoDelegation(pendingApproval, state, node, recordDelegationUsage);
        }

        return pendingApproval;
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
     * When the step is due: its flow's {@code remindAfterHours} after it opens — its approvers still to act are
     * reminded then — or {@code null} when the flow sends no reminders.
     */
    private static LocalDateTime computeDueTime(CompiledFlowDefinition definition) {
        Integer hours = definition.getRemindAfterHours();
        return hours == null || hours < 1 ? null : LocalDateTime.now().plusHours(hours);
    }
}
