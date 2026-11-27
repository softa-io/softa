package io.softa.starter.flow.service.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import io.softa.framework.base.context.Context;
import io.softa.framework.base.context.ContextHolder;
import io.softa.starter.flow.enums.FormFieldPermission;
import io.softa.starter.flow.runtime.bundle.CompiledFlowDefinition;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.bundle.FlowBundleRegistry;
import io.softa.starter.flow.runtime.nodeconfig.ApprovalNodeConfig;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.PendingApproval;
import io.softa.starter.flow.service.FlowInstanceService;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An approval step that lets its approver edit a field grants them that field while it waits on them — so the file
 * for it can be uploaded before the approval writes it back.
 */
class ApprovalStepFieldEditGrantTest {

    private static final String CLAIM = "ExpenseClaim";
    private static final Long BUNDLE = 9L;

    private final FlowInstanceService instances = mock(FlowInstanceService.class);
    private final FlowBundleRegistry bundles = mock(FlowBundleRegistry.class);
    private final ApprovalStepFieldEditGrant grant;

    @SuppressWarnings("unchecked")
    ApprovalStepFieldEditGrantTest() {
        ObjectProvider<FlowInstanceService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(instances);
        grant = new ApprovalStepFieldEditGrant(provider, bundles);

        ApprovalNodeConfig config = new ApprovalNodeConfig();
        config.setFormPermissions(Map.of("attachments", FormFieldPermission.EDITABLE,
                "startDate", FormFieldPermission.REQUIRED, "amount", FormFieldPermission.READONLY));
        CompiledFlowNode manager = mock(CompiledFlowNode.class);
        when(manager.getParsedConfig()).thenReturn(config);
        CompiledFlowDefinition definition = new CompiledFlowDefinition();
        definition.setNodeIndex(Map.of("manager", manager));
        when(bundles.getByBundleId(BUNDLE)).thenReturn(Optional.of(definition));
    }

    /** The claim's approval waiting at the manager's step on these approvers, who of them have approved. */
    private void waitingOn(List<String> approvers, List<String> approved) {
        PendingApproval step = new PendingApproval();
        step.setNodeId("manager");
        step.setApprovers(new ArrayList<>(approvers));
        step.setApprovedActors(new ArrayList<>(approved));
        FlowExecutionState state = new FlowExecutionState();
        state.setRecordApproval(true);
        state.setBundleId(BUNDLE);
        state.setPendingApprovals(List.of(step));
        when(instances.recordApprovalStates(CLAIM, "42")).thenReturn(List.of(state));
    }

    private boolean grantsTo(Long userId, String field) {
        Context context = new Context();
        context.setUserId(userId);
        return ContextHolder.callWith(context, () -> grant.grants(CLAIM, 42L, field));
    }

    @Test
    void anApproverTheStepWaitsOn_mayEditTheFieldsItMarksEditableOrRequired() {
        waitingOn(List.of("100", "200"), List.of());

        assertTrue(grantsTo(100L, "attachments"));
        assertTrue(grantsTo(100L, "startDate"));
        assertFalse(grantsTo(100L, "amount"), "read-only on the step");
        assertFalse(grantsTo(100L, "remark"), "not on the step at all");
    }

    @Test
    void someoneTheStepDoesNotWaitOn_isGrantedNothing() {
        waitingOn(List.of("100", "200"), List.of("200"));

        assertFalse(grantsTo(200L, "attachments"), "already approved");
        assertFalse(grantsTo(300L, "attachments"), "not an approver");
    }

    @Test
    void aRecordWithNoApprovalWaiting_grantsNothing() {
        when(instances.recordApprovalStates(CLAIM, "42")).thenReturn(List.of());

        assertFalse(grantsTo(100L, "attachments"));
    }
}
