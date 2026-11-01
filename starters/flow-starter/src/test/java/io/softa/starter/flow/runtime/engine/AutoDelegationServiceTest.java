package io.softa.starter.flow.runtime.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.softa.starter.flow.entity.FlowDelegation;
import io.softa.starter.flow.enums.FlowNodeType;
import io.softa.starter.flow.runtime.NoopApprovalActionLedger;
import io.softa.starter.flow.runtime.TestActorAccounts;
import io.softa.starter.flow.runtime.bundle.CompiledFlowNode;
import io.softa.starter.flow.runtime.state.FlowExecutionState;
import io.softa.starter.flow.runtime.state.FlowExecutionTraceEntry;
import io.softa.starter.flow.runtime.state.PendingApproval;
import io.softa.starter.flow.service.FlowDelegationService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * A delegation applies only while its delegate can act: one whose account is frozen leaves the task with whoever the
 * delegation chain reached before them, says why in the trace, and counts no use.
 */
class AutoDelegationServiceTest {

    private static final String ANN = "2001";
    private static final String BOB = "2002";
    private static final String CAT = "2003";

    private final FlowDelegationService delegations = mock(FlowDelegationService.class);
    private final AutoDelegationService service = new AutoDelegationService();
    private final CompiledFlowNode node = CompiledFlowNode.builder()
            .nodeId("manager").type(FlowNodeType.APPROVAL).label("Manager approval").build();
    private final FlowExecutionState state = FlowExecutionState.builder()
            .instanceId("inst-1").flowId("leave").trace(new ArrayList<>()).build();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "delegationService", delegations);
        ReflectionTestUtils.setField(service, "auditService",
                new FlowAuditService(new ApprovalAuditReader(new NoopApprovalActionLedger())));
        // Bob's account is frozen.
        ReflectionTestUtils.setField(service, "actorAccounts",
                TestActorAccounts.of(Map.of(2002L, "Frozen"), Map.of(BOB, "Bob Wu")));
    }

    @Test
    void aDelegateWhoCannotActLeavesTheTaskWithTheApprover() {
        delegates(ANN, BOB, 1L);
        PendingApproval pending = pending(ANN);

        service.applyAutoDelegation(pending, state, node, true);

        assertEquals(List.of(ANN), pending.getApprovers());
        assertEquals(List.of("Delegation from 2001 to 2002 not applied at node manager: Bob Wu's account is frozen"),
                state.getTrace().stream().map(FlowExecutionTraceEntry::getMessage).toList());
        verify(delegations, never()).recordDelegationUsage(anyLong());
    }

    @Test
    void theChainStopsBeforeADelegateWhoCannotAct() {
        delegates(ANN, CAT, 1L);
        delegates(CAT, BOB, 2L);
        PendingApproval pending = pending(ANN);

        service.applyAutoDelegation(pending, state, node, true);

        assertEquals(List.of(CAT), pending.getApprovers());
        verify(delegations).recordDelegationUsage(1L);
        verify(delegations, never()).recordDelegationUsage(2L);
    }

    private void delegates(String delegator, String delegate, Long id) {
        FlowDelegation delegation = new FlowDelegation();
        delegation.setId(id);
        delegation.setDelegatorId(delegator);
        delegation.setDelegateId(delegate);
        when(delegations.findActiveDelegation(eq(delegator), eq("leave"), eq("manager"), any()))
                .thenReturn(Optional.of(delegation));
    }

    private static PendingApproval pending(String approver) {
        return PendingApproval.builder().flowId("leave").nodeId("manager")
                .approvers(new ArrayList<>(List.of(approver))).build();
    }
}
