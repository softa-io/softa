package io.softa.starter.flow.service.support;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import io.softa.framework.base.context.ContextHolder;
import io.softa.framework.base.context.ContextUtils;
import io.softa.framework.orm.service.RecordFieldEditGrant;
import io.softa.starter.flow.enums.FormFieldPermission;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.engine.FormPermissionService;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.PendingApproval;
import io.softa.starter.flow.service.FlowInstanceService;

/**
 * An approval step that lets its approver edit a field of the record grants them that field, while the step waits on
 * them: the approval writes it back with the decision, past the approver's own permissions on the model, and what is
 * prepared for it on its own — a file uploaded to the field — is let through on the same grant.
 *
 * <p>Only while the approver is still to act: a step they have approved, or one that no longer waits, grants nothing.
 */
@Component
public class ApprovalStepFieldEditGrant implements RecordFieldEditGrant {

    private final ObjectProvider<FlowInstanceService> instanceService;
    private final FlowBundleRegistry bundleRegistry;

    public ApprovalStepFieldEditGrant(ObjectProvider<FlowInstanceService> instanceService,
                                      FlowBundleRegistry bundleRegistry) {
        this.instanceService = instanceService;
        this.bundleRegistry = bundleRegistry;
    }

    @Override
    public boolean grants(String modelName, Serializable rowId, String fieldName) {
        Long userId = ContextHolder.existContext() ? ContextHolder.getContext().getUserId() : null;
        FlowInstanceService instances = instanceService.getIfAvailable();
        if (userId == null || rowId == null || !StringUtils.hasText(fieldName) || instances == null) {
            return false;
        }
        String approverId = userId.toString();
        // The record's approvals are the engine's to read, whatever the caller may read of them.
        List<FlowExecutionState> approvals = ContextUtils.withoutPermissionChecks(
                () -> instances.recordApprovalStates(modelName, rowId.toString()));
        return approvals.stream()
                .filter(FlowExecutionState::isApprovalOfRecord)
                .anyMatch(state -> this.letsEdit(state, approverId, fieldName));
    }

    /** Whether a step of this approval still waits on the approver and lets them edit the field. */
    private boolean letsEdit(FlowExecutionState state, String approverId, String fieldName) {
        List<PendingApproval> pending = state.getPendingApprovals();
        if (pending == null || pending.isEmpty() || state.getBundleId() == null) {
            return false;
        }
        CompiledFlowDefinition definition = bundleRegistry.getByBundleId(state.getBundleId()).orElse(null);
        Map<String, CompiledFlowNode> nodes = definition == null ? null : definition.getNodeIndex();
        if (nodes == null) {
            return false;
        }
        return pending.stream()
                .filter(step -> step.getApprovers() != null && step.getApprovers().contains(approverId))
                .filter(step -> step.getApprovedActors() == null || !step.getApprovedActors().contains(approverId))
                .map(step -> nodes.get(step.getNodeId()))
                .filter(Objects::nonNull)
                .map(node -> FormPermissionService.getFieldPermissions(node).get(fieldName))
                .anyMatch(permission -> permission == FormFieldPermission.EDITABLE
                        || permission == FormFieldPermission.REQUIRED);
    }
}
