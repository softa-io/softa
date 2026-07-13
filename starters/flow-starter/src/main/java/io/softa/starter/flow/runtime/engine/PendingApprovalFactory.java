package io.softa.starter.flow.runtime.engine;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.softa.starter.flow.enums.EmptyApproverStrategy;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.exception.FlowRuntimeException;
import io.softa.starter.flow.runtime.nodeconfig.ApprovalNodeConfig;
import io.softa.starter.flow.runtime.spi.ApprovalTimeoutConfig;
import io.softa.starter.flow.runtime.spi.OrganizationService;
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

    @Autowired(required = false)
    private OrganizationService organizationService;

    public PendingApprovalFactory(ApproverResolutionService approverResolutionService,
                                  ApprovalAuditReader auditReader) {
        this.approverResolutionService = approverResolutionService;
        this.auditReader = auditReader;
    }

    public PendingApproval copyPendingApproval(PendingApproval pendingApproval) {
        return PendingApproval.builder()
                .flowCode(pendingApproval.getFlowCode())
                .flowRevision(pendingApproval.getFlowRevision())
                .nodeId(pendingApproval.getNodeId())
                .nodeLabel(pendingApproval.getNodeLabel())
                .cycleNumber(pendingApproval.getCycleNumber())
                .approvers(pendingApproval.getApprovers() == null ? List.of() : List.copyOf(pendingApproval.getApprovers()))
                .dynamicApprovers(pendingApproval.getDynamicApprovers())
                .approvalMode(pendingApproval.getApprovalMode())
                .requiredApprovalCount(pendingApproval.getRequiredApprovalCount())
                .totalApproverCount(pendingApproval.getTotalApproverCount())
                .approvedActors(pendingApproval.getApprovedActors() == null ? new ArrayList<>() : new ArrayList<>(pendingApproval.getApprovedActors()))
                .rejectMode(pendingApproval.getRejectMode())
                .requiredRejectCount(pendingApproval.getRequiredRejectCount())
                .rejectedActors(pendingApproval.getRejectedActors() == null ? new ArrayList<>() : new ArrayList<>(pendingApproval.getRejectedActors()))
                .blockedActorId(pendingApproval.getBlockedActorId())
                .prerequisiteActorId(pendingApproval.getPrerequisiteActorId())
                .dueTime(pendingApproval.getDueTime())
                .remindCount(pendingApproval.getRemindCount())
                .lastRemindTime(pendingApproval.getLastRemindTime())
                .build();
    }

    public PendingApproval resetPendingApprovalProgress(PendingApproval pendingApproval) {
        PendingApproval copy = copyPendingApproval(pendingApproval);
        copy.setApprovedActors(new ArrayList<>());
        copy.setRejectedActors(new ArrayList<>());
        copy.setBlockedActorId(null);
        copy.setPrerequisiteActorId(null);
        return copy;
    }

    /**
     * Build a pending approval for the given node.
     *
     * @return the pending approval, or {@code null} if the empty-approver strategy
     *         dictates that this node should be skipped / auto-approved.
     */
    public PendingApproval buildPendingApproval(CompiledFlowDefinition definition,
                                                CompiledFlowNode node,
                                                FlowExecutionState state) {
        Map<String, Object> resolutionVariables = buildApproverResolutionVariables(state == null ? null : state.getVariables(),
                state == null ? null : state.getInitiatorId());
        List<String> approvers = approverResolutionService.resolveApprovers(node, resolutionVariables);

        if (approvers.isEmpty()) {
            EmptyApproverStrategy strategy = resolveEmptyApproverStrategy(node);
            return switch (strategy) {
                case SKIP, AUTO_APPROVE -> null;  // caller treats null as "skip this node"
                case ERROR -> throw new FlowRuntimeException(
                        "Approval node '" + node.getNodeId() + "' resolved an empty approver list (strategy=ERROR)");
            };
        }
        PendingApproval pendingApproval = PendingApproval.builder()
                .flowCode(definition.getFlowCode())
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
            autoDelegationService.applyAutoDelegation(pendingApproval, state, node);
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
            if (state.getReturnedApproval() != null && state.getReturnedApproval().getPendingApproval() != null
                    && Objects.equals(nodeId, state.getReturnedApproval().getPendingApproval().getNodeId())
                    && state.getReturnedApproval().getPendingApproval().getCycleNumber() != null) {
                maxCycle = Math.max(maxCycle, state.getReturnedApproval().getPendingApproval().getCycleNumber());
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

    private Map<String, Object> buildApproverResolutionVariables(Map<String, Object> variables, String initiatorId) {
        Map<String, Object> resolved = new LinkedHashMap<>(variables == null ? Map.of() : variables);
        Map<String, Object> approverContext = new LinkedHashMap<>();
        approverContext.put(ApproverResolutionService.INITIATOR_ID_KEY, initiatorId);
        approverContext.put(ApproverResolutionService.INITIATOR_MANAGER_ID_KEY,
                resolved.getOrDefault(ApproverResolutionService.INITIATOR_MANAGER_ID_KEY, null));
        approverContext.put(ApproverResolutionService.ROLE_APPROVERS_KEY,
                resolved.getOrDefault(ApproverResolutionService.ROLE_APPROVERS_KEY, Map.of()));

        // Enrich with org data when OrganizationService is available
        if (organizationService != null && initiatorId != null) {
            try {
                Long initiatorIdLong = Long.parseLong(initiatorId);
                Long deptId = organizationService.getUserDeptId(initiatorIdLong);
                approverContext.put(ApproverResolutionService.INITIATOR_DEPT_ID_KEY, deptId);
            } catch (NumberFormatException ignored) {
                // initiatorId is not a numeric Long; skip org enrichment
            }
        }

        resolved.put(ApproverResolutionService.APPROVER_CONTEXT_KEY, approverContext);
        return resolved;
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
